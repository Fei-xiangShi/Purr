package life.fxs.purr.domain.incomingcall

import javax.inject.Inject
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase

/** Joins an exact incoming call and consumes its presentation candidate only after success. */
class PrepareIncomingCallUseCase @Inject constructor(
    private val prepareCallSession: PrepareCallSessionUseCase,
    private val consumeIncomingCall: ConsumeIncomingCallUseCase,
) {
    suspend operator fun invoke(request: CallPreparationRequest.Existing): AppResult<CallSession> {
        if (request.callId.isBlank()) {
            return AppResult.Failure(life.fxs.purr.core.common.AppError.Validation("Call id is required"))
        }
        return when (val result = prepareCallSession(request)) {
            is AppResult.Success -> {
                if (result.value.callId != request.callId) {
                    return AppResult.Failure(
                        AppError.Validation("Prepared call identity does not match requested call"),
                    )
                }
                consumeIncomingCall(request.callId)
                result
            }
            is AppResult.Failure -> result
        }
    }
}
