package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallHistoryPage

interface CallHistoryRepository {
    suspend fun loadDay(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        cursor: String?,
    ): AppResult<CallHistoryPage>
}
