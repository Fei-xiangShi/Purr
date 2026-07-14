package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallDetailRepository

class LoadCallDetailUseCase @Inject constructor(
    private val repository: CallDetailRepository,
) {
    suspend operator fun invoke(callId: String) = repository.loadDetail(callId)
}
