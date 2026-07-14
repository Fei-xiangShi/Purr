package life.fxs.purr.data.call.repository

import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.call.model.CallTranscript
import life.fxs.purr.domain.call.model.CallTranscriptStatus
import life.fxs.purr.domain.call.repository.TranscriptionRepository

/** Replace this adapter when a transcription provider is introduced. */
@Singleton
class UnavailableTranscriptionRepository @Inject constructor() : TranscriptionRepository {
    override suspend fun loadTranscript(callId: String): AppResult<CallTranscript> =
        AppResult.Success(CallTranscript(CallTranscriptStatus.Unavailable))
}
