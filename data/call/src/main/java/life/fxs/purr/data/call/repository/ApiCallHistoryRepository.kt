package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrCallHistoryApi
import life.fxs.purr.core.network.model.CallHistoryItemDto
import life.fxs.purr.domain.call.model.CallDirection
import life.fxs.purr.domain.call.model.CallHistoryEntry
import life.fxs.purr.domain.call.model.CallHistoryPage
import life.fxs.purr.domain.call.model.CallOutcome
import life.fxs.purr.domain.call.repository.CallHistoryRepository

@Singleton
class ApiCallHistoryRepository @Inject constructor(
    private val api: PurrCallHistoryApi,
) : CallHistoryRepository {
    override suspend fun loadDay(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        cursor: String?,
    ): AppResult<CallHistoryPage> = callApiResult {
        val response = api.getDay(fromEpochMillis, toEpochMillis, CALL_HISTORY_PAGE_SIZE, cursor)
        CallHistoryPage(
            calls = response.calls.map(CallHistoryItemDto::toDomain),
            nextCursor = response.nextCursor,
        )
    }

    private companion object {
        const val CALL_HISTORY_PAGE_SIZE = 50
    }
}

internal fun CallHistoryItemDto.toDomain() = CallHistoryEntry(
    callId = callId,
    direction = if (direction.equals("incoming", ignoreCase = true)) {
        CallDirection.Incoming
    } else {
        CallDirection.Outgoing
    },
    outcome = when (outcome.lowercase()) {
        "missed" -> CallOutcome.Missed
        "cancelled" -> CallOutcome.Cancelled
        else -> CallOutcome.Completed
    },
    requestedAtEpochMillis = requestedAtEpochMillis,
    startedAtEpochMillis = startedAtEpochMillis,
    connectedAtEpochMillis = connectedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    ringingDurationMillis = ringingDurationMillis,
    durationMillis = durationMillis,
    recordingStatus = recordingStatus,
)
