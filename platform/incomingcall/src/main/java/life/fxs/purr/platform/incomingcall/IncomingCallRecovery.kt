package life.fxs.purr.platform.incomingcall

import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase

internal class IncomingCallRecovery @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val startRealtimeUpdates: StartRealtimeUpdatesUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
) {
    suspend fun refreshAfterProcessRecreation() {
        val authenticated = withTimeoutOrNull(AUTH_RECOVERY_TIMEOUT_MILLIS) {
            observeAuthSession().filterNotNull().first()
        } ?: return
        if (authenticated.accessToken.isNotBlank()) {
            startRealtimeUpdates()
            refreshActiveCall()
        }
    }

    private companion object {
        const val AUTH_RECOVERY_TIMEOUT_MILLIS = 4_000L
    }
}
