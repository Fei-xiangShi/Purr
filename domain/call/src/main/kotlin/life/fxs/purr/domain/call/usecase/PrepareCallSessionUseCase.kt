package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.repository.CallRepository

class PrepareCallSessionUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(params: PrepareCallParams) = callRepository.prepareCall(params)
}
