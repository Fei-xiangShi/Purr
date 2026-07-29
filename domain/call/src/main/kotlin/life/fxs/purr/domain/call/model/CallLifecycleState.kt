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
            disconnectingCallId == null && it.connectionState.isResumable
        }

    val isTerminationInProgress: Boolean
        get() = disconnectingCallId != null ||
            session?.connectionState == CallConnectionState.Terminating

    val isNewCallBlocked: Boolean
        get() = disconnectingCallId != null || session?.connectionState?.isOngoing == true
}
