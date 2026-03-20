package life.fxs.purr.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CallSessionSummary(
    val callId: String,
    val pairId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val durationSeconds: Long? = null,
    val recordingActive: Boolean = false,
)
