package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallRepository

class ObserveCallLifecycleUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    operator fun invoke() = callRepository.observeCallLifecycle()
}
