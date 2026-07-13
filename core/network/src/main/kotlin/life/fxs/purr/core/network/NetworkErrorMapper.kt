package life.fxs.purr.core.network

import java.io.IOException
import life.fxs.purr.core.common.AppError
import retrofit2.HttpException

/** Converts transport-specific failures into the application error contract. */
fun Throwable.asAppError(): AppError = when (this) {
    is HttpException -> when (code()) {
        401 -> AppError.Unauthorized(apiMessage() ?: "Unauthorized")
        in 400..499 -> AppError.Validation(apiMessage() ?: (message() ?: "Request failed"))
        else -> AppError.Unexpected(this)
    }
    is IOException -> AppError.Network(message)
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
