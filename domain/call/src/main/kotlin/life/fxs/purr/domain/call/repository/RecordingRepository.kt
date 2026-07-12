package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.model.RecordingPage

interface RecordingRepository {
    suspend fun loadCallRecordings(callId: String): AppResult<List<CallRecording>>
    suspend fun loadRecordingLibrary(cursor: String?): AppResult<RecordingPage>
    suspend fun createRecordingDownload(callId: String, recordingId: String): AppResult<RecordingDownload>
}
