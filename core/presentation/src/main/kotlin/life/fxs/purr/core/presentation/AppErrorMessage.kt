package life.fxs.purr.core.presentation

import life.fxs.purr.core.common.AppError

fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> message ?: "网络错误"
    is AppError.Unauthorized -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> throwable?.message ?: "发生未知错误"
}
