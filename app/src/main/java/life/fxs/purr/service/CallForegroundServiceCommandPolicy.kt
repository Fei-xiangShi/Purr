package life.fxs.purr.service

/**
 * Validates commands carried by notification PendingIntents.
 *
 * Notification actions can outlive the service instance that created them.
 * Requiring an exact, non-blank call id prevents an old action from mutating
 * a later call that happens to use the same service component.
 */
internal object CallForegroundServiceCommandPolicy {
    fun acceptsHangUp(requestedCallId: String?, activeCallId: String?): Boolean =
        !requestedCallId.isNullOrBlank() && requestedCallId == activeCallId

    fun pendingIntentIdentifier(command: String, callId: String): String {
        require(command.isNotBlank()) { "Command must not be blank" }
        require(callId.isNotBlank()) { "Call id must not be blank" }
        return "$command:$callId"
    }
}
