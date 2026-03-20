package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class CallStatusDto(
    val callId: String,
    val pairId: String,
    val state: String,
    val recordingStatus: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
)
