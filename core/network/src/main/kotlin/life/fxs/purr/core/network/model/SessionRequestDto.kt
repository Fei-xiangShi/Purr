package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionRequestDto(
    val pairId: String,
    val resumeCallId: String? = null,
)
