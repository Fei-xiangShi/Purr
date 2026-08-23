package life.fxs.purr.overlay

import life.fxs.purr.domain.call.model.CallOverlayStyle
import life.fxs.purr.core.model.CallDirection

data class CallOverlayRenderModel(
    val pairId: String,
    val callId: String = "",
    val direction: CallDirection,
    val style: CallOverlayStyle,
    val localName: String,
    val localAvatarUrl: String?,
    val remoteName: String,
    val remoteAvatarUrl: String?,
    val durationSeconds: Long,
    val localAudioLevel: Float,
    val remoteAudioLevel: Float,
)
