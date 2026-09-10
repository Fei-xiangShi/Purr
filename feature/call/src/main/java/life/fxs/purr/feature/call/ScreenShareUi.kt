package life.fxs.purr.feature.call

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import life.fxs.purr.domain.call.model.LocalScreenShareState
import life.fxs.purr.domain.call.model.ScreenSharePublishing
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareStatus

@Composable
internal fun ScreenShareButton(
    state: CallScreenShareState,
    canStart: Boolean,
    onOpenPicker: () -> Unit,
    onStop: () -> Unit,
) {
    val active = state.session?.status in setOf(
        ScreenShareStatus.Authorized,
        ScreenShareStatus.Live,
        ScreenShareStatus.Stopping,
    )
    val canStop = active && state.isOwnedByCurrentUser
    FilledIconButton(
        onClick = if (canStop) onStop else onOpenPicker,
        enabled = canStop || (canStart && !active),
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (canStop) Color(0xFF5F4630) else Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.07f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Icon(
            imageVector = if (canStop) {
                Icons.AutoMirrored.Rounded.StopScreenShare
            } else {
                Icons.AutoMirrored.Rounded.ScreenShare
            },
            contentDescription = if (canStop) "停止投屏" else "开始投屏",
            modifier = Modifier.size(27.dp),
        )
    }
}

@Composable
internal fun LocalScreenShareStatus(state: CallScreenShareState) {
    if (!state.isOwnedByCurrentUser || state.session == null) return
    val sourceLabel = when (state.session.source) {
        ScreenShareSource.Mobile -> "手机屏幕与系统声音"
        ScreenShareSource.Obs -> "OBS 节目流"
    }
    val statusLabel = when (val local = state.localState) {
        LocalScreenShareState.Idle -> "已停止"
        is LocalScreenShareState.RequestingPermission -> "等待系统授权"
        is LocalScreenShareState.Connecting -> "正在连接"
        is LocalScreenShareState.Live -> "直播中"
        is LocalScreenShareState.Stopping -> "正在停止"
        is LocalScreenShareState.Failed -> local.message
    }
    Text(
        text = "$sourceLabel · $statusLabel",
        style = MaterialTheme.typography.labelLarge,
        color = if (state.localState is LocalScreenShareState.Failed) {
            Color(0xFFFFB4AB)
        } else {
            Color.White.copy(alpha = 0.82f)
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RemoteScreenSharePanel(
    state: CallScreenShareState,
    createRenderer: (Context) -> View,
    releaseRenderer: (View) -> Unit,
) {
    val showRenderer = state.remoteState in setOf(
        RemoteScreenShareUiState.Connecting,
        RemoteScreenShareUiState.Buffering,
        RemoteScreenShareUiState.Live,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (showRenderer) {
                AndroidView(
                    factory = createRenderer,
                    modifier = Modifier.fillMaxSize(),
                    onRelease = releaseRenderer,
                )
            }
            if (state.remoteState != RemoteScreenShareUiState.Live) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(20.dp),
                ) {
                    if (state.remoteState != RemoteScreenShareUiState.Failed) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = state.remoteStatusLabel(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.remoteState == RemoteScreenShareUiState.Failed) {
                        Text(
                            text = state.errorMessage ?: "节目流播放失败，语音通话仍然可用",
                            color = Color(0xFFFFB4AB),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "对方正在共享屏幕 · 节目声音与语音同时播放",
            color = Color.White.copy(alpha = 0.74f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ScreenShareSourceSheet(
    onDismiss: () -> Unit,
    onMobile: () -> Unit,
    onObs: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "选择投屏来源",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text("手机投屏") },
            supportingContent = { Text("共享屏幕画面和系统内部声音；麦克风继续由语音通话使用") },
            leadingContent = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                Button(onClick = onMobile) { Text("选择") }
            },
        )
        ListItem(
            headlineContent = { Text("OBS 推流") },
            supportingContent = { Text("优先使用 WHIP；网络不稳定时可改用加密 SRT") },
            leadingContent = { Icon(Icons.Rounded.Computer, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                Button(onClick = onObs) { Text("选择") }
            },
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
internal fun ObsSetupDialog(
    publishing: ScreenSharePublishing,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("OBS 屏幕直播设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("OBS → 设置 → 推流 → 服务选择 WHIP，然后填写以下地址和 Bearer Token。")
                CopyableCredential("WHIP URL", publishing.whip.url)
                CopyableCredential("Bearer Token", publishing.whip.bearerToken)
                publishing.srt?.let { srt ->
                    Text("SRT 备用（已加密）", style = MaterialTheme.typography.titleSmall)
                    CopyableCredential("SRT URL", srt.url)
                    CopyableCredential("Stream ID", srt.streamId)
                    CopyableCredential("Passphrase", srt.passphrase)
                }
                Text(
                    "这些凭证只属于当前通话，请勿转发；停止投屏或通话结束后会立即失效。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun CopyableCredential(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制 $label")
            }
        }
    }
}

private fun CallScreenShareState.remoteStatusLabel(): String = when (remoteState) {
    RemoteScreenShareUiState.None -> "暂无屏幕共享"
    RemoteScreenShareUiState.Preparing -> "对方正在准备投屏"
    RemoteScreenShareUiState.Connecting -> "正在建立低延迟 WHEP 连接"
    RemoteScreenShareUiState.Buffering -> "节目流缓冲中"
    RemoteScreenShareUiState.Live -> "直播中"
    RemoteScreenShareUiState.Failed -> "节目流播放失败"
    RemoteScreenShareUiState.Stopped -> "对方已停止投屏"
}

internal val CallScreenShareState.shouldShowRemotePanel: Boolean
    get() = !isOwnedByCurrentUser && session != null &&
        remoteState != RemoteScreenShareUiState.None &&
        remoteState != RemoteScreenShareUiState.Stopped
