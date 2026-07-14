package life.fxs.purr.feature.incomingcall

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservePresentableIncomingCallUseCaseTest {
    private val realtime = MutableStateFlow(RealtimeState())
    private val auth = MutableStateFlow<AuthSession?>(session())
    private val callSession = MutableStateFlow<CallSession?>(null)
    private val observeAuth = mockk<ObserveAuthSessionUseCase>()
    private val observeRealtime = mockk<ObserveRealtimeStateUseCase>()
    private val observeCall = mockk<ObserveCallStateUseCase>()
    private lateinit var useCase: ObservePresentableIncomingCallUseCase

    @Before
    fun setUp() {
        every { observeAuth.invoke() } returns auth
        every { observeRealtime.invoke() } returns realtime
        every { observeCall.invoke() } returns callSession
        useCase = ObservePresentableIncomingCallUseCase(observeAuth, observeRealtime, observeCall)
    }

    @Test
    fun `matching local session suppresses a candidate through termination`() = runTest {
        val candidate = incomingCall("call-1")
        val observed = mutableListOf<IncomingCall?>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().collect(observed::add)
        }

        realtime.value = RealtimeState(incomingCallCandidate = candidate)
        callSession.value = session("call-1", CallConnectionState.Connected)
        callSession.value = session("call-1", CallConnectionState.Disconnected)
        collection.cancelAndJoin()

        assertThat(observed).containsExactly(null, candidate, null).inOrder()
    }

    @Test
    fun `different active session blocks candidate until that session is terminal`() = runTest {
        val candidate = incomingCall("call-2")
        callSession.value = session("call-1", CallConnectionState.Connected)
        val observed = mutableListOf<IncomingCall?>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().collect(observed::add)
        }

        realtime.value = RealtimeState(incomingCallCandidate = candidate)
        callSession.value = session("call-1", CallConnectionState.Disconnected)
        collection.cancelAndJoin()

        assertThat(observed).containsExactly(null, candidate).inOrder()
    }

    @Test
    fun `logged out session never exposes a realtime candidate`() = runTest {
        val candidate = incomingCall("call-1")
        val observed = mutableListOf<IncomingCall?>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().collect(observed::add)
        }

        realtime.value = RealtimeState(incomingCallCandidate = candidate)
        auth.value = null
        collection.cancelAndJoin()

        assertThat(observed.last()).isNull()
    }

    private fun incomingCall(callId: String) = IncomingCall(
        callId = callId,
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )

    private fun session() = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        self = SelfProfile(userId = "user-a", displayName = "User A"),
    )

    private fun session(callId: String, state: CallConnectionState) = CallSession(
        callId = callId,
        pairId = "pair-1",
        participantIdentity = ParticipantIdentity(local = "user-a", remote = "user-b"),
        roomName = "room-1",
        connectionState = state,
    )
}
