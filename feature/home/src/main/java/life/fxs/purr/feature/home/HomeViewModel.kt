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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.account.usecase.RefreshPairBondUseCase

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    observePairBondUseCase: ObservePairBondUseCase,
    private val refreshPairBondUseCase: RefreshPairBondUseCase,
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

    private val _uiState = MutableStateFlow(HomeState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
        }
        viewModelScope.launch {
            authSessionState.collect { session ->
                _uiState.value = _uiState.value.copy(self = session?.self)
            }
        }
        viewModelScope.launch {
            pairBondState.collect { bond ->
                _uiState.value = _uiState.value.copy(
                    pairId = bond?.pairId,
                    partner = bond?.partner,
                    isCallable = bond?.partner?.isCallable == true,
                    isLoading = false,
                )
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
                        _effects.emit(HomeEffect.ShowError("Partner is currently unavailable"))
                    }
                }
            }
        }
    }

    private suspend fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        when (val result = refreshPairBondUseCase()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> _effects.emit(HomeEffect.ShowError(result.error.toMessage()))
        }
        _uiState.value = _uiState.value.copy(isLoading = false)
    }
}

private fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> message ?: "Network error"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "Unexpected error"
}
