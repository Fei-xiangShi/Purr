package life.fxs.purr.feature.call

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import life.fxs.purr.core.media.screenshare.ScreenShareQuality
import life.fxs.purr.core.media.screenshare.browserWatchLink
import life.fxs.purr.domain.call.model.LocalScreenShareState
import life.fxs.purr.domain.call.model.ScreenSharePublishing
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallToolsSheet(
    state: CallScreenShareState,
    canStartScreenShare: Boolean,
    onDismiss: () -> Unit,
    onScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val active = state.session?.status in setOf(
        ScreenShareStatus.Authorized,
        ScreenShareStatus.Live,
        ScreenShareStatus.Stopping,
    )
    val canStop = active && state.isOwnedByCurrentUser
    val stopping = state.session?.status == ScreenShareStatus.Stopping
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "通话工具",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(if (canStop) "停止投屏" else "投屏与推流") },
            supportingContent = {
                Text(when {
                    stopping -> "正在停止投屏"
                    state.isCreating -> "正在准备投屏"
                    canStop -> "停止当前手机投屏或 OBS 推流"
                    active -> "对方正在投屏"
                    !canStartScreenShare -> "双方接通后可共享画面"
                    else -> "选择手机投屏或 OBS 推流"
                })
            },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null) },
            trailingContent = {
                Button(
                    onClick = if (canStop) onStopScreenShare else onScreenShare,
                    enabled = !state.isCreating && !stopping &&
                        (canStop || (canStartScreenShare && !active)),
                ) { Text(if (canStop) "停止" else "选择") }
            },
        )
        ListItem(
            headlineContent = { Text("网页观看直播") },
            supportingContent = { Text("复制短期观看链接后请尽快打开；已连接可继续观看，直播停止后失效。浏览器需支持 HEVC WebRTC。") },
            trailingContent = {
                Button(
                    enabled = active && !stopping && state.session?.playback != null,
                    onClick = {
                        val endpoint = state.session?.playback
                        val link = endpoint?.let { browserWatchLink(it.url, it.bearerToken, it.expiresAtEpochMillis) }
                        if (endpoint == null || link == null || System.currentTimeMillis() >= endpoint.expiresAtEpochMillis) {
                            Toast.makeText(context, "观看凭证正在刷新，请稍后重试", Toast.LENGTH_SHORT).show()
                        } else {
                            clipboard.setText(AnnotatedString(link))
                            val expiry = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(endpoint.expiresAtEpochMillis))
                            Toast.makeText(context, "已复制网页观看链接，请在 $expiry 前打开", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("复制链接") }
            },
        )
        ListItem(
            headlineContent = { Text("网络状况检测") },
            supportingContent = { Text("查看通话与直播的实际帧率、码率、丢包和缓冲") },
            leadingContent = { Icon(Icons.Rounded.NetworkCheck, contentDescription = null) },
            trailingContent = { Button(onClick = onDiagnostics) { Text("查看") } },
        )
        Spacer(Modifier.height(28.dp))
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
    controlsVisible: Boolean,
    onFullscreen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
) {
    val showRenderer = state.remoteState in setOf(
        RemoteScreenShareUiState.Connecting,
        RemoteScreenShareUiState.Buffering,
        RemoteScreenShareUiState.Live,
    )
    val panelModifier = if (fullscreen) modifier.fillMaxSize() else modifier
        .aspectRatio(state.remoteAspectRatio)
        .clip(RoundedCornerShape(18.dp))
    Box(modifier = panelModifier.background(Color.Black), contentAlignment = Alignment.Center) {
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
                modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(20.dp),
            ) {
                if (state.remoteState in setOf(
                        RemoteScreenShareUiState.Preparing,
                        RemoteScreenShareUiState.Connecting,
                        RemoteScreenShareUiState.Buffering,
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp,
                    )
                }
                Text(state.remoteStatusLabel(), color = Color.White)
                if (state.remoteState == RemoteScreenShareUiState.Buffering) {
                    Text("正在等待连续画面，恢复后自动播放", color = Color.White,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.remoteState == RemoteScreenShareUiState.Failed) {
                    Text(
                        state.errorMessage ?: "节目流播放失败，语音通话仍然可用",
                        color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.session?.status == life.fxs.purr.domain.call.model.ScreenShareStatus.Live) {
                        TextButton(onClick = onRetry) { Text("重试播放") }
                    }
                }
            }
        }
        if (controlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .then(if (fullscreen) Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ) else Modifier)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.remoteStatusLabel(), color = Color.White,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onFullscreen) {
                    Icon(
                        if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ScreenShareSourceSheet(
    quality: ScreenShareQuality,
    onSelectQuality: (ScreenShareQuality) -> Unit,
    onDismiss: () -> Unit,
    onMobile: () -> Unit,
    onObs: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = "选择投屏来源",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            PublishQualitySelector(quality, onSelectQuality)
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
                supportingContent = { Text("H.265 硬件编码；使用 WHIP 或加密 SRT") },
                leadingContent = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                trailingContent = {
                    Button(onClick = onObs) { Text("选择") }
                },
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PublishQualitySelector(
    quality: ScreenShareQuality,
    onSelectQuality: (ScreenShareQuality) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Box {
            TextButton(onClick = { expanded = true }) { Text("推流画质：${quality.label} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ScreenShareQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${option.label} · ${option.bitrate / 1_000_000} Mbps") },
                        onClick = { onSelectQuality(option); expanded = false },
                    )
                }
            }
        }
        Text(
            "H.265 硬件编码，观看设备须支持 H.265 硬件解码。手机投屏保持屏幕比例，不超过屏幕原始分辨率。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun ObsSetupDialog(
    publishing: ScreenSharePublishing,
    quality: ScreenShareQuality,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("OBS 屏幕直播设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("OBS 的 WHIP 模式可选择 H.265/HEVC 时，填写以下 WHIP 地址和 Bearer Token；否则使用下方 SRT 输出方式。")
                Text(
                    "视频 → 输出分辨率 ${quality.shortSide * 16 / 9}×${quality.shortSide}，" +
                        "${quality.framesPerSecond} fps；输出 → H.265/HEVC Main（8 位），CBR ${quality.bitrate / 1_000} Kbps，关键帧间隔 1 秒。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "优先使用硬件编码器，选择低延迟模式；B 帧设为 0，关闭前瞻分析（Look-ahead）。选项名称因编码器而异。",
                    style = MaterialTheme.typography.bodySmall,
                )
                CopyableCredential("WHIP URL", publishing.whip.url)
                CopyableCredential("Bearer Token", publishing.whip.bearerToken)
                publishing.srt?.let { srt ->
                    Text("SRT 输出（已加密）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "输出 → 高级 → 录像 → 自定义输出（FFmpeg）→ 输出到 URL；容器选 MPEG-TS，" +
                            "视频选 HEVC 硬件编码器，音频选 Opus（48 kHz、双声道、128 Kbps）。" +
                            "粘贴完整 SRT URL 后点击开始录像发送；URL 已含 Stream ID 和加密口令。",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
    val context = LocalContext.current
    val copy = {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
    }
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "复制 $label", onClick = copy),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = copy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制 $label")
            }
        }
        Text(
            text = value, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun CallScreenShareState.remoteStatusLabel(): String = when (remoteState) {
    RemoteScreenShareUiState.None -> "暂无屏幕共享"
    RemoteScreenShareUiState.Preparing -> "对方正在准备投屏"
    RemoteScreenShareUiState.Connecting -> "正在连接共享画面"
    RemoteScreenShareUiState.Buffering -> "直播缓冲中"
    RemoteScreenShareUiState.Live -> "直播中"
    RemoteScreenShareUiState.Failed -> "节目流播放失败"
    RemoteScreenShareUiState.Stopped -> "对方已停止投屏"
}

internal val CallScreenShareState.shouldShowRemotePanel: Boolean
    get() = !isOwnedByCurrentUser && session != null &&
        remoteState != RemoteScreenShareUiState.None &&
        remoteState != RemoteScreenShareUiState.Stopped
