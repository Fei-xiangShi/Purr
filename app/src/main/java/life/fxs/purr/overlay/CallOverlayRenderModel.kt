package life.fxs.purr.overlay

import life.fxs.purr.domain.call.model.CallOverlayStyle

data class CallOverlayRenderModel(
    val pairId: String,
    val style: CallOverlayStyle,
    val localName: String,
    val localAvatarUrl: String?,
    val remoteName: String,
    val remoteAvatarUrl: String?,
    val durationSeconds: Long,
    val localAudioLevel: Float,
    val remoteAudioLevel: Float,
)
