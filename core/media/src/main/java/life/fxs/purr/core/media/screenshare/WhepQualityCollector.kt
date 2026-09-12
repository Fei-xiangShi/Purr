package life.fxs.purr.core.media.screenshare

import android.os.SystemClock
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport

internal class WhepQualityCollector(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var previous: RTCStats? = null

    fun collect(report: RTCStatsReport): ScreenShareQualitySample {
        val stats = report.statsMap
        val video = stats.values.firstOrNull {
            it.type == "inbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video"
        }
        val old = previous?.takeIf { it.id == video?.id }
        previous = video
        val seconds = if (video != null && old != null) (video.timestampUs - old.timestampUs) / 1_000_000.0 else null
        fun delta(name: String) = counterDelta(video.number(name), old.number(name))
        val decoded = delta("framesDecoded")
        val received = delta("packetsReceived")
        val lost = delta("packetsLost")
        val emitted = delta("jitterBufferEmittedCount")
        val selected = stats.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")
        val pair = stats[selected]
        val codec = stats[video?.members?.get("codecId")]
        return ScreenShareQualitySample(
            sampledAtMillis = clock(),
            width = video.number("frameWidth")?.toInt(),
            height = video.number("frameHeight")?.toInt(),
            framesPerSecond = measuredRatio(decoded, seconds),
            bitrateKbps = measuredRatio(delta("bytesReceived")?.times(8.0), seconds)?.div(1_000),
            droppedVideoFrames = video.number("framesDropped")?.toLong(),
            packetLossPercent = if (received != null && lost != null) measuredRatio(lost * 100, received + lost) else null,
            jitterMs = video.number("jitter")?.times(1_000),
            roundTripTimeMs = pair.number("currentRoundTripTime")?.times(1_000),
            decodeTimeMs = measuredRatio(delta("totalDecodeTime")?.times(1_000), decoded),
            jitterBufferMs = measuredRatio(delta("jitterBufferDelay")?.times(1_000), emitted),
            freezeCount = video.number("freezeCount")?.toLong(),
            codec = codec?.members?.get("mimeType") as? String,
            decoder = video?.members?.get("decoderImplementation") as? String,
        )
    }
}

internal fun RTCStats?.number(name: String): Double? =
    (this?.members?.get(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)

internal fun counterDelta(current: Double?, previous: Double?): Double? =
    if (current == null || previous == null || !current.isFinite() || !previous.isFinite() || current < previous) null
    else current - previous

internal fun measuredRatio(numerator: Double?, denominator: Double?): Double? =
    if (numerator == null || denominator == null || !numerator.isFinite() || !denominator.isFinite() || denominator <= 0) null
    else numerator / denominator
