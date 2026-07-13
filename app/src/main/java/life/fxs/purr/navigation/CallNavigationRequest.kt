package life.fxs.purr.navigation

/** A one-shot request to show a call destination from an external entry point. */
internal data class CallNavigationRequest(
    val pairId: String,
    val requestId: Long,
)

/**
 * Assigns a unique identity to every external call-open intent.
 *
 * The pair id alone is not an event key: Android can deliver the same notification intent
 * repeatedly, and Compose will correctly ignore an unchanged String state. Keeping the sequence
 * in this small navigation-layer type makes that lifecycle explicit and deterministic in tests.
 */
internal class CallNavigationRequestStore {
    private var nextRequestId = 1L

    var pending: CallNavigationRequest? = null
        private set

    fun submit(pairId: String?): CallNavigationRequest? {
        val requestId = nextRequestId
        nextRequestId = if (nextRequestId == Long.MAX_VALUE) 1L else nextRequestId + 1L
        pending = pairId?.takeIf(String::isNotBlank)?.let {
            CallNavigationRequest(pairId = it, requestId = requestId)
        }
        return pending
    }

    fun consume(requestId: Long): Boolean {
        if (pending?.requestId == requestId) {
            pending = null
            return true
        }
        return false
    }
}
