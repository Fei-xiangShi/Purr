package life.fxs.purr.feature.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.model.AudioRoute

@Composable
internal fun AudioRoutePanel(
    routes: List<AudioRoute>,
    activeRoute: AudioRoute,
    enabled: Boolean,
    onRouteSelect: (AudioRoute) -> Unit,
) {
    PurrPanel(title = "音频输出") {
        routes.forEachIndexed { index, route ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                headlineContent = { Text(route.toDisplayLabel()) },
                leadingContent = {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Icon(
                            imageVector = route.toIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp).padding(8.dp),
                        )
                    }
                },
                trailingContent = {
                    RadioButton(selected = route == activeRoute, onClick = null, enabled = enabled)
                },
                modifier = Modifier.clickable(enabled = enabled) { onRouteSelect(route) },
            )
        }
    }
}

internal fun AudioRoute.toDisplayLabel(): String = when (this) {
    AudioRoute.Earpiece -> "听筒"
    AudioRoute.Speaker -> "扬声器"
    AudioRoute.Bluetooth -> "蓝牙"
    AudioRoute.WiredHeadset -> "有线耳机"
}

private fun AudioRoute.toIcon(): ImageVector = when (this) {
    AudioRoute.Earpiece -> Icons.Rounded.PhoneInTalk
    AudioRoute.Speaker -> Icons.AutoMirrored.Rounded.VolumeUp
    AudioRoute.Bluetooth -> Icons.Rounded.BluetoothAudio
    AudioRoute.WiredHeadset -> Icons.Rounded.Headphones
}
