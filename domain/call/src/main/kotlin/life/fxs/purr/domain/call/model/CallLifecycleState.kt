package life.fxs.purr.domain.call.model

/**
 * Observable call ownership state.
 *
 * A local terminal session can be published before server end synchronization completes, so the
 * disconnect identity is intentionally separate from [CallSession.connectionState].
 */
data class CallLifecycleState(
    val session: CallSession? = null,
    val disconnectingCallId: String? = null,
) {
    val resumableSession: CallSession?
        get() = session?.takeIf {
            (disconnectingCallId == null || disconnectingCallId != it.callId) &&
                it.connectionState.isResumable
        }

    val isTerminationInProgress: Boolean
        get() = session?.let { current ->
            current.callId == disconnectingCallId &&
                (current.connectionState == CallConnectionState.Terminating ||
                    current.connectionState.isOngoing)
        } == true

    val isNewCallBlocked: Boolean
        get() = session?.let { current ->
            current.callId == disconnectingCallId && current.connectionState.isOngoing
        } == true
}
