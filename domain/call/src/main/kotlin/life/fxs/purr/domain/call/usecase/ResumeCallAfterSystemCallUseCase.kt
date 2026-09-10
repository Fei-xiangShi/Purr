package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.domain.call.repository.CallRepository

class ResumeCallAfterSystemCallUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(request: SystemCallInterruptionRequest) =
        callRepository.resumeAfterSystemCall(request)
}
