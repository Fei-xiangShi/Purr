package life.fxs.purr.feature.auth

sealed interface AuthIntent {
    data class UsernameChanged(val value: String) : AuthIntent
    data class PasswordChanged(val value: String) : AuthIntent
    data object Submit : AuthIntent
}

data class AuthState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface AuthEffect {
    data object NavigateHome : AuthEffect
    data class ShowError(val message: String) : AuthEffect
}
