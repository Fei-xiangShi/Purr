package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.RecordingRepository

class LoadCallRecordingsUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
) {
    suspend operator fun invoke(callId: String) = recordingRepository.loadCallRecordings(callId)
}
