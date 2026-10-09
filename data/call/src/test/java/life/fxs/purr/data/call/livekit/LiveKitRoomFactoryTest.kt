package life.fxs.purr.data.call.livekit

import com.google.common.truth.Truth.assertThat
import io.livekit.android.audio.NoAudioHandler
import org.junit.Test

class LiveKitRoomFactoryTest {
    @Test
    fun `application remains the sole Android audio session owner`() {
        val audioOptions = applicationOwnedAudioOverrides().audioOptions

        assertThat(audioOptions?.audioHandler).isInstanceOf(NoAudioHandler::class.java)
        assertThat(audioOptions?.disableCommunicationModeWorkaround).isTrue()
    }

    @Test
    fun `voice publish and capture defaults are pinned`() {
        val options = pinnedRoomOptions()
        val publish = requireNotNull(options.audioTrackPublishDefaults)
        assertThat(publish.audioBitrate).isEqualTo(48_000)
        assertThat(publish.dtx).isTrue()
        assertThat(publish.red).isTrue()
        val capture = requireNotNull(options.audioTrackCaptureDefaults)
        assertThat(capture.noiseSuppression).isTrue()
        assertThat(capture.echoCancellation).isTrue()
        assertThat(capture.autoGainControl).isTrue()
        assertThat(capture.highPassFilter).isTrue()
    }
}
