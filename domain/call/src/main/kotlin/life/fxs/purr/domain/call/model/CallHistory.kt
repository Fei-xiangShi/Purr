package life.fxs.purr.domain.call.model

data class CallHistoryEntry(
    val callId: String,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
)

data class CallHistoryPage(
    val calls: List<CallHistoryEntry>,
    val nextCursor: String?,
)
