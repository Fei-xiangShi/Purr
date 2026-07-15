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

        assertThat(controller.state.value)
            .isEqualTo(CallAudioSessionState.Active(CallAudioProfile.Conversational))
    }

    @Test
    fun `listen-only transition is not applicable while Telecom owns the endpoint`() = runBlocking {
        val controller = TelecomManagedCallAudioSessionController()
        controller.activate()

        val outcome = controller.transitionTo(CallAudioProfile.ListenOnly)

        assertThat(outcome).isEqualTo(CallAudioTransitionOutcome.NotApplicable)
        assertThat(controller.state.value)
            .isEqualTo(CallAudioSessionState.Active(CallAudioProfile.Conversational))
    }

    @Test
    fun `transition before activation fails without mutating released state`() = runBlocking {
        val controller = TelecomManagedCallAudioSessionController()

        val outcome = controller.transitionTo(CallAudioProfile.Conversational)

        assertThat(outcome).isInstanceOf(CallAudioTransitionOutcome.Failed::class.java)
        assertThat(controller.state.value).isEqualTo(CallAudioSessionState.Released)
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
