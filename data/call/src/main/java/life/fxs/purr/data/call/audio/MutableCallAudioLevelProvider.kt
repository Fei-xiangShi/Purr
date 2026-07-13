package life.fxs.purr.data.call.audio

import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import livekit.org.webrtc.AudioTrackSink

@Singleton
class MutableCallAudioLevelProvider @Inject constructor() : CallAudioLevelProvider, AudioTrackSink {
    private val _localAudioLevel = MutableStateFlow(0f)

    override val localAudioLevel: StateFlow<Float> = _localAudioLevel.asStateFlow()

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        calculatePcmDisplayLevel(
            audioData = audioData,
            bitsPerSample = bitsPerSample,
            numberOfChannels = numberOfChannels,
            numberOfFrames = numberOfFrames,
        )?.let { _localAudioLevel.value = it }
    }

    fun reset() {
        _localAudioLevel.value = 0f
    }
}
