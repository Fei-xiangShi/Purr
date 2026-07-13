package life.fxs.purr.feature.call

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.ui.graphics.vector.ImageVector
import life.fxs.purr.core.model.AudioRoute

internal val AudioRoute.displayLabel: String
    get() = when (this) {
        AudioRoute.Earpiece -> "听筒"
        AudioRoute.Speaker -> "扬声器"
        AudioRoute.Bluetooth -> "蓝牙"
        AudioRoute.WiredHeadset -> "有线耳机"
    }

internal val AudioRoute.displayIcon: ImageVector
    get() = when (this) {
        AudioRoute.Earpiece -> Icons.Rounded.PhoneInTalk
        AudioRoute.Speaker -> Icons.AutoMirrored.Rounded.VolumeUp
        AudioRoute.Bluetooth -> Icons.Rounded.BluetoothAudio
        AudioRoute.WiredHeadset -> Icons.Rounded.Headphones
    }
