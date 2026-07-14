package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.TranscriptionRepository

class LoadCallTranscriptUseCase @Inject constructor(
    private val repository: TranscriptionRepository,
) {
    suspend operator fun invoke(callId: String) = repository.loadTranscript(callId)
}
