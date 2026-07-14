package life.fxs.purr.data.call.repository

import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.asAppError

internal suspend fun <T> callApiResult(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (throwable: Throwable) {
    if (throwable is CancellationException) throw throwable
    AppResult.Failure(throwable.asAppError())
}
