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
}
