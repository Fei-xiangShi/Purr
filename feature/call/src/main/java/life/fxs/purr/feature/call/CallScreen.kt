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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrPanel
import life.fxs.purr.core.designsystem.component.PurrScreen
import life.fxs.purr.core.designsystem.component.PurrSectionTitle
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.LocalAudioState
import life.fxs.purr.domain.call.model.CallTiming

@Composable
fun CallScreenRoute(
    pairId: String,
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localAudioLevel by viewModel.localAudioLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
                is CallEffect.NavigateHome -> onCallEnded()
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
            }
        }
    }

    CallNavHost(
        state = state,
        callDurationSeconds = callDurationSeconds,
        localAudioLevel = localAudioLevel,
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
                EndCallButton(
                    onClick = onEndCall,
                    enabled = canEndCall,
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

        AudioRoutePanel(
            routes = state.availableRoutes,
            activeRoute = state.activeRoute,
            enabled = canManageActiveCall,
            onRouteSelect = onRouteSelect,
        )

    }
}

@Composable
private fun rememberCallDurationSeconds(
    timing: CallTiming,
    callId: String?,
): Long {
    var elapsedSeconds by remember(callId) { mutableLongStateOf(0L) }

    LaunchedEffect(callId, timing) {
        fun synchronizedDurationSeconds(): Long = timing
            .durationAtMonotonicMillis(System.nanoTime() / 1_000_000L)
            .div(1_000L)

        elapsedSeconds = synchronizedDurationSeconds()
        if (!timing.isRunning) return@LaunchedEffect
        while (true) {
            delay(CALL_DURATION_TICK_INTERVAL_MILLIS)
            elapsedSeconds = synchronizedDurationSeconds()
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
