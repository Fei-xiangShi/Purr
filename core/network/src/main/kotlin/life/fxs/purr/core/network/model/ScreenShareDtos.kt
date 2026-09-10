package life.fxs.purr.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateScreenShareRequestDto(
    val source: String,
)

@Serializable
data class ScreenShareEnvelopeDto(
    val screenShare: ScreenShareDto? = null,
)

@Serializable
data class ScreenShareDto(
    val shareId: String,
    val callId: String,
    val ownerUserId: String,
    val source: String,
    val status: String,
    val mediaPath: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val liveAtEpochMillis: Long? = null,
    val stoppedAtEpochMillis: Long? = null,
    val publishing: ScreenSharePublishingDto? = null,
    val playback: ScreenShareMediaEndpointDto? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ScreenSharePublishingDto(
    val whip: ScreenShareMediaEndpointDto,
    val srt: ScreenShareSrtDto? = null,
)

@Serializable
data class ScreenShareMediaEndpointDto(
    val url: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ScreenShareSrtDto(
    val url: String,
    val streamId: String,
    val passphrase: String,
)
