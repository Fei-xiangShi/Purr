package life.fxs.purr.data.account.realtime

/**
 * Process-session tombstones for incoming calls the user has already accepted or declined.
 *
 * The backend may briefly return the same active call from a recovery poll or an out-of-order
 * realtime snapshot after the local prompt was consumed. Keeping this concern at the realtime
 * boundary prevents both foreground navigation and background notifications from resurrecting it.
 */
internal class ConsumedIncomingCallRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val callIds = LinkedHashSet<String>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun markConsumed(callId: String) {
        if (callId.isBlank()) return
        callIds.remove(callId)
        callIds.add(callId)
        while (callIds.size > capacity) callIds.remove(callIds.first())
    }

    @Synchronized
    fun contains(callId: String): Boolean = callId in callIds

    @Synchronized
    fun clear() = callIds.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 128
    }
}
