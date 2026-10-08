package life.fxs.purr.domain.incomingcall

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import life.fxs.purr.core.common.AppError
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.usecase.PrepareCallSessionUseCase

/** Revalidates an exact incoming/resumed call before acquiring a new media session. */
class PrepareIncomingCallUseCase @Inject constructor(
    private val prepareCallSession: PrepareCallSessionUseCase,
    private val consumeIncomingCall: ConsumeIncomingCallUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
    private val observeRealtimeState: ObserveRealtimeStateUseCase,
) {
    suspend operator fun invoke(request: CallPreparationRequest.Existing): AppResult<CallSession> {
        if (request.callId.isBlank()) {
            return AppResult.Failure(life.fxs.purr.core.common.AppError.Validation("Call id is required"))
        }
        when (val refresh = refreshActiveCall()) {
            is AppResult.Failure -> return refresh
            is AppResult.Success -> if (!isCurrentCall(request)) return expiredCall(request.callId)
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
            is AppResult.Failure -> {
                // The room may end between preflight and session creation. Refresh
                // the home/prompt source as well, so retry cannot reuse an expired ID.
                if (result.error !is AppError.Network && result.error !is AppError.Unauthorized &&
                    refreshActiveCall() is AppResult.Success &&
                    !isCurrentCall(request)
                ) expiredCall(request.callId) else result
            }
        }
    }

    private suspend fun isCurrentCall(request: CallPreparationRequest.Existing): Boolean {
        val active = observeRealtimeState().first().activeCall
        return active?.callId == request.callId && active.pairId == request.pairId
    }

    private fun expiredCall(callId: String): AppResult.Failure {
        consumeIncomingCall(callId)
        return AppResult.Failure(AppError.Validation("这通电话已结束，请返回首页重新拨打"))
    }
}
