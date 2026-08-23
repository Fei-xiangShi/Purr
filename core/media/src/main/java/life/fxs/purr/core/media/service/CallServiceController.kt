package life.fxs.purr.core.media.service

import kotlinx.coroutines.flow.StateFlow
import life.fxs.purr.core.model.CallDirection

interface CallServiceController {
    /** The state published by the Android service after it has actually entered foreground mode. */
    val foregroundState: StateFlow<ForegroundCallServiceState>

    suspend fun startForegroundCall(callId: String, pairId: String, direction: CallDirection)
    suspend fun stopForegroundCall(callId: String)
}

data class ForegroundCallServiceState(
    val activeCallId: String? = null,
    val direction: CallDirection? = null,
) {
    val isActive: Boolean
        get() = activeCallId != null
}
