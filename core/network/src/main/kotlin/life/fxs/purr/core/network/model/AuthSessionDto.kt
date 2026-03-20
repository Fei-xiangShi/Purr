package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable
import life.fxs.purr.core.model.SelfProfile

@Serializable
data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    val self: SelfProfile,
)
