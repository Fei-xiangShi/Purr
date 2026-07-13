package life.fxs.purr.core.media.service

import kotlinx.coroutines.flow.StateFlow

interface CallServiceController {
    /** The state published by the Android service after it has actually entered foreground mode. */
    val foregroundState: StateFlow<ForegroundCallServiceState>

    suspend fun startForegroundCall(callId: String, pairId: String)
    suspend fun stopForegroundCall(expectedCallId: String)
}

data class ForegroundCallServiceState(
    val activeCallId: String? = null,
) {
    val isActive: Boolean
        get() = activeCallId != null
}
