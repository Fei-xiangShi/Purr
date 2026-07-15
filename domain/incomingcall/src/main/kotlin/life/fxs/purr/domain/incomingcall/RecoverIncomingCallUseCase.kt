package life.fxs.purr.domain.incomingcall

import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase

sealed interface IncomingCallRecoveryResult {
    data object NoAuthenticatedSession : IncomingCallRecoveryResult

    data class Refreshed(val result: AppResult<Unit>) : IncomingCallRecoveryResult
}

class RecoverIncomingCallUseCase @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val startRealtimeUpdates: StartRealtimeUpdatesUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
) {
    suspend operator fun invoke(
        timeoutMillis: Long = DEFAULT_AUTH_RESTORE_TIMEOUT_MILLIS,
    ): IncomingCallRecoveryResult {
        val session = withTimeoutOrNull(timeoutMillis) {
            observeAuthSession().filterNotNull().first()
        } ?: return IncomingCallRecoveryResult.NoAuthenticatedSession
        if (session.accessToken.isBlank()) return IncomingCallRecoveryResult.NoAuthenticatedSession

        startRealtimeUpdates()
        return IncomingCallRecoveryResult.Refreshed(refreshActiveCall())
    }

    private companion object {
        const val DEFAULT_AUTH_RESTORE_TIMEOUT_MILLIS = 5_000L
    }
}
