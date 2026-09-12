package life.fxs.purr.data.call.audio

/** Local level updates track display cadence without requiring a frame-rate coroutine. */
internal const val AUDIO_LEVEL_SAMPLE_INTERVAL_MILLIS = 16L

/** RTC stats are slower than display frames because each read crosses the WebRTC stats API. */
internal const val WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS = 1_000L

/**
 * Returns the next monotonic deadline without replaying missed ticks.
 *
 * A slow sample skips the missed deadline and starts a fresh interval. This keeps a
 * stalled collector from entering a zero-delay busy loop while preserving a stable
 * cadence when the collector is healthy.
 */
internal fun nextSampleAtMillis(
    scheduledAtMillis: Long,
    nowMillis: Long,
    intervalMillis: Long,
): Long {
    require(intervalMillis > 0L) { "Sample interval must be positive" }
    val next = scheduledAtMillis + intervalMillis
    return if (next > nowMillis) next else nowMillis + intervalMillis
}
