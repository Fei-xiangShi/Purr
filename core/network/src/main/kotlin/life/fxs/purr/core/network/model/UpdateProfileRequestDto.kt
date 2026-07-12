package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String,
)
