package life.fxs.purr.feature.home

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.ClearIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authState = MutableStateFlow<AuthSession?>(session())
    private val pairState = MutableStateFlow<PairBond?>(pairBond())
    private val realtimeState = MutableStateFlow(RealtimeState())
    private val callState = MutableStateFlow<CallSession?>(null)

    private val observeAuthSessionUseCase = mockk<ObserveAuthSessionUseCase>()
    private val observePairBondUseCase = mockk<ObservePairBondUseCase>()
    private val refreshPairBondUseCase = mockk<RefreshPairBondUseCase>()
    private val observeRealtimeStateUseCase = mockk<ObserveRealtimeStateUseCase>()
    private val observeCallStateUseCase = mockk<ObserveCallStateUseCase>()
    private val startRealtimeUpdatesUseCase = mockk<StartRealtimeUpdatesUseCase>()
    private val stopRealtimeUpdatesUseCase = mockk<StopRealtimeUpdatesUseCase>()
    private val refreshActiveCallUseCase = mockk<RefreshActiveCallUseCase>()
    private val declineIncomingCallUseCase = mockk<DeclineIncomingCallUseCase>()
    private val clearIncomingCallUseCase = mockk<ClearIncomingCallUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { observeAuthSessionUseCase.invoke() } returns authState
        every { observePairBondUseCase.invoke() } returns pairState
        every { observeRealtimeStateUseCase.invoke() } returns realtimeState
        every { observeCallStateUseCase.invoke() } returns callState
        every { startRealtimeUpdatesUseCase.invoke() } just Runs
        every { stopRealtimeUpdatesUseCase.invoke() } just Runs
        every { clearIncomingCallUseCase.invoke(any()) } just Runs
        coEvery { refreshPairBondUseCase.invoke() } returns AppResult.Success(pairBond())
        coEvery { refreshActiveCallUseCase.invoke() } returns AppResult.Success(Unit)
        coEvery { declineIncomingCallUseCase.invoke(any()) } returns AppResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `realtime presence overrides stale REST presence`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()

            realtimeState.value = RealtimeState(isConnected = true, partnerOnline = true)
            runCurrent()

            assertThat(viewModel.uiState.value.partner?.isOnline).isTrue()
            assertThat(viewModel.uiState.value.isCallable).isTrue()
            verify(atLeast = 1) { startRealtimeUpdatesUseCase.invoke() }
        }
    }

    @Test
    fun `active call remains available when partner presence is offline`() = runTest(dispatcher) {
        callState.value = callSession(CallConnectionState.Connected, pairId = "active-pair")
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.hasActiveCall).isTrue()
            assertThat(viewModel.uiState.value.activeCallPairId).isEqualTo("active-pair")
            assertThat(viewModel.uiState.value.isCallable).isFalse()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(HomeEffect.NavigateToCall("active-pair"))
        }
    }

    @Test
    fun `all non terminal call states expose the active call`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()

            listOf(
                CallConnectionState.Preparing,
                CallConnectionState.Connecting,
                CallConnectionState.Connected,
                CallConnectionState.Reconnecting,
                CallConnectionState.Terminating,
            ).forEach { connectionState ->
                callState.value = callSession(connectionState)
                runCurrent()

                assertThat(viewModel.uiState.value.hasActiveCall).isTrue()
                assertThat(viewModel.uiState.value.activeCallPairId).isEqualTo("pair-1")
            }
        }
    }

    @Test
    fun `terminal call restores start call behavior when partner is online`() = runTest(dispatcher) {
        realtimeState.value = RealtimeState(isConnected = true, partnerOnline = true)
        callState.value = callSession(CallConnectionState.Disconnected, pairId = "ended-pair")
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.hasActiveCall).isFalse()
            assertThat(viewModel.uiState.value.activeCallPairId).isNull()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(HomeEffect.NavigateToCall("pair-1"))
        }
    }

    @Test
    fun `terminal call cannot start a new call while partner is offline`() = runTest(dispatcher) {
        callState.value = callSession(CallConnectionState.Failed("ended"))
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.hasActiveCall).isFalse()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(HomeEffect.ShowError("当前无法发起通话"))
        }
    }

    @Test
    fun `accepting incoming call clears prompt and navigates to its pair`() = runTest(dispatcher) {
        realtimeState.value = RealtimeState(incomingCall = incomingCall())
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            viewModel.onIntent(HomeIntent.AcceptIncomingCall)
            runCurrent()

            verify(exactly = 1) { clearIncomingCallUseCase.invoke("call-1") }
            assertThat(effect.await()).isEqualTo(HomeEffect.NavigateToCall("pair-1"))
        }
    }

    @Test
    fun `declining incoming call ends that call`() = runTest(dispatcher) {
        realtimeState.value = RealtimeState(incomingCall = incomingCall())
        withViewModel { viewModel ->
            runCurrent()

            viewModel.onIntent(HomeIntent.DeclineIncomingCall)
            runCurrent()

            coVerify(exactly = 1) { declineIncomingCallUseCase.invoke("call-1") }
        }
    }

    @Test
    fun `background recovery does not show blocking loading state`() = runTest(dispatcher) {
        val recoveryGate = CompletableDeferred<Unit>()
        var refreshCount = 0
        coEvery { refreshPairBondUseCase.invoke() } coAnswers {
            refreshCount++
            if (refreshCount > 1) recoveryGate.await()
            AppResult.Success(pairBond())
        }
        withViewModel { viewModel ->
            runCurrent()
            assertThat(viewModel.uiState.value.isLoading).isFalse()

            advanceTimeBy(10_000L)
            runCurrent()

            assertThat(viewModel.uiState.value.isLoading).isFalse()
            recoveryGate.complete(Unit)
            runCurrent()
        }
    }

    private suspend inline fun withViewModel(block: suspend (HomeViewModel) -> Unit) {
        val viewModel = createViewModel()
        try {
            block(viewModel)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun createViewModel() = HomeViewModel(
        observeAuthSessionUseCase = observeAuthSessionUseCase,
        observePairBondUseCase = observePairBondUseCase,
        refreshPairBondUseCase = refreshPairBondUseCase,
        observeRealtimeStateUseCase = observeRealtimeStateUseCase,
        observeCallStateUseCase = observeCallStateUseCase,
        startRealtimeUpdatesUseCase = startRealtimeUpdatesUseCase,
        stopRealtimeUpdatesUseCase = stopRealtimeUpdatesUseCase,
        refreshActiveCallUseCase = refreshActiveCallUseCase,
        declineIncomingCallUseCase = declineIncomingCallUseCase,
        clearIncomingCallUseCase = clearIncomingCallUseCase,
    )

    private companion object {
        fun self() = SelfProfile(userId = "user-a", displayName = "User A")

        fun session() = AuthSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            self = self(),
        )

        fun pairBond() = PairBond(
            pairId = "pair-1",
            self = self(),
            partner = PairedPartner(
                userId = "user-b",
                displayName = "User B",
                isOnline = false,
                isCallable = false,
            ),
            bondedAtEpochMillis = 1L,
        )

        fun incomingCall() = IncomingCall(
            callId = "call-1",
            pairId = "pair-1",
            callerUserId = "user-b",
            startedAtEpochMillis = 1L,
        )

        fun callSession(
            connectionState: CallConnectionState,
            pairId: String = "pair-1",
        ) = CallSession(
            callId = "call-1",
            pairId = pairId,
            participantIdentity = ParticipantIdentity(local = "user-a", remote = "user-b"),
            roomName = "room-1",
            connectionState = connectionState,
        )
    }
}
