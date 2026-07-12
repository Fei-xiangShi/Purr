package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.RecordingRepository

class LoadRecordingLibraryUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
) {
    suspend operator fun invoke(cursor: String? = null) = recordingRepository.loadRecordingLibrary(cursor)
}
