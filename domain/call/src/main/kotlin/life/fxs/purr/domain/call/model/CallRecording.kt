package life.fxs.purr.domain.call.model

data class CallRecording(
    val recordingId: String,
    val callId: String,
    val status: CallRecordingStatus,
    val downloadAvailable: Boolean,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val sizeBytes: Long?,
    val failureReason: String?,
)

enum class CallRecordingStatus {
    Processing,
    Available,
    Expired,
    Failed,
}

data class RecordingDownload(
    val recordingId: String,
    val url: String,
    val expiresAtEpochMillis: Long,
)

data class RecordingPage(
    val recordings: List<CallRecording>,
    val nextCursor: String?,
)
