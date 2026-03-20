package life.fxs.purr.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PairedPartner(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val isCallable: Boolean = false,
)
