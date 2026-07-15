package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class PushDeviceRegistrationDto(
    val provider: String,
    val token: String,
)
