package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.asAppError
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CallHistoryItemDto
import life.fxs.purr.domain.call.model.CallHistoryEntry
import life.fxs.purr.domain.call.model.CallHistoryPage
import life.fxs.purr.domain.call.repository.CallHistoryRepository

@Singleton
class ApiCallHistoryRepository @Inject constructor(
    private val api: PurrCallApi,
) : CallHistoryRepository {
    override suspend fun loadHistory(cursor: String?): AppResult<CallHistoryPage> {
        return try {
            val response = api.getCallHistory(CALL_HISTORY_PAGE_SIZE, cursor)
            AppResult.Success(
                CallHistoryPage(
                    calls = response.calls.map(CallHistoryItemDto::toDomain),
                    nextCursor = response.nextCursor,
                ),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(throwable.asAppError())
        }
    }

    private companion object {
        const val CALL_HISTORY_PAGE_SIZE = 20
    }
}

private fun CallHistoryItemDto.toDomain() = CallHistoryEntry(
    callId = callId,
    startedAtEpochMillis = startedAtEpochMillis,
    durationMillis = durationMillis,
)
