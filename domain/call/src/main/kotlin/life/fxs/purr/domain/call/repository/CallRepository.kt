package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.model.CallRecording
import life.fxs.purr.domain.call.model.RecordingDownload
import life.fxs.purr.domain.call.model.RecordingPage

interface CallRepository {
    fun observeCallSession(): Flow<CallSession?>
    suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession>
    suspend fun connectCall(): AppResult<Unit>
    suspend fun disconnectCall(): AppResult<Unit>
    suspend fun setMuted(muted: Boolean): AppResult<Unit>
    suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit>
    suspend fun loadRecordings(): AppResult<List<CallRecording>>
    suspend fun loadRecordingLibrary(cursor: String?): AppResult<RecordingPage>
    suspend fun createRecordingDownload(callId: String, recordingId: String): AppResult<RecordingDownload>
}
