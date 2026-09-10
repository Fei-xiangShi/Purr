package life.fxs.purr.screenshare

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.call.model.CallConnectionState
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.repository.ScreenShareRepository

@Singleton
class ScreenShareCallLifecycleCoordinator @Inject constructor(
    private val callRepository: CallRepository,
    private val screenShareRepository: ScreenShareRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private var trackedCallId: String? = null
    private var cleanedCallId: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            callRepository.observeCallSession().collect { session ->
                if (session != null && session.connectionState.isResumable) {
                    trackedCallId = session.callId
                    if (cleanedCallId != session.callId) cleanedCallId = null
                    return@collect
                }
                val callId = session?.callId ?: trackedCallId ?: return@collect
                val shouldClean = session == null ||
                    session.connectionState == CallConnectionState.Terminating ||
                    session.connectionState.isTerminal
                if (!shouldClean || cleanedCallId == callId) return@collect
                cleanedCallId = callId
                screenShareRepository.stop(callId)
            }
        }
    }
}
