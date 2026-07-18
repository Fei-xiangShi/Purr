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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.presentation.toUserMessage
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    observePairBondUseCase: ObservePairBondUseCase,
    private val refreshPairBondUseCase: RefreshPairBondUseCase,
    observeRealtimeStateUseCase: ObserveRealtimeStateUseCase,
    observeCallStateUseCase: ObserveCallStateUseCase,
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

    private val partnerOnlineState = observeRealtimeStateUseCase()
        .map { it.partnerOnline }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val callSessionState = observeCallStateUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
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
                partnerOnlineState,
                callSessionState,
            ) { session, bond, realtimePartnerOnline, callSession ->
                HomeSources(session, bond, realtimePartnerOnline, callSession.activePairIdOrNull())
            }.collect { sources ->
                val (session, bond, realtimePartnerOnline, activeCallPairId) = sources
                val partnerOnline = realtimePartnerOnline ?: bond?.partner?.isOnline ?: false
                val isCallable = bond?.pairId != null && activeCallPairId == null
                _uiState.value = _uiState.value.copy(
                    self = session?.self,
                    pairId = bond?.pairId,
                    activeCallPairId = activeCallPairId,
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
                    val latestActiveCallPairId = callSessionState.value.activePairIdOrNull()
                    if (latestActiveCallPairId != null) {
                        _effects.emit(HomeEffect.NavigateToCall(latestActiveCallPairId))
                    } else if (latestPair?.pairId != null) {
                        _effects.emit(HomeEffect.NavigateToCall(latestPair.pairId))
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

private fun CallSession?.activePairIdOrNull(): String? =
    this?.takeIf { it.connectionState.isOngoing }?.pairId

private data class HomeSources(
    val session: AuthSession?,
    val bond: PairBond?,
    val realtimePartnerOnline: Boolean?,
    val activeCallPairId: String?,
)
