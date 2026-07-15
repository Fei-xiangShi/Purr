package life.fxs.purr.telecom

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemCallEventCoordinatorTest {
    @Test
    fun `start is idempotent and one failed disconnect does not stop later events`() = runTest {
        val controller = FakeSystemCallController()
        val repository = mockk<CallRepository>()
        coEvery { repository.disconnectCall("call-1") } throws IllegalStateException("temporary failure")
        coEvery { repository.disconnectCall("call-2") } returns AppResult.Success(Unit)
        val coordinator = SystemCallEventCoordinator(
            systemCallController = controller,
            disconnectCall = DisconnectCallUseCase(repository),
            applicationScope = backgroundScope,
        )

        coordinator.start()
        coordinator.start()
        runCurrent()
        controller.emitDisconnect("call-1")
        runCurrent()
        controller.emitDisconnect("call-2")
        runCurrent()

        coVerify(exactly = 1) { repository.disconnectCall("call-1") }
        coVerify(exactly = 1) { repository.disconnectCall("call-2") }
        assertThat(controller.events.subscriptionCount.value).isEqualTo(1)
    }
}

private class FakeSystemCallController : SystemCallController {
    private val mutableEvents = MutableSharedFlow<SystemCallEvent>(extraBufferCapacity = 4)
    override val events: MutableSharedFlow<SystemCallEvent> = mutableEvents

    override suspend fun startCall(descriptor: SystemCallDescriptor) = Unit

    override suspend fun activateCall(callId: String) = Unit

    override suspend fun disconnectCall(callId: String) = Unit

    fun emitDisconnect(callId: String) {
        check(mutableEvents.tryEmit(SystemCallEvent.DisconnectRequested(callId)))
    }
}
