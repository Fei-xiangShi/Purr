package life.fxs.purr.domain.incomingcall

import javax.inject.Inject
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase

/** Joins an exact incoming call and consumes its presentation candidate only after success. */
class PrepareIncomingCallUseCase @Inject constructor(
    private val prepareCallSession: PrepareCallSessionUseCase,
    private val consumeIncomingCall: ConsumeIncomingCallUseCase,
) {
    suspend operator fun invoke(params: PrepareCallParams): AppResult<CallSession> {
        val expectedCallId = params.expectedCallId
            ?: return AppResult.Failure(AppError.Validation("Incoming call identity is required"))
        return when (val result = prepareCallSession(params)) {
            is AppResult.Success -> {
                if (result.value.callId != expectedCallId) {
                    return AppResult.Failure(
                        AppError.Unexpected(
                            IllegalStateException("Prepared call does not match the accepted incoming call"),
                        ),
                    )
                }
                consumeIncomingCall(expectedCallId)
                result
            }
            is AppResult.Failure -> result
        }
    }
}
