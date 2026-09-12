package life.fxs.purr.core.media.screenshare

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All rates are measured over a sample interval. Null means unavailable, never zero. */
data class ScreenShareQualitySample(
    val sampledAtMillis: Long,
    val width: Int? = null,
    val height: Int? = null,
    val framesPerSecond: Double? = null,
    val bitrateKbps: Double? = null,
    val targetBitrateKbps: Double? = null,
    val queuedFrames: Int? = null,
    val queueCapacity: Int? = null,
    val droppedVideoFrames: Long? = null,
    val droppedAudioFrames: Long? = null,
    val packetLossPercent: Double? = null,
    val jitterMs: Double? = null,
    val roundTripTimeMs: Double? = null,
    val decodeTimeMs: Double? = null,
    val jitterBufferMs: Double? = null,
    val freezeCount: Long? = null,
    val codec: String? = null,
    val decoder: String? = null,
)

data class ScreenShareDiagnostics(
    val callId: String,
    val shareId: String,
    val sample: ScreenShareQualitySample? = null,
)

/** Each media attempt owns an epoch so delayed native callbacks cannot revive old data. */
@Singleton
class ScreenShareDiagnosticsStore @Inject constructor() {
    private var publisherEpoch = 0L
    private var receiverEpoch = 0L
    private val publishingState = MutableStateFlow<ScreenShareDiagnostics?>(null)
    private val receivingState = MutableStateFlow<ScreenShareDiagnostics?>(null)
    val publishing = publishingState.asStateFlow()
    val receiving = receivingState.asStateFlow()

    @Synchronized fun beginPublishing(callId: String, shareId: String): Long {
        publishingState.value = ScreenShareDiagnostics(callId, shareId)
        return ++publisherEpoch
    }
    @Synchronized fun beginReceiving(callId: String, shareId: String): Long {
        receivingState.value = ScreenShareDiagnostics(callId, shareId)
        return ++receiverEpoch
    }
    @Synchronized fun publish(epoch: Long, sample: ScreenShareQualitySample) {
        if (epoch == publisherEpoch) publishingState.value = publishingState.value?.copy(sample = sample)
    }
    @Synchronized fun receive(epoch: Long, sample: ScreenShareQualitySample) {
        if (epoch == receiverEpoch) receivingState.value = receivingState.value?.copy(sample = sample)
    }
    @Synchronized fun stopPublishing(epoch: Long) {
        if (epoch == publisherEpoch) { publisherEpoch++; publishingState.value = null }
    }
    @Synchronized fun stopReceiving(epoch: Long) {
        if (epoch == receiverEpoch) { receiverEpoch++; receivingState.value = null }
    }
}
