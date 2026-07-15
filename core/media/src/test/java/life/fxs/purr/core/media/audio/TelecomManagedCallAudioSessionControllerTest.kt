package life.fxs.purr.core.media.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TelecomManagedCallAudioSessionControllerTest {
    @Test
    fun `activation exposes media readiness without acquiring direct Android audio ownership`() = runBlocking {
        val controller = TelecomManagedCallAudioSessionController()

        controller.activate()
        controller.activate()

        assertThat(controller.state.value).isEqualTo(CallAudioSessionState.Active)
    }

    @Test
    fun `release is idempotent`() = runBlocking {
        val controller = TelecomManagedCallAudioSessionController()
        controller.activate()

        controller.release()
        controller.release()

        assertThat(controller.state.value).isEqualTo(CallAudioSessionState.Released)
    }
}
