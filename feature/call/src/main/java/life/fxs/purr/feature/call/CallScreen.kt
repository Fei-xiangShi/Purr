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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import life.fxs.purr.core.designsystem.component.VoiceCallScaffold
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.call.model.CallTiming
import life.fxs.purr.domain.call.model.CallPreparationRequest
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
        remoteAudioLevel = remoteAudioLevel,
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        partnerAvatarModifier = partnerAvatarModifier,
        onCallSurfaceVisibilityChanged = onCallSurfaceVisibilityChanged,
        onMuteToggle = { viewModel.onIntent(CallIntent.MuteToggle) },
        onRouteSelect = { route -> viewModel.onIntent(CallIntent.RouteSelect(route)) },
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
    onShowDiagnostics: () -> Unit,
    onEndCall: () -> Unit,
) {
    val canManageActiveCall = !state.isLoading &&
        (state.screenState == CallScreenState.Waiting || state.screenState == CallScreenState.Active)
    val microphoneMuted = state.localAudioState.isEffectivelyMuted
    val canEndCall = state.screenState in setOf(
        CallScreenState.Dialing,
        CallScreenState.Connecting,
        CallScreenState.Waiting,
        CallScreenState.Active,
        CallScreenState.Reconnecting,
        CallScreenState.Failed,
    )
    val detail = when {
        state.screenState == CallScreenState.Failed ->
            state.failureMessage ?: "通话连接失败，请稍后重试"
        state.screenState == CallScreenState.Active || callDurationSeconds > 0L ->
            callDurationSeconds.toCallDuration()
        else -> state.screenState.connectionDetail()
    }

    VoiceCallScaffold(
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        status = state.screenState.title(),
        detail = detail,
        avatarModifier = partnerAvatarModifier,
        remoteAudioLevel = remoteAudioLevel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicrophoneLevelButton(
                label = if (microphoneMuted) "取消静音" else "静音",
                muted = microphoneMuted,
                level = localAudioLevel,
                onClick = onMuteToggle,
                enabled = canManageActiveCall && state.localAudioState.canToggleMute,
            )
            AudioRoutePicker(
                routes = state.availableRoutes,
                activeRoute = state.activeRoute,
                enabled = canManageActiveCall && state.availableRoutes.isNotEmpty(),
                onRouteSelect = onRouteSelect,
            )
            DiagnosticsButton(
                onClick = onShowDiagnostics,
                enabled = state.session != null,
            )
            EndCallButton(onClick = onEndCall, enabled = canEndCall)
        }
    }
}

@Composable
private fun DiagnosticsButton(onClick: () -> Unit, enabled: Boolean) {
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
            imageVector = Icons.Rounded.NetworkCheck,
            contentDescription = "通话质量检测",
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
    CallScreenState.Reconnecting -> "正在重新连接"
    CallScreenState.Ending -> "正在结束"
    CallScreenState.Ended -> "通话结束"
    CallScreenState.Failed -> "通话失败"
}

private fun CallScreenState.connectionDetail(): String = when (this) {
    CallScreenState.Dialing -> "正在发起安全语音通话"
    CallScreenState.Connecting -> "正在建立加密连接"
    CallScreenState.Waiting -> "对方加入后即可通话"
    CallScreenState.Reconnecting -> "网络波动，请稍候"
    CallScreenState.Ending -> "正在释放通话资源"
    CallScreenState.Ended -> "感谢使用 Purr 语音"
    CallScreenState.Failed -> "通话连接失败，请稍后重试"
    else -> "Purr 语音"
}

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
