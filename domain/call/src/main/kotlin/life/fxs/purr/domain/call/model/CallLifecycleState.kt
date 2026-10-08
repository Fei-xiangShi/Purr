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

    /** Local teardown of the current call is still running; a new call must wait for it. */
    val isTerminationInProgress: Boolean
        get() = session?.let { current ->
            current.callId == disconnectingCallId && current.connectionState.isOngoing
        } == true
}
