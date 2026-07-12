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
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase
import life.fxs.purr.domain.account.usecase.ClearIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    observePairBondUseCase: ObservePairBondUseCase,
    private val refreshPairBondUseCase: RefreshPairBondUseCase,
    observeRealtimeStateUseCase: ObserveRealtimeStateUseCase,
    private val startRealtimeUpdatesUseCase: StartRealtimeUpdatesUseCase,
    private val stopRealtimeUpdatesUseCase: StopRealtimeUpdatesUseCase,
    private val refreshActiveCallUseCase: RefreshActiveCallUseCase,
    private val declineIncomingCallUseCase: DeclineIncomingCallUseCase,
    private val clearIncomingCallUseCase: ClearIncomingCallUseCase,
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
            initialValue = life.fxs.purr.domain.account.model.RealtimeState(),
        )

    private val _uiState = MutableStateFlow(HomeState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            refreshActiveCallUseCase()
        }
        viewModelScope.launch {
            combine(authSessionState, pairBondState, realtimeState) { session, bond, realtime ->
                Triple(session, bond, realtime)
            }.collect { (session, bond, realtime) ->
                if (session == null) {
                    stopRealtimeUpdatesUseCase()
                } else {
                    startRealtimeUpdatesUseCase()
                }
                val partnerOnline = realtime.partnerOnline ?: bond?.partner?.isOnline ?: false
                _uiState.value = _uiState.value.copy(
                    self = session?.self,
                    pairId = bond?.pairId,
                    partner = bond?.partner?.copy(
                        isOnline = partnerOnline,
                        isCallable = partnerOnline,
                    ),
                    isCallable = partnerOnline,
                    incomingCall = realtime.incomingCall,
                    isLoading = false,
                )
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(STATUS_RECOVERY_INTERVAL_MILLIS)
                refresh(showError = false)
                refreshActiveCallUseCase()
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
                    val pairId = _uiState.value.pairId
                    if (_uiState.value.isCallable && pairId != null) {
                        _effects.emit(HomeEffect.NavigateToCall(pairId))
                    } else {
                        _effects.emit(HomeEffect.ShowError("当前无法发起通话"))
                    }
                }
            }

            HomeIntent.AcceptIncomingCall -> {
                viewModelScope.launch {
                    val incomingCall = _uiState.value.incomingCall ?: return@launch
                    clearIncomingCallUseCase(incomingCall.callId)
                    _effects.emit(HomeEffect.NavigateToCall(incomingCall.pairId))
                }
            }

            HomeIntent.DeclineIncomingCall -> {
                viewModelScope.launch {
                    val incomingCall = _uiState.value.incomingCall ?: return@launch
                    when (val result = declineIncomingCallUseCase(incomingCall.callId)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> _effects.emit(HomeEffect.ShowError(result.error.toUserMessage()))
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

    override fun onCleared() {
        stopRealtimeUpdatesUseCase()
        super.onCleared()
    }

    private companion object {
        const val STATUS_RECOVERY_INTERVAL_MILLIS = 10_000L
    }
}
