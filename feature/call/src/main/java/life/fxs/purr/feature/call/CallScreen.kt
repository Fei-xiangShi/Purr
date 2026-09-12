package life.fxs.purr.feature.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import life.fxs.purr.core.designsystem.component.VoiceCallScaffold
import life.fxs.purr.core.media.screenshare.ScreenShareQuality
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.domain.call.model.CallTiming
import life.fxs.purr.domain.call.model.LocalScreenShareState
import life.fxs.purr.domain.call.model.canShowLocalAudioActivity
import life.fxs.purr.domain.call.model.canShowRemoteAudioActivity
import life.fxs.purr.domain.call.model.canToggleMute
import life.fxs.purr.domain.call.model.isEffectivelyMuted

@Composable
fun CallScreenRoute(
    request: CallPreparationRequest,
    partnerName: String = "对方",
    partnerAvatarUrl: String? = null,
    partnerAvatarModifier: Modifier = Modifier,
    onCallSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localAudioLevel by viewModel.localAudioLevel.collectAsStateWithLifecycle()
    val remoteAudioLevel by viewModel.remoteAudioLevel.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val callDurationSeconds = rememberCallDurationSeconds(
        timing = state.session?.timing ?: CallTiming(),
        callId = state.session?.callId,
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
    ) { requestMicrophonePermission() }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onIntent(
            CallIntent.ScreenCapturePermissionResult(
                resultCode = result.resultCode,
                data = result.data,
            ),
        )
    }

    LaunchedEffect(request, partnerName) {
        when (request) {
            is CallPreparationRequest.NewOutgoing -> viewModel.onIntent(
                CallIntent.StartNewOutgoingCall(
                    pairId = request.pairId,
                    remoteDisplayName = partnerName,
                ),
            )
            is CallPreparationRequest.Existing -> viewModel.onIntent(
                CallIntent.OpenExistingCall(
                    pairId = request.pairId,
                    callId = request.callId,
                    remoteDisplayName = partnerName,
                    direction = request.direction,
                ),
            )
        }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CallEffect.OpenAppSettings -> context.openAppSettings()
                CallEffect.NavigateHome -> onCallEnded()
                is CallEffect.RequestScreenCapturePermission -> {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                }
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
                is CallEffect.ShowMessage -> Toast.makeText(
                    context,
                    effect.message,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    CallNavHost(
        state = state,
        callDurationSeconds = callDurationSeconds,
        localAudioLevel = localAudioLevel,
        remoteAudioLevel = remoteAudioLevel.takeIf {
            !state.screenState.isTerminalPresentation() && state.session?.canShowRemoteAudioActivity == true
        } ?: 0f,
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        partnerAvatarModifier = partnerAvatarModifier,
        onCallSurfaceVisibilityChanged = onCallSurfaceVisibilityChanged,
        onMuteToggle = { viewModel.onIntent(CallIntent.MuteToggle) },
        onRouteSelect = { route -> viewModel.onIntent(CallIntent.RouteSelect(route)) },
        onOpenScreenSharePicker = { viewModel.onIntent(CallIntent.OpenScreenSharePicker) },
        onDismissScreenSharePicker = { viewModel.onIntent(CallIntent.DismissScreenSharePicker) },
        onSelectPublishQuality = { viewModel.onIntent(CallIntent.SelectPublishQuality(it)) },
        onStartMobileScreenShare = { viewModel.onIntent(CallIntent.StartMobileScreenShare) },
        onStartObsScreenShare = { viewModel.onIntent(CallIntent.StartObsScreenShare) },
        onStopScreenShare = { viewModel.onIntent(CallIntent.StopScreenShare) },
        onRetryRemoteScreenShare = { viewModel.onIntent(CallIntent.RetryRemoteScreenShare) },
        onDismissObsSetup = { viewModel.onIntent(CallIntent.DismissObsSetup) },
        createRemoteScreenRenderer = viewModel::createRemoteScreenRenderer,
        releaseRemoteScreenRenderer = viewModel::releaseRemoteScreenRenderer,
        onEndCall = { viewModel.onIntent(CallIntent.EndCall) },
    )
}

@Composable
fun CallScreen(
    state: CallState,
    callDurationSeconds: Long,
    localAudioLevel: Float,
    partnerName: String = "对方",
    partnerAvatarUrl: String? = null,
    remoteAudioLevel: Float = 0f,
    partnerAvatarModifier: Modifier = Modifier,
    onMuteToggle: () -> Unit,
    onRouteSelect: (AudioRoute) -> Unit,
    onOpenScreenSharePicker: () -> Unit,
    onDismissScreenSharePicker: () -> Unit,
    onSelectPublishQuality: (ScreenShareQuality) -> Unit,
    onStartMobileScreenShare: () -> Unit,
    onStartObsScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onRetryRemoteScreenShare: () -> Unit,
    onDismissObsSetup: () -> Unit,
    createRemoteScreenRenderer: (Context) -> View,
    releaseRemoteScreenRenderer: (View) -> Unit,
    onShowDiagnostics: () -> Unit,
    onEndCall: () -> Unit,
) {
    var toolsVisible by remember(state.session?.callId) { mutableStateOf(false) }
    var audioRoutesVisible by remember(state.session?.callId) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(state.screenShare.session?.shareId) { mutableStateOf(false) }
    var controlsVisible by remember(state.screenShare.session?.shareId) { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    val live = state.screenState == CallScreenState.Active &&
        (state.screenShare.remoteState == RemoteScreenShareUiState.Live ||
            state.screenShare.localState is LocalScreenShareState.Live)
    val keepControls = toolsVisible || audioRoutesVisible ||
        state.screenShare.sourcePickerVisible || state.screenShare.obsSetupVisible
    LaunchedEffect(live, interaction, keepControls, state.screenShare.session?.shareId) {
        controlsVisible = true
        if (live && !keepControls) {
            delay(4_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(state.screenShare.shouldShowRemotePanel, state.screenState) {
        if (!state.screenShare.shouldShowRemotePanel || state.screenState.isTerminalPresentation()) fullscreen = false
    }
    val recordInteraction: () -> Unit = { interaction++; controlsVisible = true }
    val interactionModifier = Modifier.pointerInput(state.screenShare.session?.shareId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.any { it.pressed }) recordInteraction()
            }
        }
    }
    LaunchedEffect(state.screenState) {
        if (state.screenState.isTerminalPresentation()) toolsVisible = false
    }
    val canManageActiveCall = !state.isLoading &&
        (state.screenState == CallScreenState.Waiting || state.screenState == CallScreenState.Active)
    val microphoneMuted = state.localAudioState.isEffectivelyMuted
    val canEndCall = state.screenState in setOf(
        CallScreenState.Dialing,
        CallScreenState.Connecting,
        CallScreenState.Waiting,
        CallScreenState.Active,
        CallScreenState.SystemCallSuspended,
        CallScreenState.ResumingAfterSystemCall,
        CallScreenState.RemoteSystemCallSuspended,
        CallScreenState.RemoteResumingAfterSystemCall,
        CallScreenState.Reconnecting,
        CallScreenState.Failed,
    )
    val detail = when {
        state.screenState == CallScreenState.Failed ->
            state.failureMessage ?: "通话连接失败，请稍后重试"
        state.screenState.isSystemCallInterruptionState() -> {
            val interruptionDetail = state.screenState.connectionDetail()
            if (callDurationSeconds > 0L) {
                "$interruptionDetail · ${callDurationSeconds.toCallDuration()}"
            } else {
                interruptionDetail
            }
        }
        state.screenState == CallScreenState.Active || callDurationSeconds > 0L ->
            callDurationSeconds.toCallDuration()
        else -> state.screenState.connectionDetail()
    }

    VoiceCallScaffold(
        modifier = interactionModifier,
        chromeVisible = controlsVisible,
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        status = if (state.screenState == CallScreenState.Waiting &&
            state.session?.timing?.startedAtEpochMillis != null
        ) {
            "对方已离开，等待重新加入"
        } else {
            state.screenState.title()
        },
        detail = detail,
        avatarModifier = partnerAvatarModifier,
        remoteAudioLevel = remoteAudioLevel,
        bottomContent = {
            LocalScreenShareStatus(state.screenShare)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicrophoneLevelButton(
                    label = if (microphoneMuted) "取消静音" else "静音",
                    muted = microphoneMuted,
                    level = localAudioLevel.takeIf {
                        !state.screenState.isTerminalPresentation() && state.session?.canShowLocalAudioActivity == true
                    } ?: 0f,
                    onClick = onMuteToggle,
                    enabled = canManageActiveCall && state.localAudioState.canToggleMute,
                )
                AudioRoutePicker(
                    routes = state.availableRoutes,
                    activeRoute = state.activeRoute,
                    enabled = canManageActiveCall && state.availableRoutes.isNotEmpty(),
                    onRouteSelect = onRouteSelect,
                    onVisibilityChanged = { audioRoutesVisible = it },
                )
                CallToolsButton(
                    onClick = { toolsVisible = true },
                    enabled = state.session != null && !state.screenState.isTerminalPresentation(),
                )
                EndCallButton(onClick = onEndCall, enabled = canEndCall)
            }
        },
        centerContent = if (state.screenShare.shouldShowRemotePanel) {
            {
                if (!fullscreen) {
                    RemoteScreenSharePanel(
                        state = state.screenShare,
                        controlsVisible = controlsVisible,
                        onFullscreen = { fullscreen = true; recordInteraction() },
                        createRenderer = createRemoteScreenRenderer,
                        releaseRenderer = releaseRemoteScreenRenderer,
                onRetry = onRetryRemoteScreenShare,
                    )
                } else {
                    Spacer(Modifier.fillMaxSize())
                }
            }
        } else {
            null
        },
    )

    if (fullscreen && state.screenShare.shouldShowRemotePanel) {
        ScreenShareFullscreenDialog(onDismiss = { fullscreen = false; recordInteraction() }) {
            RemoteScreenSharePanel(
                state = state.screenShare,
                fullscreen = true,
                controlsVisible = controlsVisible,
                modifier = interactionModifier,
                onFullscreen = { fullscreen = false; recordInteraction() },
                createRenderer = createRemoteScreenRenderer,
                releaseRenderer = releaseRemoteScreenRenderer,
                onRetry = onRetryRemoteScreenShare,
            )
        }
    }

    if (toolsVisible && !state.screenState.isTerminalPresentation()) {
        CallToolsSheet(
            state = state.screenShare,
            canStartScreenShare = state.screenState == CallScreenState.Active && !state.isLoading,
            onDismiss = { toolsVisible = false },
            onScreenShare = {
                toolsVisible = false
                onOpenScreenSharePicker()
            },
            onStopScreenShare = {
                toolsVisible = false
                onStopScreenShare()
            },
            onDiagnostics = {
                toolsVisible = false
                onShowDiagnostics()
            },
        )
    }

    if (state.screenShare.sourcePickerVisible) {
        ScreenShareSourceSheet(
            quality = state.screenShare.publishQuality,
            onSelectQuality = onSelectPublishQuality,
            onDismiss = onDismissScreenSharePicker,
            onMobile = onStartMobileScreenShare,
            onObs = onStartObsScreenShare,
        )
    }
    if (state.screenShare.obsSetupVisible) {
        state.screenShare.obsPublishing?.let { publishing ->
            ObsSetupDialog(publishing = publishing, quality = state.screenShare.publishQuality, onDismiss = onDismissObsSetup)
        }
    }
}

@Composable
private fun CallToolsButton(onClick: () -> Unit, enabled: Boolean) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.07f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = "投屏与网络检测",
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun rememberCallDurationSeconds(timing: CallTiming, callId: String?): Long {
    val elapsedSeconds = remember(callId) { mutableLongStateOf(0L) }
    LaunchedEffect(callId, timing) {
        fun synchronizedDurationSeconds(): Long = timing
            .durationAtMonotonicMillis(System.nanoTime() / 1_000_000L)
            .div(1_000L)
        elapsedSeconds.longValue = synchronizedDurationSeconds()
        if (!timing.isRunning) return@LaunchedEffect
        while (true) {
            delay(CALL_DURATION_TICK_INTERVAL_MILLIS)
            elapsedSeconds.longValue = synchronizedDurationSeconds()
        }
    }
    return elapsedSeconds.longValue
}

private fun CallScreenState.title(): String = when (this) {
    CallScreenState.Idle -> "准备通话"
    CallScreenState.Dialing -> "正在呼叫"
    CallScreenState.Connecting -> "正在连接"
    CallScreenState.Waiting -> "等待对方接听"
    CallScreenState.Active -> "通话中"
    CallScreenState.SystemCallSuspended -> "通话已暂时挂起"
    CallScreenState.ResumingAfterSystemCall -> "正在恢复通话"
    CallScreenState.RemoteSystemCallSuspended -> "对方正在接听系统电话"
    CallScreenState.RemoteResumingAfterSystemCall -> "对方正在恢复通话"
    CallScreenState.Reconnecting -> "正在重新连接"
    CallScreenState.Ending -> "正在结束"
    CallScreenState.Ended -> "通话结束"
    CallScreenState.Failed -> "通话失败"
}

private fun CallScreenState.connectionDetail(): String = when (this) {
    CallScreenState.Dialing -> "正在发起安全语音通话"
    CallScreenState.Connecting -> "正在建立加密连接"
    CallScreenState.Waiting -> "对方加入后即可通话"
    CallScreenState.SystemCallSuspended -> "系统电话结束后将自动恢复"
    CallScreenState.ResumingAfterSystemCall -> "正在恢复麦克风和通话声音"
    CallScreenState.RemoteSystemCallSuspended -> "对方系统电话结束后将自动恢复"
    CallScreenState.RemoteResumingAfterSystemCall -> "请稍候，正在重新接通对方声音"
    CallScreenState.Reconnecting -> "网络波动，请稍候"
    CallScreenState.Ending -> "正在释放通话资源"
    CallScreenState.Ended -> "感谢使用 Purr 语音"
    CallScreenState.Failed -> "通话连接失败，请稍后重试"
    else -> "Purr 语音"
}

private fun CallScreenState.isSystemCallInterruptionState(): Boolean = when (this) {
    CallScreenState.SystemCallSuspended,
    CallScreenState.ResumingAfterSystemCall,
    CallScreenState.RemoteSystemCallSuspended,
    CallScreenState.RemoteResumingAfterSystemCall,
    -> true
    else -> false
}

private fun CallScreenState.isTerminalPresentation(): Boolean =
    this == CallScreenState.Ending || this == CallScreenState.Ended || this == CallScreenState.Failed

internal fun Long.toCallDuration(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    val seconds = this % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
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

private const val CALL_DURATION_TICK_INTERVAL_MILLIS = 250L
