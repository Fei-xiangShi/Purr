package life.fxs.purr.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SelfProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
)
