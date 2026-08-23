package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallRepository

class CancelCallPreparationUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke() = callRepository.cancelCallPreparation()
}
