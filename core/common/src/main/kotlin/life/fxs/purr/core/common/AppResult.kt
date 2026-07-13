package life.fxs.purr.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Success) block(value)
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Failure) block(error)
}
