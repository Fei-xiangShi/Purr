package life.fxs.purr.platform.push

import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase

internal sealed interface IncomingCallWakeRecoveryResult {
    data object NoAuthenticatedSession : IncomingCallWakeRecoveryResult

    data class Refreshed(val result: AppResult<Unit>) : IncomingCallWakeRecoveryResult
}

class IncomingCallWakeRecovery @Inject internal constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val startRealtimeUpdates: StartRealtimeUpdatesUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
) {
    internal suspend fun recover(): IncomingCallWakeRecoveryResult {
        val session = withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MILLIS) {
            observeAuthSession().filterNotNull().first()
        } ?: return IncomingCallWakeRecoveryResult.NoAuthenticatedSession
        if (session.accessToken.isBlank()) return IncomingCallWakeRecoveryResult.NoAuthenticatedSession

        startRealtimeUpdates()
        return IncomingCallWakeRecoveryResult.Refreshed(refreshActiveCall())
    }

    private companion object {
        const val AUTH_RESTORE_TIMEOUT_MILLIS = 5_000L
    }
}
