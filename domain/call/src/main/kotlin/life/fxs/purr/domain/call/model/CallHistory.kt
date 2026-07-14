package life.fxs.purr.domain.call.model

data class CallHistoryEntry(
    val callId: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val requestedAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: String,
)

enum class CallDirection { Incoming, Outgoing }

enum class CallOutcome { Completed, Missed, Cancelled }

data class CallHistoryPage(
    val calls: List<CallHistoryEntry>,
    val nextCursor: String?,
)
