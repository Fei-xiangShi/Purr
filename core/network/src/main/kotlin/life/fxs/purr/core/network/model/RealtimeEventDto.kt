package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class RealtimeEventDto(
    val type: String,
    val partnerOnline: Boolean? = null,
    val callId: String? = null,
    val pairId: String? = null,
    val callerUserId: String? = null,
    val startedAtEpochMillis: Long? = null,
)
