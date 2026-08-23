package life.fxs.purr.service

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.model.CallDirection

@Singleton
class AndroidCallServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateStore: CallForegroundServiceStateStore,
) : CallServiceController {
    override val foregroundState: StateFlow<ForegroundCallServiceState> = stateStore.state

    override suspend fun startForegroundCall(callId: String, pairId: String, direction: CallDirection) {
        ContextCompat.startForegroundService(context, CallForegroundService.intent(context, callId, pairId, direction))
        withTimeout(SERVICE_LIFECYCLE_TIMEOUT_MILLIS) {
            stateStore.state
                .filter { it.activeCallId == callId }
                .first()
        }
    }

    override suspend fun stopForegroundCall(callId: String) {
        if (!stateStore.isActiveFor(callId)) return
        context.stopService(
            android.content.Intent(context, CallForegroundService::class.java)
                .putExtra(CallForegroundService.EXTRA_CALL_ID, callId),
        )
        withTimeout(SERVICE_LIFECYCLE_TIMEOUT_MILLIS) {
            stateStore.state.filter { it.activeCallId != callId }.first()
        }
    }

    private companion object {
        const val SERVICE_LIFECYCLE_TIMEOUT_MILLIS = 5_000L
    }
}
