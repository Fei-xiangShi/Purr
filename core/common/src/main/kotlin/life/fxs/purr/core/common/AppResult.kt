package life.fxs.purr.core.common

import retrofit2.HttpException

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

fun Throwable.asAppError(): AppError = when (this) {
    is HttpException -> when (code()) {
        401 -> AppError.Unauthorized(apiMessage() ?: "Unauthorized")
        in 400..499 -> AppError.Validation(apiMessage() ?: (message() ?: "Request failed"))
        else -> AppError.Unexpected(this)
    }
    is java.io.IOException -> AppError.Network(message)
    else -> AppError.Unexpected(this)
}

private fun HttpException.apiMessage(): String? {
    val raw = response()?.errorBody()?.string().orEmpty()
    return Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?: raw.ifBlank { null }
}
