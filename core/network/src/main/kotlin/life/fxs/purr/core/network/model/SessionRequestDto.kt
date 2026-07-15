package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionRequestDto(
    val pairId: String,
    val recordingConsent: Boolean,
    val expectedCallId: String? = null,
)
