package life.fxs.purr.core.media.telecom

import kotlinx.coroutines.flow.Flow
import life.fxs.purr.core.model.CallDirection

data class SystemCallDescriptor(
    val callId: String,
    val pairId: String,
    val remoteDisplayName: String,
    val direction: CallDirection,
)

sealed interface SystemCallEvent {
    data class DisconnectRequested(val callId: String) : SystemCallEvent
}

interface SystemCallController {
    val events: Flow<SystemCallEvent>

    suspend fun startCall(descriptor: SystemCallDescriptor)

    suspend fun activateCall(callId: String)

    suspend fun disconnectCall(callId: String)
}
