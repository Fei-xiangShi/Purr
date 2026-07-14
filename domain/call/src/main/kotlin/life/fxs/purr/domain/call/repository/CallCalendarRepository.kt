package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallCalendarDay

interface CallCalendarRepository {
    suspend fun loadMonth(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        zoneId: String,
    ): AppResult<List<CallCalendarDay>>
}
