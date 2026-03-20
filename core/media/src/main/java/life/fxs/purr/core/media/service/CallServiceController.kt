package life.fxs.purr.core.media.service

import kotlinx.coroutines.flow.StateFlow

interface CallServiceController {
    val isCallForeground: StateFlow<Boolean>

    suspend fun startForegroundCall(callId: String)
    suspend fun stopForegroundCall()
}
