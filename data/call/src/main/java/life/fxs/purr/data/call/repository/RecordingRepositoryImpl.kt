package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.asAppError
import life.fxs.purr.core.network.api.PurrRecordingApi
import life.fxs.purr.core.network.model.CallRecordingDto
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.CallRecordingStatus
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.model.RecordingPage
import life.fxs.purr.domain.call.repository.RecordingRepository

@Singleton
class RecordingRepositoryImpl @Inject constructor(
    private val api: PurrRecordingApi,
) : RecordingRepository {
    override suspend fun loadCallRecordings(callId: String): AppResult<List<CallRecording>> = apiResult {
        api.getRecordings(callId).recordings.map(CallRecordingDto::toDomain)
    }

    override suspend fun loadRecordingLibrary(cursor: String?): AppResult<RecordingPage> = apiResult {
        api.getRecordingLibrary(RECORDING_LIBRARY_PAGE_SIZE, cursor).let { response ->
            RecordingPage(
                recordings = response.recordings.map(CallRecordingDto::toDomain),
                nextCursor = response.nextCursor,
            )
        }
    }

    override suspend fun createRecordingDownload(
        callId: String,
        recordingId: String,
    ): AppResult<RecordingDownload> = apiResult {
        api.createRecordingDownload(callId, recordingId).let { response ->
            RecordingDownload(
                recordingId = response.recordingId,
                url = response.url,
                expiresAtEpochMillis = response.expiresAtEpochMillis,
            )
        }
    }

    private suspend fun <T> apiResult(block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AppResult.Failure(throwable.asAppError())
    }

    private companion object {
        const val RECORDING_LIBRARY_PAGE_SIZE = 20
    }
}

private fun CallRecordingDto.toDomain() = CallRecording(
    recordingId = recordingId,
    callId = callId,
    status = when (status.lowercase()) {
        "stopped" -> CallRecordingStatus.Available
        "deleted" -> CallRecordingStatus.Expired
        "failed" -> CallRecordingStatus.Failed
        else -> CallRecordingStatus.Processing
    },
    downloadAvailable = downloadAvailable,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
    failureReason = errorMessage,
)
