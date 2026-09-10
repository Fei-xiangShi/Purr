package life.fxs.purr.telecom

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.media.telecom.SystemCallInterruptionDispatcher
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.call.usecase.ResumeCallAfterSystemCallUseCase
import life.fxs.purr.domain.call.usecase.SuspendCallForSystemCallUseCase
import life.fxs.purr.incomingcall.IncomingCallUiLauncher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemCallEventCoordinatorTest {
    private val sessionState = MutableStateFlow<CallSession?>(null)
    private val realtimeState = MutableStateFlow(RealtimeState())
    private val pairBondState = MutableStateFlow<PairBond?>(null)
    private val disconnectCall = mockk<DisconnectCallUseCase>()
    private val suspendCallForSystemCall = mockk<SuspendCallForSystemCallUseCase>()
    private val resumeCallAfterSystemCall = mockk<ResumeCallAfterSystemCallUseCase>()
    private val declineIncomingCall = mockk<DeclineIncomingCallUseCase>()
    private val observeCallState = mockk<ObserveCallStateUseCase>()
    private val observeRealtimeState = mockk<ObserveRealtimeStateUseCase>()
    private val observePairBond = mockk<ObservePairBondUseCase>()
    private val incomingCallUiLauncher = mockk<IncomingCallUiLauncher>(relaxed = true)

    @Test
    fun `start is idempotent and one failed disconnect does not stop later events`() = runTest {
        val harness = harness()
        sessionState.value = session("call-1")
        coEvery { disconnectCall.invoke("call-1") } throws IllegalStateException("temporary failure")
        coEvery { disconnectCall.invoke("call-2") } returns AppResult.Success(Unit)

        harness.coordinator.start()
        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.DisconnectRequested("call-1"))
        runCurrent()
        sessionState.value = session("call-2")
        harness.controller.emit(SystemCallEvent.DisconnectRequested("call-2"))
        runCurrent()

        coVerify(exactly = 1) { disconnectCall.invoke("call-1") }
        coVerify(exactly = 1) { disconnectCall.invoke("call-2") }
        assertThat(harness.controller.events.subscriptionCount.value).isEqualTo(1)
    }

    @Test
    fun `system disconnect while ringing declines the server call`() = runTest {
        val harness = harness()
        realtimeState.value = RealtimeState(incomingCallCandidate = incomingCall())

        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.DisconnectRequested("call-1"))
        runCurrent()

        coVerify(exactly = 1) { declineIncomingCall.invoke("call-1") }
        coVerify(exactly = 0) { disconnectCall.invoke(any()) }
    }

    @Test
    fun `system answer foregrounds the exact incoming call`() = runTest {
        val harness = harness()
        realtimeState.value = RealtimeState(incomingCallCandidate = incomingCall())

        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.AnswerRequested("call-1"))
        runCurrent()

        verify(exactly = 1) {
            incomingCallUiLauncher.launchAnswer(
                match { content ->
                    content.callId == "call-1" &&
                        content.pairId == "pair-1" &&
                        content.startedAtEpochMillis == 10L
                },
            )
        }
    }

    @Test
    fun `stale system answer releases its Telecom call without opening UI`() = runTest {
        val harness = harness()

        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.AnswerRequested("stale-call"))
        runCurrent()

        assertThat(harness.controller.disconnectedCallIds).containsExactly("stale-call")
        verify(exactly = 0) { incomingCallUiLauncher.launchAnswer(any()) }
    }

    @Test
    fun `answer callback for a terminal local attempt requires a current server candidate`() = runTest {
        val harness = harness()
        sessionState.value = session("call-1", CallConnectionState.Failed("ICE failed"))

        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.AnswerRequested("call-1"))
        runCurrent()

        assertThat(harness.controller.disconnectedCallIds).containsExactly("call-1")
        verify(exactly = 0) { incomingCallUiLauncher.launchAnswer(any()) }
    }

    @Test
    fun `explicit answer may reopen the same server call after a terminal local attempt`() = runTest {
        val harness = harness()
        sessionState.value = session("call-1", CallConnectionState.Failed("ICE failed"))
        realtimeState.value = RealtimeState(incomingCallCandidate = incomingCall())

        harness.coordinator.start()
        runCurrent()
        harness.controller.emit(SystemCallEvent.AnswerRequested("call-1"))
        runCurrent()

        verify(exactly = 1) {
            incomingCallUiLauncher.launchAnswer(match { it.callId == "call-1" })
        }
        assertThat(harness.controller.disconnectedCallIds).isEmpty()
    }

    @Test
    fun `registered interruption handler delegates inactive and active to domain use cases`() = runTest {
        val harness = harness()
        val inactive = SystemCallInterruptionRequest("call-1", "operation-1", 1L)
        val active = inactive.copy(telecomSequence = 2L)
        coEvery { suspendCallForSystemCall.invoke(inactive) } returns SystemCallInterruptionResult.Applied
        coEvery { resumeCallAfterSystemCall.invoke(active) } returns SystemCallInterruptionResult.RetryScheduled

        harness.coordinator.start()
        val inactiveResult = harness.interruptionDispatcher.dispatchInactive(inactive)
        val activeResult = harness.interruptionDispatcher.dispatchActive(active)

        assertThat(inactiveResult).isEqualTo(SystemCallInterruptionResult.Applied)
        assertThat(activeResult).isEqualTo(SystemCallInterruptionResult.RetryScheduled)
        coVerify(exactly = 1) { suspendCallForSystemCall.invoke(inactive) }
        coVerify(exactly = 1) { resumeCallAfterSystemCall.invoke(active) }
    }

    private fun TestScope.harness(): Harness {
        every { observeCallState.invoke() } returns sessionState
        every { observeRealtimeState.invoke() } returns realtimeState
        every { observePairBond.invoke() } returns pairBondState
        coEvery { disconnectCall.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { declineIncomingCall.invoke(any()) } returns AppResult.Success(Unit)
        coEvery { suspendCallForSystemCall.invoke(any()) } returns SystemCallInterruptionResult.Applied
        coEvery { resumeCallAfterSystemCall.invoke(any()) } returns SystemCallInterruptionResult.Applied
        val controller = FakeSystemCallController()
        val interruptionDispatcher = SystemCallInterruptionDispatcher()
        return Harness(
            controller = controller,
            interruptionDispatcher = interruptionDispatcher,
            coordinator = SystemCallEventCoordinator(
                systemCallController = controller,
                interruptionDispatcher = interruptionDispatcher,
                suspendCallForSystemCall = suspendCallForSystemCall,
                resumeCallAfterSystemCall = resumeCallAfterSystemCall,
                disconnectCall = disconnectCall,
                declineIncomingCall = declineIncomingCall,
                observeCallState = observeCallState,
                observeRealtimeState = observeRealtimeState,
                observePairBond = observePairBond,
                incomingCallUiLauncher = incomingCallUiLauncher,
                applicationScope = backgroundScope,
            ),
        )
    }

    private fun incomingCall() = IncomingCall(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "partner",
        startedAtEpochMillis = 10L,
    )

    private fun session(
        callId: String,
        state: CallConnectionState = CallConnectionState.Connected,
    ) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self"),
        roomName = "room-1",
        direction = CallDirection.Incoming,
        connectionState = state,
    )
}

private data class Harness(
    val controller: FakeSystemCallController,
    val interruptionDispatcher: SystemCallInterruptionDispatcher,
    val coordinator: SystemCallEventCoordinator,
)

private class FakeSystemCallController : SystemCallController {
    override val events = MutableSharedFlow<SystemCallEvent>(extraBufferCapacity = 4)
    val disconnectedCallIds = mutableListOf<String>()

    override suspend fun startCall(descriptor: SystemCallDescriptor) = Unit

    override suspend fun activateCall(callId: String) = Unit

    override suspend fun disconnectCall(callId: String) {
        disconnectedCallIds += callId
    }

    fun emit(event: SystemCallEvent) {
        check(events.tryEmit(event))
    }
}
