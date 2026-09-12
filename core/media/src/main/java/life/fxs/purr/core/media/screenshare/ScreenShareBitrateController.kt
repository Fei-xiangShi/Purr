package life.fxs.purr.core.media.screenshare

/**
 * Bounds local sender backpressure. This is not an estimate of remote bandwidth:
 * a successful UDP send does not tell us whether the receiver got the packet.
 * Call once every 200 ms with a monotonic clock and session-local drop counters.
 */
class ScreenShareBitrateController(private val maximumBitrate: Int) {
    init {
        require(maximumBitrate > 0)
    }

    var bitrate: Int = maximumBitrate
        private set
    private val minimumBitrate = minOf(500_000, maximumBitrate)
    private var previousVideoDrops = 0L
    private var previousAudioDrops = 0L
    private var congestionSince: Long? = null
    private var healthySince: Long? = null
    private var lastDecreaseAt: Long? = null
    private var lastKeyframeAt: Long? = null
    private var needsKeyframe = false

    fun sample(
        nowMillis: Long,
        queuedFrames: Int,
        queueCapacity: Int,
        droppedVideoFrames: Long,
        droppedAudioFrames: Long,
    ): ScreenShareBitrateDecision {
        val newVideoDrops = droppedVideoFrames > previousVideoDrops
        val newDrops = newVideoDrops || droppedAudioFrames > previousAudioDrops
        previousVideoDrops = droppedVideoFrames.coerceAtLeast(0)
        previousAudioDrops = droppedAudioFrames.coerceAtLeast(0)
        val usage = queuedFrames.coerceAtLeast(0).toDouble() / queueCapacity.coerceAtLeast(1)
        val congested = usage >= 0.25 || newDrops
        var requestKeyframe = false
        if (congested) {
            healthySince = null
            if (congestionSince == null) congestionSince = nowMillis
            val sustained = nowMillis - requireNotNull(congestionSince) >= 400
            val canDecrease = lastDecreaseAt?.let { nowMillis - it >= 1_000 } ?: true
            if ((newDrops || sustained) && canDecrease) {
                bitrate = (bitrate * 0.75).toInt().coerceAtLeast(minimumBitrate)
                lastDecreaseAt = nowMillis
            }
        } else {
            congestionSince = null
            if (usage <= 0.10) {
                if (healthySince == null) healthySince = nowMillis
                if (nowMillis - requireNotNull(healthySince) >= 5_000) {
                    bitrate = (bitrate.toLong() + maxOf(100_000, maximumBitrate / 20))
                        .coerceAtMost(maximumBitrate.toLong()).toInt()
                    healthySince = nowMillis
                }
            } else {
                healthySince = null
            }
        }
        // Missing references need a fresh IDR, but an IDR pushed into a full queue
        // is likely to be dropped too. Remember the request until the queue drains.
        needsKeyframe = needsKeyframe || newVideoDrops
        if (needsKeyframe && usage <= 0.10 &&
            (lastKeyframeAt?.let { nowMillis - it >= 1_000 } ?: true)
        ) {
            requestKeyframe = true
            needsKeyframe = false
            lastKeyframeAt = nowMillis
        }
        return ScreenShareBitrateDecision(bitrate, requestKeyframe)
    }
}

data class ScreenShareBitrateDecision(val bitrate: Int, val requestKeyframe: Boolean)
