package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallRepository

class ObserveCallStateUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    operator fun invoke() = callRepository.observeCallSession()
}
