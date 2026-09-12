package life.fxs.purr.data.call.diagnostics

import android.os.SystemClock
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.domain.call.model.AudioQualityMetrics
import life.fxs.purr.domain.call.model.TransportQualityMetrics
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import java.util.Locale
import kotlin.math.roundToInt

@Singleton
class LiveKitCallMetricsCollector @Inject constructor() {
    suspend fun collect(
        room: Room?,
        previousSample: RtpByteSample?,
    ): LiveKitMetricsResult {
        val localTrack = room?.localParticipant?.audioTrackPublications
            ?.firstOrNull { (publication, _) -> publication.source == Track.Source.MICROPHONE }
            ?.second
        val remoteParticipant = room?.remoteParticipants?.values?.firstOrNull()
        val remoteTrack = remoteParticipant?.audioTrackPublications
            ?.firstOrNull { (publication, _) -> publication.source == Track.Source.MICROPHONE }
            ?.second
        val localReport = try { localTrack?.getRTCStats() } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        val remoteReport = try { remoteTrack?.getRTCStats() } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        val allStats = localReport.stats() + remoteReport.stats()
        val selectedPairIds = allStats.filter { it.type == "transport" }
            .mapNotNull { it.members["selectedCandidatePairId"] as? String }.toSet()
        val candidatePair = allStats.firstOrNull {
            it.type == "candidate-pair" && it.id in selectedPairIds
        } ?: allStats.firstOrNull {
            it.type == "candidate-pair" && it.members["nominated"] == true &&
                it.members["state"] == "succeeded"
        }
        val outboundStats = localReport.stats("outbound-rtp")
        val inboundStats = remoteReport.stats("inbound-rtp")
        val remoteInboundStats = localReport.stats("remote-inbound-rtp")
        val currentSample = RtpByteSample(
            capturedAtMillis = SystemClock.elapsedRealtime(),
            bytesSent = outboundStats.sumMember("bytesSent"),
            bytesReceived = inboundStats.sumMember("bytesReceived"),
            packetsReceived = inboundStats.sumMember("packetsReceived"),
            packetsLost = inboundStats.sumMember("packetsLost"),
            streamIds = (outboundStats + inboundStats).map { it.id }.toSet(),
        )
        val previous = previousSample?.takeIf { it.streamIds == currentSample.streamIds }

        return LiveKitMetricsResult(
            remoteConnected = remoteParticipant != null,
            audio = AudioQualityMetrics(
                sendCodec = localReport.codecFor(outboundStats.firstOrNull()),
                receiveCodec = remoteReport.codecFor(inboundStats.firstOrNull()),
            ),
            transport = TransportQualityMetrics(
                sampledAtMillis = currentSample.capturedAtMillis.takeIf { room != null },
                roundTripTimeMs = candidatePair.memberDouble("currentRoundTripTime")?.times(1_000.0)
                    ?: remoteInboundStats.firstDouble("roundTripTime")?.times(1_000.0),
                uplinkBitrateKbps = currentSample.calculateBitrate(previous) { it.bytesSent },
                downlinkBitrateKbps = currentSample.calculateBitrate(previous) { it.bytesReceived },
                // Receiver RTCP fractionLost describes a feedback interval. Dividing its
                // delayed cumulative loss by our current packetsSent mixes two time bases.
                uplinkPacketLossPercent = remoteInboundStats.firstDouble("fractionLost")
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100),
                downlinkPacketLossPercent = packetLossPercent(
                    lost = intervalCounterDelta(currentSample.packetsLost, previous?.packetsLost),
                    delivered = intervalCounterDelta(currentSample.packetsReceived, previous?.packetsReceived),
                ),
                jitterMs = inboundStats.firstDouble("jitter")?.times(1_000.0),
                availableOutgoingKbps = candidatePair.memberDouble("availableOutgoingBitrate")?.div(1_000.0),
                availableIncomingKbps = candidatePair.memberDouble("availableIncomingBitrate")?.div(1_000.0),
                path = allStats.transportPath(candidatePair),
            ),
            sample = currentSample,
        )
    }
}

data class LiveKitMetricsResult(
    val remoteConnected: Boolean,
    val audio: AudioQualityMetrics,
    val transport: TransportQualityMetrics,
    val sample: RtpByteSample,
)

data class RtpByteSample(
    val capturedAtMillis: Long,
    val bytesSent: Double?,
    val bytesReceived: Double?,
    val packetsReceived: Double? = null,
    val packetsLost: Double? = null,
    val streamIds: Set<String> = emptySet(),
)

internal fun intervalCounterDelta(current: Double?, previous: Double?): Double? =
    if (current == null || previous == null || !current.isFinite() || !previous.isFinite() || current < previous) null
    else current - previous

private fun RTCStatsReport?.stats(type: String? = null): List<RTCStats> =
    this?.statsMap?.values?.filter { type == null || it.type == type }.orEmpty()

private fun RTCStats?.memberDouble(name: String): Double? = (this?.members?.get(name) as? Number)?.toDouble()

private fun List<RTCStats>.firstDouble(name: String): Double? = firstNotNullOfOrNull { it.memberDouble(name) }

private fun List<RTCStats>.sumMember(name: String): Double? {
    val values = mapNotNull { it.memberDouble(name) }
    return values.takeIf { it.isNotEmpty() }?.sum()
}

private fun RtpByteSample.calculateBitrate(
    previous: RtpByteSample?,
    selector: (RtpByteSample) -> Double?,
): Double? {
    previous ?: return null
    val currentBytes = selector(this) ?: return null
    val previousBytes = selector(previous) ?: return null
    val elapsedSeconds = (capturedAtMillis - previous.capturedAtMillis) / 1_000.0
    if (elapsedSeconds <= 0.0 || currentBytes < previousBytes) return null
    return (currentBytes - previousBytes) * 8.0 / elapsedSeconds / 1_000.0
}

internal fun packetLossPercent(lost: Double?, delivered: Double?): Double? {
    if (lost == null || delivered == null) return null
    return sentPacketLossPercent(lost, lost.coerceAtLeast(0.0) + delivered.coerceAtLeast(0.0))
}

internal fun sentPacketLossPercent(lost: Double?, sent: Double?): Double? {
    if (lost == null || sent == null || !lost.isFinite() || !sent.isFinite()) return null
    return if (sent > 0.0) (lost.coerceAtLeast(0.0) / sent * 100.0).coerceIn(0.0, 100.0) else null
}

private fun RTCStatsReport?.codecFor(rtpStats: RTCStats?): String? {
    val codecId = rtpStats?.members?.get("codecId") as? String ?: return null
    val codec = this?.statsMap?.get(codecId) ?: return null
    val mimeType = codec.members["mimeType"] as? String ?: return null
    val clockRate = codec.memberDouble("clockRate")?.roundToInt()
    return if (clockRate != null) "$mimeType / ${clockRate / 1_000} kHz" else mimeType
}

private fun List<RTCStats>.transportPath(candidatePair: RTCStats?): String? {
    candidatePair ?: return null
    val localId = candidatePair.members["localCandidateId"] as? String
    val remoteId = candidatePair.members["remoteCandidateId"] as? String
    val local = firstOrNull { it.id == localId }
    val remote = firstOrNull { it.id == remoteId }
    val protocol = (local?.members?.get("protocol") as? String)?.uppercase(Locale.ROOT)
    val localType = local?.members?.get("candidateType") as? String
    val remoteType = remote?.members?.get("candidateType") as? String
    return listOfNotNull(protocol, localType, remoteType).joinToString(" / ").takeIf { it.isNotBlank() }
}
