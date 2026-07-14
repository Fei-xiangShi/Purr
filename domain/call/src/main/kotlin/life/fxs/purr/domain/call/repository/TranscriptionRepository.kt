package life.fxs.purr.domain.call.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallTranscript

interface TranscriptionRepository {
    suspend fun loadTranscript(callId: String): AppResult<CallTranscript>
}
