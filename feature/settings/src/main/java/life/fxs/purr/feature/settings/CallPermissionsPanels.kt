package life.fxs.purr.feature.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.domain.call.model.CallOverlayStyle

@Composable
internal fun IncomingCallPermissionsPanel(
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    fullScreenIntentGranted: Boolean,
    onRequestFullScreenIntent: () -> Unit,
) {
    PurrPanel(
        title = "来电提醒",
        subtitle = "控制锁屏和后台来电的系统展示能力",
    ) {
        SystemPermissionPreference(
            icon = Icons.Rounded.NotificationsActive,
            title = "来电通知",
            description = if (notificationsEnabled) {
                "已允许显示系统来电通知"
            } else {
                "需要开启通知后才能接收后台来电提醒"
            },
            granted = notificationsEnabled,
            onClick = onOpenNotificationSettings,
        )
        PermissionDivider()
        SystemPermissionPreference(
            icon = Icons.Rounded.Call,
            title = "全屏来电",
            description = if (fullScreenIntentGranted) {
                "已允许在锁屏时显示来电页面"
            } else {
                "未授权时将降级为高优先级通知"
            },
            granted = fullScreenIntentGranted,
            onClick = onRequestFullScreenIntent,
        )
    }
}

@Composable
internal fun CallOverlayPermissionsPanel(
    selectedStyle: CallOverlayStyle,
    onStyleSelected: (CallOverlayStyle) -> Unit,
    overlayPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
) {
    PurrPanel(
        title = "通话悬浮窗",
        subtitle = "通话页之外持续显示，可随时点按返回通话",
    ) {
        Text(
            text = "显示样式",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OverlayStylePreference(
            title = "方形计时",
            description = "通话图标与通话时长",
            selected = selectedStyle == CallOverlayStyle.CompactSquare,
            onClick = { onStyleSelected(CallOverlayStyle.CompactSquare) },
        )
        OverlayStylePreference(
            title = "顶部说话者",
            description = "根据实时音量突出双方姓名",
            selected = selectedStyle == CallOverlayStyle.SpeakerNames,
            onClick = { onStyleSelected(CallOverlayStyle.SpeakerNames) },
        )
        PermissionDivider()
        SystemPermissionPreference(
            icon = Icons.Rounded.PictureInPictureAlt,
            title = "显示在其他应用上层",
            description = if (overlayPermissionGranted) {
                "已允许，离开通话页面后显示悬浮窗"
            } else {
                "需要授权后才能显示通话悬浮窗"
            },
            granted = overlayPermissionGranted,
            onClick = onRequestOverlayPermission,
        )
        PermissionDivider()
        SystemPermissionPreference(
            icon = Icons.Rounded.BatterySaver,
            title = "忽略电池优化",
            description = if (batteryOptimizationIgnored) {
                "已允许，系统不会限制后台通话服务"
            } else {
                "避免锁屏或后台运行时中断通话"
            },
            granted = batteryOptimizationIgnored,
            onClick = onRequestIgnoreBatteryOptimizations,
        )
    }
}

@Composable
private fun OverlayStylePreference(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        },
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = { RadioButton(selected = selected, onClick = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SystemPermissionPreference(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (granted) {
                TextButton(onClick = onClick) { Text("管理") }
            } else {
                FilledTonalButton(onClick = onClick) { Text("授权") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun PermissionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}
