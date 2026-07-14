package life.fxs.purr.realtime

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase

/** Owns realtime connectivity and recovery for the authenticated application session. */
@Singleton
class RealtimeSessionCoordinator @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val startRealtimeUpdates: StartRealtimeUpdatesUseCase,
    private val stopRealtimeUpdates: StopRealtimeUpdatesUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private var recoveryJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        observeAuthSession()
            .map { session -> session?.self?.userId }
            .distinctUntilChanged()
            .onEach(::switchAuthenticatedUser)
            .launchIn(applicationScope)
    }

    private fun switchAuthenticatedUser(userId: String?) {
        recoveryJob?.cancel()
        recoveryJob = null
        stopRealtimeUpdates()
        if (userId == null) return

        startRealtimeUpdates()
        recoveryJob = applicationScope.launch {
            while (isActive) {
                refreshActiveCall()
                delay(RECOVERY_INTERVAL_MILLIS)
            }
        }
    }

    private companion object {
        const val RECOVERY_INTERVAL_MILLIS = 10_000L
    }
}
