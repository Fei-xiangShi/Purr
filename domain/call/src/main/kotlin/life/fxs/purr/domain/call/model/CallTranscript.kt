package life.fxs.purr.domain.call.model

data class CallTranscript(
    val status: CallTranscriptStatus,
    val text: String? = null,
)

enum class CallTranscriptStatus {
    Unavailable,
    Processing,
    Available,
    Failed,
}
