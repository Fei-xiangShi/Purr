package life.fxs.purr.realtime

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeRunningUseCase
import life.fxs.purr.domain.account.usecase.ObserveRealtimeStateUseCase
import life.fxs.purr.domain.account.usecase.RefreshActiveCallUseCase
import life.fxs.purr.domain.account.usecase.StartRealtimeUpdatesUseCase
import life.fxs.purr.domain.account.usecase.StopRealtimeUpdatesUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase
import life.fxs.purr.domain.incomingcall.ApplicationVisibility

/**
 * Owns realtime connectivity and recovery for the authenticated application session.
 *
 * In the foreground the socket stays connected and the active call is polled. In the background polling stops and
 * the socket is dropped after [BACKGROUND_SOCKET_GRACE], unless a call is ongoing or an incoming call is pending.
 */
@Singleton
class RealtimeSessionCoordinator @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val startRealtimeUpdates: StartRealtimeUpdatesUseCase,
    private val stopRealtimeUpdates: StopRealtimeUpdatesUseCase,
    private val refreshActiveCall: RefreshActiveCallUseCase,
    private val observeRealtimeState: ObserveRealtimeStateUseCase,
    private val observeRealtimeRunning: ObserveRealtimeRunningUseCase,
    private val observeCallState: ObserveCallStateUseCase,
    private val applicationVisibility: ApplicationVisibility,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        applicationScope.launch {
            observeAuthSession()
                .map { session -> session?.self?.userId }
                .distinctUntilChanged()
                .collectLatest { userId ->
                    stopRealtimeUpdates()
                    if (userId != null) runSession()
                }
        }
    }

    private suspend fun runSession() {
        val ongoing: Flow<Boolean> = observeCallState().map { it?.connectionState?.isOngoing == true }
        val ringing: Flow<Boolean> = observeRealtimeState().map { it.incomingCallCandidate != null }
        combine(
            applicationVisibility.isForeground,
            ongoing,
            ringing,
            observeRealtimeRunning(),
        ) { foreground, isOngoing, isRinging, running ->
            when {
                foreground -> Mode.Foreground
                isOngoing || isRinging -> Mode.BackgroundHeld
                running -> Mode.BackgroundIdleRunning
                else -> Mode.BackgroundIdleStopped
            }
        }
            .distinctUntilChanged()
            .collectLatest { mode ->
                when (mode) {
                    Mode.Foreground -> {
                        startRealtimeUpdates()
                        while (true) {
                            refreshActiveCall()
                            delay(RECOVERY_INTERVAL)
                        }
                    }
                    Mode.BackgroundHeld, Mode.BackgroundIdleStopped -> Unit
                    Mode.BackgroundIdleRunning -> {
                        delay(BACKGROUND_SOCKET_GRACE)
                        stopRealtimeUpdates()
                    }
                }
            }
    }

    private enum class Mode { Foreground, BackgroundHeld, BackgroundIdleRunning, BackgroundIdleStopped }

    internal companion object {
        val BACKGROUND_SOCKET_GRACE = 5.minutes
        val RECOVERY_INTERVAL = 10.seconds
    }
}
