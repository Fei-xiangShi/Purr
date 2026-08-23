package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.repository.CallRepository

class PrepareCallSessionUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(request: CallPreparationRequest) = callRepository.prepareCall(request)
}
