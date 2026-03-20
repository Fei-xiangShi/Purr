package life.fxs.purr.feature.settings

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
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.usecase.LogoutUseCase
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeAuthSessionUseCase: ObserveAuthSessionUseCase,
    private val logoutUseCase: LogoutUseCase,
) : ViewModel() {
    private val sessionState = observeAuthSessionUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            sessionState.collect { session ->
                _state.value = _state.value.copy(self = session?.self)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            when (val result = logoutUseCase()) {
                is AppResult.Success -> {
                    _state.value = _state.value.copy(isLoading = false)
                    _effects.emit(SettingsEffect.LoggedOut)
                }
                is AppResult.Failure -> {
                    _state.value = _state.value.copy(isLoading = false)
                    _effects.emit(SettingsEffect.ShowMessage(result.error.toMessage()))
                }
            }
        }
    }
}

data class SettingsState(
    val self: SelfProfile? = null,
    val isLoading: Boolean = false,
)

sealed interface SettingsEffect {
    data object LoggedOut : SettingsEffect
    data class ShowMessage(val message: String) : SettingsEffect
}

private fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> message ?: "Network error"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "Unexpected error"
}
