package life.fxs.purr.core.network.api

import life.fxs.purr.core.network.model.CallCalendarResponseDto
import life.fxs.purr.core.network.model.CallDetailDto
import life.fxs.purr.core.network.model.CallHistoryResponseDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PurrCallHistoryApi {
    @GET("calls/history/calendar")
    suspend fun getCalendar(
        @Query("from") fromEpochMillis: Long,
        @Query("to") toEpochMillis: Long,
        @Query("zoneId") zoneId: String,
    ): CallCalendarResponseDto

    @GET("calls/history/day")
    suspend fun getDay(
        @Query("from") fromEpochMillis: Long,
        @Query("to") toEpochMillis: Long,
        @Query("limit") limit: Int,
        @Query("before") cursor: String? = null,
    ): CallHistoryResponseDto

    @GET("calls/{callId}/details")
    suspend fun getDetail(@Path("callId") callId: String): CallDetailDto
}
