package life.fxs.purr.telecom

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.telecom.SystemCallController
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase

@Singleton
class SystemCallEventCoordinator @Inject constructor(
    private val systemCallController: SystemCallController,
    private val disconnectCall: DisconnectCallUseCase,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        systemCallController.events
            .onEach { event ->
                when (event) {
                    is SystemCallEvent.DisconnectRequested -> handleDisconnectRequest(event.callId)
                }
            }
            .launchIn(applicationScope)
    }

    private suspend fun handleDisconnectRequest(callId: String) {
        try {
            when (val result = disconnectCall(callId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> Log.e(
                    TAG,
                    "Unable to complete Telecom disconnect for $callId: ${result.error}",
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Telecom disconnect handling failed for $callId", error)
        }
    }

    private companion object {
        const val TAG = "SystemCallEvents"
    }
}
