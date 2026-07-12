package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallRepository

class LoadRecordingLibraryUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(cursor: String? = null) = callRepository.loadRecordingLibrary(cursor)
}
