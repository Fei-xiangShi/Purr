package life.fxs.purr.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.model.RealtimeState
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallLifecycleState
import life.fxs.purr.domain.call.usecase.ObserveCallLifecycleUseCase

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    observePairBondUseCase: ObservePairBondUseCase,
    private val refreshPairBondUseCase: RefreshPairBondUseCase,
    observeRealtimeStateUseCase: ObserveRealtimeStateUseCase,
    observeCallLifecycleUseCase: ObserveCallLifecycleUseCase,
) : ViewModel() {
    private val _effects = MutableSharedFlow<HomeEffect>()
    val effects = _effects.asSharedFlow()

    private val authSessionState = observeAuthSessionUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val pairBondState = observePairBondUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val realtimeState = observeRealtimeStateUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RealtimeState(),
        )

    private val callLifecycleState = observeCallLifecycleUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CallLifecycleState(),
        )

    private val _uiState = MutableStateFlow(HomeState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
        }
        viewModelScope.launch {
            combine(
                authSessionState,
                pairBondState,
                realtimeState,
                callLifecycleState,
            ) { session, bond, realtime, callLifecycle ->
                HomeSources(session, bond, realtime, callLifecycle)
            }.collect { sources ->
                val (session, bond, realtime, callLifecycle) = sources
                val realtimePartnerOnline = realtime.partnerOnline
                val partnerOnline = realtimePartnerOnline ?: bond?.partner?.isOnline ?: false
                val isCallable = bond?.pairId != null &&
                    callLifecycle.resumableSession == null &&
                    !callLifecycle.isNewCallBlocked && realtime.incomingCallCandidate == null
                _uiState.value = _uiState.value.copy(
                    self = session?.self,
                    pairId = bond?.pairId,
                    activeCallTarget = callLifecycle.resumableSession?.toHomeCallTarget(),
                    isEndingCall = callLifecycle.isTerminationInProgress,
                    partner = bond?.partner?.copy(
                        isOnline = partnerOnline == true,
                        isCallable = isCallable,
                    ),
                    partnerPresenceOnline = realtimePartnerOnline,
                    isCallable = isCallable,
                    isLoading = false,
                )
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(STATUS_RECOVERY_INTERVAL_MILLIS)
                refresh(showError = false)
            }
        }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.RefreshStatus -> {
                viewModelScope.launch {
                    refresh()
                }
            }

            HomeIntent.StartCall -> {
                viewModelScope.launch {
                    // Re-read source snapshots so a just-emitted call state cannot be bypassed by stale UI state.
                    val latestPair = pairBondState.value
                    val latestCallLifecycle = callLifecycleState.value
                    val resumeTarget = latestCallLifecycle.resumableSession?.toHomeCallTarget()
                    if (resumeTarget != null) {
                        _effects.emit(HomeEffect.NavigateToCall(resumeTarget))
                    } else if (latestCallLifecycle.isNewCallBlocked) {
                        _effects.emit(HomeEffect.ShowError("上一通电话正在结束，请稍候"))
                    } else if (realtimeState.value.incomingCallCandidate != null) {
                        _effects.emit(HomeEffect.ShowError("请先接听或拒绝当前来电"))
                    } else if (latestPair?.pairId != null) {
                        _effects.emit(HomeEffect.NavigateToCall(HomeCallTarget.NewOutgoing(latestPair.pairId)))
                    } else {
                        _effects.emit(HomeEffect.ShowError("当前无法发起通话"))
                    }
                }
            }

        }
    }

    private suspend fun refresh(showError: Boolean = true) {
        if (showError) {
            _uiState.value = _uiState.value.copy(isLoading = true)
        }
        when (val result = refreshPairBondUseCase()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> if (showError) {
                _effects.emit(HomeEffect.ShowError(result.error.toUserMessage()))
            }
        }
        if (showError) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private companion object {
        const val STATUS_RECOVERY_INTERVAL_MILLIS = 10_000L
    }
}

private fun CallSession.toHomeCallTarget() = HomeCallTarget.Existing(
    pairId = pairId,
    callId = callId,
    direction = direction,
)

private data class HomeSources(
    val session: AuthSession?,
    val bond: PairBond?,
    val realtime: RealtimeState,
    val callLifecycle: CallLifecycleState,
)
