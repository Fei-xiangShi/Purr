package life.fxs.purr.telecom

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingSystemCallLifecycleCoordinatorTest {
    private val realtimeState = MutableStateFlow(RealtimeState())
    private val sessionState = MutableStateFlow<CallSession?>(null)
    private val pairBondState = MutableStateFlow<PairBond?>(null)
    private val observeRealtimeState = mockk<ObserveRealtimeStateUseCase>()
    private val observeCallState = mockk<ObserveCallStateUseCase>()
    private val observePairBond = mockk<ObservePairBondUseCase>()

    @Test
    fun `incoming candidate is registered before media and prepared session retains ownership`() = runTest {
        val controller = FakeLifecycleSystemCallController()
        val coordinator = coordinator(controller)
        coordinator.start()
        coordinator.start()
        runCurrent()

        realtimeState.value = RealtimeState(incomingCallCandidate = incomingCall())
        runCurrent()

        assertThat(controller.startedDescriptors.map { it.callId }).containsExactly("call-1")
        assertThat(controller.startedDescriptors.single().direction).isEqualTo(CallDirection.Incoming)

        sessionState.value = session(CallConnectionState.Preparing)
        realtimeState.value = RealtimeState()
        runCurrent()

        assertThat(controller.startedDescriptors).hasSize(1)
        assertThat(controller.disconnectedCallIds).isEmpty()

        sessionState.value = session(CallConnectionState.Disconnected)
        runCurrent()

        assertThat(controller.disconnectedCallIds).containsExactly("call-1")
    }

    @Test
    fun `candidate disappearance before preparation releases ringing Telecom call`() = runTest {
        val controller = FakeLifecycleSystemCallController()
        val coordinator = coordinator(controller)
        coordinator.start()
        runCurrent()
        realtimeState.value = RealtimeState(incomingCallCandidate = incomingCall())
        runCurrent()

        realtimeState.value = RealtimeState()
        runCurrent()

        assertThat(controller.disconnectedCallIds).containsExactly("call-1")
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        controller: SystemCallController,
    ): IncomingSystemCallLifecycleCoordinator {
        every { observeRealtimeState.invoke() } returns realtimeState
        every { observeCallState.invoke() } returns sessionState
        every { observePairBond.invoke() } returns pairBondState
        return IncomingSystemCallLifecycleCoordinator(
            observeRealtimeState = observeRealtimeState,
            observeCallState = observeCallState,
            observePairBond = observePairBond,
            systemCallController = controller,
            targetResolver = IncomingSystemCallTargetResolver(),
            applicationScope = backgroundScope,
        )
    }

    private fun incomingCall() = IncomingCall(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "partner",
        startedAtEpochMillis = 10L,
    )

    private fun session(state: CallConnectionState) = CallSession(
        callId = "call-1",
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "self"),
        roomName = "room-1",
        remoteDisplayName = "Partner",
        direction = CallDirection.Incoming,
        connectionState = state,
    )
}

private class FakeLifecycleSystemCallController : SystemCallController {
    override val events = MutableSharedFlow<SystemCallEvent>()
    val startedDescriptors = mutableListOf<SystemCallDescriptor>()
    val disconnectedCallIds = mutableListOf<String>()

    override suspend fun startCall(descriptor: SystemCallDescriptor) {
        startedDescriptors += descriptor
    }

    override suspend fun activateCall(callId: String) = Unit

    override suspend fun disconnectCall(callId: String) {
        disconnectedCallIds += callId
    }
}
