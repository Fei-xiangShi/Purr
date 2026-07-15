package life.fxs.purr.platform.telecom

import androidx.core.telecom.CallEndpointCompat
import life.fxs.purr.core.model.AudioRoute

internal fun CallEndpointCompat.toAudioRoute(): AudioRoute? = when (type) {
    CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.Earpiece
    CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.Bluetooth
    CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.WiredHeadset
    CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.Speaker
    else -> null
}
