package life.fxs.purr.feature.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.state.rememberParticipantTrackReferences
import io.livekit.android.compose.ui.audio.AudioBarVisualizer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrPrimaryButton
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrSecondaryButton
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.RecordingState
import life.fxs.purr.domain.call.model.CallRecordingStatus

@Composable
fun CallScreenRoute(
    pairId: String,
    onBack: () -> Unit,
    roomStateProvider: CallRoomStateProvider,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room by roomStateProvider.room.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recordingPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
        }
    }
    DisposableEffect(recordingPlayer, viewModel) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    recordingPlayer.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { recordingId ->
                        viewModel.onIntent(CallIntent.RecordingPlaybackStarted(recordingId))
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recordingPlayer.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { recordingId ->
                        viewModel.onIntent(CallIntent.RecordingPlaybackStopped(recordingId))
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val recordingId = recordingPlayer.currentMediaItem?.mediaId.orEmpty()
                viewModel.onIntent(CallIntent.RecordingPlaybackFailed(recordingId, error.message))
            }
        }
        recordingPlayer.addListener(listener)
        onDispose {
            recordingPlayer.removeListener(listener)
            recordingPlayer.release()
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val activity = context.findActivity()
        val permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        viewModel.onIntent(
            CallIntent.MicrophonePermissionResult(
                granted = granted,
                permanentlyDenied = permanentlyDenied,
            ),
        )
    }
    val requestMicrophonePermission = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        requestMicrophonePermission()
    }

    LaunchedEffect(pairId) {
        if (pairId.isNotBlank()) {
            viewModel.onIntent(CallIntent.ConnectCall(pairId = pairId))
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CallEffect.OpenAppSettings -> context.openAppSettings()
                CallEffect.RequestMicrophonePermission -> {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        requestMicrophonePermission()
                    }
                }
                is CallEffect.ShowMessage -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is CallEffect.PlayRecording -> {
                    recordingPlayer.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(effect.recordingId)
                            .setUri(effect.url)
                            .build(),
                    )
                    recordingPlayer.prepare()
                    recordingPlayer.play()
                }
                CallEffect.PauseRecording -> recordingPlayer.pause()
            }
        }
    }

    if (state.recordingConsentRequired) {
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(CallIntent.RecordingConsentResult(granted = false)) },
            title = { Text("录音同意") },
            text = {
                Text("本次通话将在双方接通后自动录音。录音仅供配对双方访问，继续前需要你的明确同意。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onIntent(CallIntent.RecordingConsentResult(granted = true)) }) {
                    Text("同意并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(CallIntent.RecordingConsentResult(granted = false)) }) {
                    Text("不同意")
                }
            },
        )
    }

    CallScreen(
        state = state,
        room = room,
        onConnect = { viewModel.onIntent(CallIntent.ConnectCall(pairId = pairId)) },
        onMuteToggle = { viewModel.onIntent(CallIntent.MuteToggle) },
        onRouteSelect = { route -> viewModel.onIntent(CallIntent.RouteSelect(route)) },
        onEndCall = { viewModel.onIntent(CallIntent.EndCall) },
        onRefreshRecordings = { viewModel.onIntent(CallIntent.RefreshRecordings) },
        onPlayRecording = { recordingId -> viewModel.onIntent(CallIntent.PlayRecording(recordingId)) },
        onBack = onBack,
    )
}

@Composable
fun CallScreen(
    state: CallState,
    room: Room?,
    onConnect: () -> Unit,
    onMuteToggle: () -> Unit,
    onRouteSelect: (AudioRoute) -> Unit,
    onEndCall: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onPlayRecording: (String) -> Unit,
    onBack: () -> Unit,
) {
    val session = state.session
    val remoteName = session?.participantIdentity?.remote ?: "通话对象"
    val canConnect = !state.isLoading && (state.screenState == CallScreenState.Idle || state.screenState == CallScreenState.Ended)
    val canManageActiveCall = !state.isLoading && state.screenState == CallScreenState.Active
    val canEndCall = !state.isLoading && state.screenState != CallScreenState.Idle && state.screenState != CallScreenState.Ended

    PurrScreen {
        PurrSectionTitle(
            eyebrow = "通话",
            title = screenTitle(state.screenState),
            subtitle = "",
            modifier = Modifier.fillMaxWidth(),
        )

        PurrPanel(
            title = remoteName,
            subtitle = session?.roomName ?: "",
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
        ) {
            PurrStatusChip(
                label = "状态",
                detail = state.screenState.toDisplayLabel(),
                accentColor = state.screenState.accentColor(),
            )
            if (state.isLoading) {
                PurrStatusChip(
                    label = "处理中",
                    detail = "",
                    accentColor = MaterialTheme.colorScheme.primary,
                )
            }
            PurrStatusChip(
                label = "麦克风",
                detail = state.localAudioState.toDisplayLabel(),
                accentColor = if (state.localAudioState is LocalAudioState.Muted) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            PurrStatusChip(
                label = "录音",
                detail = state.recordingState.toDisplayLabel(),
                accentColor = if (state.recordingState is RecordingState.Recording) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            PurrStatusChip(
                label = "前台服务",
                detail = if (state.isForegroundServiceActive) "运行中" else "未运行",
                accentColor = if (state.isForegroundServiceActive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
        }

        PurrPanel(title = "操作") {
            PurrPrimaryButton(
                text = if (state.screenState == CallScreenState.Ended) "重新连接" else "开始通话",
                onClick = onConnect,
                enabled = canConnect,
            )
            PurrSecondaryButton(
                text = if (state.localAudioState is LocalAudioState.Muted) "取消静音" else "静音",
                onClick = onMuteToggle,
                enabled = canManageActiveCall,
            )
            PurrPrimaryButton(
                text = "结束通话",
                onClick = onEndCall,
                enabled = canEndCall,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            PurrSecondaryButton(
                text = "返回",
                onClick = onBack,
            )
        }

        PurrPanel(title = "音频输出") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.availableRoutes.forEach { route ->
                    PurrSecondaryButton(
                        text = route.toButtonLabel(selected = route == state.activeRoute),
                        onClick = { onRouteSelect(route) },
                        enabled = canManageActiveCall,
                    )
                }
            }
        }

        if (session != null) {
            PurrPanel(title = "录音历史") {
                PurrSecondaryButton(
                    text = if (state.isRecordingsLoading) "加载中..." else "刷新录音",
                    onClick = onRefreshRecordings,
                    enabled = !state.isRecordingsLoading,
                )
                state.recordingsError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!state.isRecordingsLoading && state.recordings.isEmpty()) {
                    Text(
                        text = "暂无录音",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.recordings.forEachIndexed { index, recording ->
                    PurrStatusChip(
                        label = "录音 ${index + 1}",
                        detail = recording.toDisplayDetail(),
                        accentColor = when (recording.status) {
                            CallRecordingStatus.Available -> MaterialTheme.colorScheme.tertiary
                            CallRecordingStatus.Expired -> MaterialTheme.colorScheme.secondary
                            CallRecordingStatus.Processing -> MaterialTheme.colorScheme.primary
                            CallRecordingStatus.Failed -> MaterialTheme.colorScheme.error
                        },
                    )
                    if (recording.downloadAvailable) {
                        val isLoading = state.playbackLoadingRecordingId == recording.recordingId
                        val isPlaying = state.playingRecordingId == recording.recordingId
                        PurrSecondaryButton(
                            text = when {
                                isLoading -> "加载中..."
                                isPlaying -> "暂停"
                                else -> "播放"
                            },
                            onClick = { onPlayRecording(recording.recordingId) },
                            enabled = state.playbackLoadingRecordingId == null,
                        )
                    }
                }
            }
        }

        room?.let { activeRoom ->
            LiveKitRoomStatus(room = activeRoom)
            PurrPanel(title = "远端音频") {
                RemoteAudioVisualizer(room = activeRoom)
            }
        }
    }
}

@OptIn(Beta::class)
@Composable
private fun RemoteAudioVisualizer(room: Room) {
    val remoteIdentity = room.remoteParticipants.keys.firstOrNull()
    if (remoteIdentity == null) {
        Text(
            text = "等待对方接入音频",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val trackReferences = rememberParticipantTrackReferences(
        sources = listOf(Track.Source.MICROPHONE),
        participantIdentity = remoteIdentity,
        passedRoom = room,
        onlySubscribed = true,
    )
    val audioTrackReference = trackReferences.firstOrNull()
    if (audioTrackReference != null) {
        Text(
            text = "已连接远端音频",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AudioBarVisualizer(audioTrackRef = audioTrackReference)
    } else {
        Text(
            text = "已连接，等待远端音频流",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun screenTitle(state: CallScreenState): String = when (state) {
    CallScreenState.Idle -> "准备通话"
    CallScreenState.Dialing -> "正在呼叫"
    CallScreenState.Connecting -> "正在连接"
    CallScreenState.Active -> "通话中"
    CallScreenState.Reconnecting -> "重连中"
    CallScreenState.Ending -> "正在结束"
    CallScreenState.Ended -> "通话结束"
}

@Composable
private fun CallScreenState.accentColor() = when (this) {
    CallScreenState.Active -> MaterialTheme.colorScheme.tertiary
    CallScreenState.Ended -> MaterialTheme.colorScheme.secondary
    CallScreenState.Reconnecting -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun CallScreenState.toDisplayLabel(): String = when (this) {
    CallScreenState.Idle -> "空闲"
    CallScreenState.Dialing -> "呼叫中"
    CallScreenState.Connecting -> "连接中"
    CallScreenState.Active -> "通话中"
    CallScreenState.Reconnecting -> "重连中"
    CallScreenState.Ending -> "结束中"
    CallScreenState.Ended -> "已结束"
}

private fun LocalAudioState.toDisplayLabel(): String = when (this) {
    LocalAudioState.Disabled -> "已关闭"
    LocalAudioState.Enabling -> "开启中"
    LocalAudioState.Enabled -> "已开启"
    LocalAudioState.Muted -> "已静音"
    is LocalAudioState.Error -> reason ?: "异常"
}

private fun RecordingState.toDisplayLabel(): String = when (this) {
    RecordingState.NotRecording -> "未录音"
    RecordingState.Starting -> "启动中"
    RecordingState.Recording -> "录音中"
    RecordingState.Stopping -> "停止中"
    is RecordingState.Failed -> reason ?: "失败"
}

private fun life.fxs.purr.domain.call.model.CallRecording.toDisplayDetail(): String = when (status) {
    CallRecordingStatus.Available -> durationMillis?.let { "可播放 · ${it.toRecordingDuration()}" } ?: "可播放"
    CallRecordingStatus.Expired -> "已过期"
    CallRecordingStatus.Processing -> "处理中"
    CallRecordingStatus.Failed -> failureReason ?: "录音失败"
}

private fun Long.toRecordingDuration(): String {
    val totalSeconds = this / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun AudioRoute.toButtonLabel(selected: Boolean): String {
    val label = when (this) {
        AudioRoute.Earpiece -> "听筒"
        AudioRoute.Speaker -> "扬声器"
        AudioRoute.Bluetooth -> "蓝牙"
        AudioRoute.WiredHeadset -> "有线耳机"
    }
    return if (selected) "$label · 当前使用" else label
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
