package life.fxs.purr.domain.call.model

sealed interface CallConnectionState {
    data object Idle : CallConnectionState
    data object Preparing : CallConnectionState
    data object Connecting : CallConnectionState
    data object Connected : CallConnectionState
    data object Reconnecting : CallConnectionState
    data object Terminating : CallConnectionState
    data object Disconnected : CallConnectionState
    data class Failed(val reason: String? = null) : CallConnectionState

    /** A call operation still owns the local session, including its terminating phase. */
    val isOngoing: Boolean
        get() = when (this) {
            Preparing,
            Connecting,
            Connected,
            Reconnecting,
            Terminating,
            -> true
            Idle,
            Disconnected,
            is Failed,
            -> false
        }

    val isTerminal: Boolean
        get() = this == Disconnected || this is Failed
}
