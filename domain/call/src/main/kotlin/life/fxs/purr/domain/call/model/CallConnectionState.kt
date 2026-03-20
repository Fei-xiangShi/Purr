package life.fxs.purr.domain.call.model

sealed interface CallConnectionState {
    data object Idle : CallConnectionState
    data object Preparing : CallConnectionState
    data object Connecting : CallConnectionState
    data object Connected : CallConnectionState
    data object Reconnecting : CallConnectionState
    data object Disconnected : CallConnectionState
    data class Failed(val reason: String? = null) : CallConnectionState
}
