package life.fxs.purr.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.LoginUseCase

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AuthEffect>()
    val effects = _effects.asSharedFlow()

    fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.UsernameChanged -> _state.value = _state.value.copy(username = intent.value, errorMessage = null)
            is AuthIntent.PasswordChanged -> _state.value = _state.value.copy(password = intent.value, errorMessage = null)
            AuthIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val username = _state.value.username.trim()
        val password = _state.value.password
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "Username and password are required")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            when (val result = loginUseCase(username, password)) {
                is AppResult.Success -> {
                    _state.value = _state.value.copy(isLoading = false, password = "")
                    _effects.emit(AuthEffect.NavigateHome)
                }
                is AppResult.Failure -> {
                    val message = result.error.toMessage()
                    _state.value = _state.value.copy(isLoading = false, errorMessage = message)
                    _effects.emit(AuthEffect.ShowError(message))
                }
            }
        }
    }
}

private fun AppError.toMessage(): String = when (this) {
    is AppError.Network -> message ?: "Network error"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "Unexpected error"
}
