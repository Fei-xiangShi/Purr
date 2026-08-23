package life.fxs.purr.feature.home

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.model.CallLifecycleState
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.ParticipantIdentity
import life.fxs.purr.domain.call.usecase.ObserveCallLifecycleUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authState = MutableStateFlow<AuthSession?>(session())
    private val pairState = MutableStateFlow<PairBond?>(pairBond())
    private val realtimeState = MutableStateFlow(RealtimeState())
    private val callLifecycleState = MutableStateFlow(CallLifecycleState())

    private val observeAuthSessionUseCase = mockk<ObserveAuthSessionUseCase>()
    private val observePairBondUseCase = mockk<ObservePairBondUseCase>()
    private val refreshPairBondUseCase = mockk<RefreshPairBondUseCase>()
    private val observeRealtimeStateUseCase = mockk<ObserveRealtimeStateUseCase>()
    private val observeCallLifecycleUseCase = mockk<ObserveCallLifecycleUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { observeAuthSessionUseCase.invoke() } returns authState
        every { observePairBondUseCase.invoke() } returns pairState
        every { observeRealtimeStateUseCase.invoke() } returns realtimeState
        every { observeCallLifecycleUseCase.invoke() } returns callLifecycleState
        coEvery { refreshPairBondUseCase.invoke() } returns AppResult.Success(pairBond())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `paired partner is callable for online offline and unknown presence`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()

            listOf(true, false, null).forEach { partnerOnline ->
                realtimeState.value = RealtimeState(
                    isConnected = partnerOnline != null,
                    partnerOnline = partnerOnline,
                )
                runCurrent()

                assertThat(viewModel.uiState.value.partner?.isOnline)
                    .isEqualTo(partnerOnline == true)
                assertThat(viewModel.uiState.value.partnerPresenceOnline)
                    .isEqualTo(partnerOnline)
                assertThat(viewModel.uiState.value.isCallable).isTrue()
                assertThat(viewModel.uiState.value.partner?.isCallable).isTrue()
            }
        }
    }

    @Test
    fun `active call is resumed when partner presence is offline`() = runTest(dispatcher) {
        realtimeState.value = RealtimeState(isConnected = true, partnerOnline = false)
        callLifecycleState.value = CallLifecycleState(
            session = callSession(
                CallConnectionState.Connected,
                pairId = "active-pair",
                direction = CallDirection.Incoming,
            ),
        )
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.hasActiveCall).isTrue()
            assertThat(viewModel.uiState.value.activeCallTarget).isEqualTo(
                HomeCallTarget.Existing(
                    pairId = "active-pair",
                    direction = CallDirection.Incoming,
                    callId = "call-1",
                ),
            )
            assertThat(viewModel.uiState.value.isCallable).isFalse()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(
                HomeEffect.NavigateToCall(
                    HomeCallTarget.Existing(
                        pairId = "active-pair",
                        direction = CallDirection.Incoming,
                        callId = "call-1",
                    ),
                ),
            )
        }
    }

    @Test
    fun `every resumable call state exposes an exact navigation target`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()

            listOf(
                CallConnectionState.Preparing,
                CallConnectionState.Connecting,
                CallConnectionState.Connected,
                CallConnectionState.Reconnecting,
            ).forEach { connectionState ->
                callLifecycleState.value = CallLifecycleState(session = callSession(connectionState))
                runCurrent()

                assertThat(viewModel.uiState.value.hasActiveCall).isTrue()
                assertThat((viewModel.uiState.value.activeCallTarget as? HomeCallTarget.Existing)?.callId)
                    .isEqualTo("call-1")
                assertThat(viewModel.uiState.value.isCallable).isFalse()
                assertThat(viewModel.uiState.value.partner?.isCallable).isFalse()
            }
        }
    }

    @Test
    fun `terminating call is not resumable and keeps the primary action blocked`() = runTest(dispatcher) {
        callLifecycleState.value = CallLifecycleState(
            session = callSession(CallConnectionState.Terminating),
            disconnectingCallId = "call-1",
        )
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.hasActiveCall).isFalse()
            assertThat(viewModel.uiState.value.activeCallTarget).isNull()
            assertThat(viewModel.uiState.value.isEndingCall).isTrue()
            assertThat(viewModel.uiState.value.isCallable).isFalse()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(HomeEffect.ShowError("上一通电话正在结束，请稍候"))
        }
    }

    @Test
    fun `local terminal state is callable while server end synchronization is pending`() =
        runTest(dispatcher) {
            callLifecycleState.value = CallLifecycleState(
                session = callSession(CallConnectionState.Disconnected),
                disconnectingCallId = "call-1",
            )
            withViewModel { viewModel ->
                runCurrent()
                val effect = async { viewModel.effects.first() }
                runCurrent()

                assertThat(viewModel.uiState.value.hasActiveCall).isFalse()
                assertThat(viewModel.uiState.value.isEndingCall).isFalse()
                assertThat(viewModel.uiState.value.isCallable).isTrue()

                viewModel.onIntent(HomeIntent.StartCall)
                runCurrent()

                assertThat(effect.await()).isEqualTo(
                    HomeEffect.NavigateToCall(HomeCallTarget.NewOutgoing(pairId = "pair-1")),
                )
            }
        }

    @Test
    fun `every terminal call state allows a new call regardless of presence`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()

            listOf(
                CallConnectionState.Disconnected to true,
                CallConnectionState.Failed("ended") to false,
            ).forEach { (connectionState, partnerOnline) ->
                realtimeState.value = RealtimeState(
                    isConnected = true,
                    partnerOnline = partnerOnline,
                )
                callLifecycleState.value = CallLifecycleState(
                    session = callSession(connectionState, pairId = "ended-pair"),
                )
                runCurrent()
                val effect = async { viewModel.effects.first() }
                runCurrent()

                assertThat(viewModel.uiState.value.hasActiveCall).isFalse()
                assertThat(viewModel.uiState.value.activeCallTarget).isNull()
                assertThat(viewModel.uiState.value.isCallable).isTrue()

                viewModel.onIntent(HomeIntent.StartCall)
                runCurrent()

                assertThat(effect.await()).isEqualTo(
                    HomeEffect.NavigateToCall(HomeCallTarget.NewOutgoing(pairId = "pair-1")),
                )
            }
        }
    }

    @Test
    fun `unpaired user cannot start a call`() = runTest(dispatcher) {
        pairState.value = null
        realtimeState.value = RealtimeState(isConnected = true, partnerOnline = true)
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }
            runCurrent()

            assertThat(viewModel.uiState.value.pairId).isNull()
            assertThat(viewModel.uiState.value.partner).isNull()
            assertThat(viewModel.uiState.value.isCallable).isFalse()

            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(HomeEffect.ShowError("当前无法发起通话"))
        }
    }

    @Test
    fun `start call uses latest pair and call snapshots`() = runTest(dispatcher) {
        withViewModel { viewModel ->
            runCurrent()
            val effect = async { viewModel.effects.first() }

            pairState.value = null
            callLifecycleState.value = CallLifecycleState(
                session = callSession(CallConnectionState.Connected, pairId = "active-pair"),
            )
            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(
                HomeEffect.NavigateToCall(
                    HomeCallTarget.Existing(pairId = "active-pair", callId = "call-1", direction = CallDirection.Outgoing),
                ),
            )
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

    @Test
    fun `manual refresh does not block a known paired call`() = runTest(dispatcher) {
        val refreshGate = CompletableDeferred<Unit>()
        var refreshCount = 0
        coEvery { refreshPairBondUseCase.invoke() } coAnswers {
            refreshCount++
            if (refreshCount > 1) refreshGate.await()
            AppResult.Success(pairBond())
        }
        withViewModel { viewModel ->
            runCurrent()
            viewModel.onIntent(HomeIntent.RefreshStatus)
            runCurrent()

            assertThat(viewModel.uiState.value.isLoading).isTrue()
            assertThat(viewModel.uiState.value.isCallable).isTrue()

            val effect = async { viewModel.effects.first() }
            runCurrent()
            viewModel.onIntent(HomeIntent.StartCall)
            runCurrent()

            assertThat(effect.await()).isEqualTo(
                HomeEffect.NavigateToCall(HomeCallTarget.NewOutgoing(pairId = "pair-1")),
            )
            refreshGate.complete(Unit)
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
        observeCallLifecycleUseCase = observeCallLifecycleUseCase,
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

        fun callSession(
            connectionState: CallConnectionState,
            pairId: String = "pair-1",
            direction: CallDirection = CallDirection.Outgoing,
        ) = CallSession(
            callId = "call-1",
            pairId = pairId,
            participantIdentity = ParticipantIdentity(local = "user-a", remote = "user-b"),
            roomName = "room-1",
            direction = direction,
            connectionState = connectionState,
        )
    }
}
