package life.fxs.purr.core.common

sealed interface AppError {
    data class Network(val message: String? = null) : AppError
    data class Unauthorized(val message: String = "Unauthorized") : AppError
    data class Validation(val message: String) : AppError
    data class Unexpected(val throwable: Throwable? = null) : AppError
}
