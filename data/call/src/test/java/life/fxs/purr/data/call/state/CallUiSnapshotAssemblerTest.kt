package life.fxs.purr.data.call.state

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallUiSnapshotAssemblerTest {
    @Test
    fun `runtime state follows the actual foreground service lifecycle`() = runTest {
        val availableRoutes = MutableStateFlow(listOf(AudioRoute.Earpiece, AudioRoute.Speaker))
        val activeRoute = MutableStateFlow(AudioRoute.Earpiece)
        val foregroundState = MutableStateFlow(ForegroundCallServiceState())
        val audioRouteController = mockk<AudioRouteController>()
        val callServiceController = mockk<CallServiceController>()
        every { audioRouteController.availableRoutes } returns availableRoutes
        every { audioRouteController.activeRoute } returns activeRoute
        every { callServiceController.foregroundState } returns foregroundState
        val assembler = CallUiSnapshotAssembler(audioRouteController, callServiceController)
        val observed = mutableListOf<CallUiRuntimeState>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            assembler.runtimeState.take(2).toList(observed)
        }

        foregroundState.value = ForegroundCallServiceState(activeCallId = "call-1")
        advanceUntilIdle()
        collection.join()

        assertThat(observed.map { it.isForegroundServiceActive }).containsExactly(false, true).inOrder()
        assertThat(assembler.assemble(session()).uiSnapshot.activeAudioRoute).isEqualTo(AudioRoute.Earpiece)
        assertThat(assembler.assemble(session()).uiSnapshot.isForegroundServiceActive).isTrue()
    }

    private fun session() = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self"),
        roomName = "room-1",
    )
}
