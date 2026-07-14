package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallHistoryApi
import life.fxs.purr.domain.call.model.CallCalendarDay
import life.fxs.purr.domain.call.repository.CallCalendarRepository

@Singleton
class ApiCallCalendarRepository @Inject constructor(
    private val api: PurrCallHistoryApi,
) : CallCalendarRepository {
    override suspend fun loadMonth(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        zoneId: String,
    ): AppResult<List<CallCalendarDay>> = callApiResult {
        api.getCalendar(fromEpochMillis, toEpochMillis, zoneId).days.map { day ->
            CallCalendarDay(
                date = day.date,
                callCount = day.callCount,
                totalDurationMillis = day.totalDurationMillis,
            )
        }
    }
}
