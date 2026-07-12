package life.fxs.purr.feature.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.LocalAudioState

@Composable
fun CallScreenRoute(
    pairId: String,
    roomStateProvider: CallRoomStateProvider,
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room by roomStateProvider.room.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
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
    val callDurationSeconds = rememberCallDurationSeconds(
        isTwoPartyActive = state.screenState == CallScreenState.Active,
        callId = state.session?.callId,
    )
    val localAudioLevel = rememberLocalAudioLevel(
        room = room,
        enabled = state.localAudioState is LocalAudioState.Enabled,
    )

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

    LaunchedEffect(state.screenState) {
        if (state.screenState == CallScreenState.Ended) {
            onCallEnded()
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

    if (showDiagnostics) {
        BackHandler { showDiagnostics = false }
        CallDiagnosticsScreen(
            state = state,
            callDurationSeconds = callDurationSeconds,
        )
        return
    }

    CallScreen(
        state = state,
        room = room,
        callDurationSeconds = callDurationSeconds,
        localAudioLevel = localAudioLevel,
        onMuteToggle = { viewModel.onIntent(CallIntent.MuteToggle) },
        onRouteSelect = { route -> viewModel.onIntent(CallIntent.RouteSelect(route)) },
        onShowDiagnostics = { showDiagnostics = true },
        onEndCall = { viewModel.onIntent(CallIntent.EndCall) },
    )
}

@Composable
fun CallScreen(
    state: CallState,
    room: Room?,
    callDurationSeconds: Long,
    localAudioLevel: Float,
    onMuteToggle: () -> Unit,
    onRouteSelect: (AudioRoute) -> Unit,
    onShowDiagnostics: () -> Unit,
    onEndCall: () -> Unit,
) {
    val session = state.session
    val remoteName = session?.participantIdentity?.remote ?: "通话对象"
    val canManageActiveCall = !state.isLoading &&
        (state.screenState == CallScreenState.Waiting || state.screenState == CallScreenState.Active)
    val canEndCall = state.screenState == CallScreenState.Dialing ||
        state.screenState == CallScreenState.Connecting ||
        state.screenState == CallScreenState.Waiting ||
        state.screenState == CallScreenState.Active ||
        state.screenState == CallScreenState.Reconnecting

    PurrScreen {
        PurrSectionTitle(
            eyebrow = "通话",
            title = screenTitle(state.screenState),
            subtitle = if (callDurationSeconds > 0L) callDurationSeconds.toCallDuration() else "",
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
            if (state.screenState == CallScreenState.Active || callDurationSeconds > 0L) {
                PurrStatusChip(
                    label = "通话时长",
                    detail = callDurationSeconds.toCallDuration(),
                    accentColor = MaterialTheme.colorScheme.primary,
                )
            }
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
        }

        PurrPanel(title = "操作") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicrophoneLevelButton(
                    label = if (state.localAudioState is LocalAudioState.Muted) "取消静音" else "静音",
                    muted = state.localAudioState is LocalAudioState.Muted,
                    level = localAudioLevel,
                    onClick = onMuteToggle,
                    enabled = canManageActiveCall,
                )
                LightIconButton(
                    label = "结束通话",
                    icon = Icons.Rounded.CallEnd,
                    onClick = onEndCall,
                    enabled = canEndCall,
                    containerColor = MaterialTheme.colorScheme.error,
                )
            }
        }

        Button(
            onClick = onShowDiagnostics,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                imageVector = Icons.Rounded.NetworkCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text("通话质量检测", style = MaterialTheme.typography.labelLarge)
        }

        PurrPanel(title = "音频输出") {
            state.availableRoutes.forEachIndexed { index, route ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ListItem(
                    headlineContent = { Text(route.toDisplayLabel()) },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Icon(
                                imageVector = route.toIcon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .size(36.dp)
                                    .padding(8.dp),
                            )
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = route == state.activeRoute,
                            onClick = null,
                            enabled = canManageActiveCall,
                        )
                    },
                    modifier = Modifier.clickable(enabled = canManageActiveCall) {
                        onRouteSelect(route)
                    },
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicrophoneLevelButton(
    label: String,
    muted: Boolean,
    level: Float,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = LIGHT_ICON_BUTTON_BACKGROUND,
                contentColor = Color.White,
                disabledContainerColor = LIGHT_ICON_BUTTON_BACKGROUND.copy(alpha = 0.48f),
                disabledContentColor = Color.White.copy(alpha = 0.48f),
            ),
        ) {
            if (muted) {
                Icon(
                    imageVector = Icons.Rounded.MicOff,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                MicrophoneLevelIcon(
                    level = level,
                    contentDescription = label,
                )
            }
        }
    }
}

@Composable
private fun MicrophoneLevelIcon(
    level: Float,
    contentDescription: String,
) {
    val normalizedLevel = level.coerceIn(0f, 1f)
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.34f),
            modifier = Modifier.fillMaxSize(),
        )
        if (normalizedLevel > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(normalizedLevel)
                    .align(Alignment.BottomCenter)
                    .clipToBounds(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = MICROPHONE_LEVEL_BLUE,
                    modifier = Modifier
                        .requiredSize(32.dp)
                        .align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = Color.White,
                disabledContainerColor = containerColor.copy(alpha = 0.48f),
                disabledContentColor = Color.White.copy(alpha = 0.48f),
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
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

@Composable
private fun rememberLocalAudioLevel(
    room: Room?,
    enabled: Boolean,
): Float {
    var level by remember(room) { mutableFloatStateOf(0f) }
    LaunchedEffect(room, enabled) {
        if (!enabled || room == null) {
            level = 0f
            return@LaunchedEffect
        }
        while (true) {
            level = room.localParticipant.audioLevel.coerceIn(0f, 1f)
            delay(AUDIO_LEVEL_REFRESH_INTERVAL_MILLIS)
        }
    }
    return level
}

@Composable
private fun rememberCallDurationSeconds(
    isTwoPartyActive: Boolean,
    callId: String?,
): Long {
    var accumulatedMillis by remember(callId) { mutableLongStateOf(0L) }
    var elapsedSeconds by remember(callId) { mutableLongStateOf(0L) }
    val currentTwoPartyActive by rememberUpdatedState(isTwoPartyActive)

    LaunchedEffect(callId) {
        accumulatedMillis = 0L
        elapsedSeconds = 0L
        var previousTickMillis = SystemClock.elapsedRealtime()
        while (true) {
            delay(CALL_DURATION_TICK_INTERVAL_MILLIS)
            val now = SystemClock.elapsedRealtime()
            if (currentTwoPartyActive) {
                accumulatedMillis += now - previousTickMillis
                elapsedSeconds = accumulatedMillis / 1_000L
            }
            previousTickMillis = now
        }
    }
    return elapsedSeconds
}

private fun screenTitle(state: CallScreenState): String = when (state) {
    CallScreenState.Idle -> "准备通话"
    CallScreenState.Dialing -> "正在呼叫"
    CallScreenState.Connecting -> "正在连接"
    CallScreenState.Waiting -> "等待对方"
    CallScreenState.Active -> "通话中"
    CallScreenState.Reconnecting -> "重连中"
    CallScreenState.Ending -> "正在结束"
    CallScreenState.Ended -> "通话结束"
}

@Composable
private fun CallScreenState.accentColor() = when (this) {
    CallScreenState.Active -> MaterialTheme.colorScheme.tertiary
    CallScreenState.Waiting -> MaterialTheme.colorScheme.primary
    CallScreenState.Ended,
    CallScreenState.Reconnecting,
    -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun CallScreenState.toDisplayLabel(): String = when (this) {
    CallScreenState.Idle -> "空闲"
    CallScreenState.Dialing -> "呼叫中"
    CallScreenState.Connecting -> "连接中"
    CallScreenState.Waiting -> "等待对方"
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

internal fun Long.toCallDuration(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    val seconds = this % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
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

private val LIGHT_ICON_BUTTON_BACKGROUND = Color(0xFF263238)
private val MICROPHONE_LEVEL_BLUE = Color(0xFF64B5F6)
private const val AUDIO_LEVEL_REFRESH_INTERVAL_MILLIS = 50L
private const val CALL_DURATION_TICK_INTERVAL_MILLIS = 250L
