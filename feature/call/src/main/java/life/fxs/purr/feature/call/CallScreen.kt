package life.fxs.purr.feature.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.state.rememberParticipantTrackReferences
import io.livekit.android.compose.ui.audio.AudioBarVisualizer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrStatusChip
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.core.model.AudioRoute

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
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.onIntent(CallIntent.MicrophonePermissionResult(granted = true))
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                is CallEffect.ShowMessage -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    CallScreen(
        state = state,
        room = room,
        onConnect = { viewModel.onIntent(CallIntent.ConnectCall(pairId = pairId)) },
        onMuteToggle = { viewModel.onIntent(CallIntent.MuteToggle) },
        onRouteSelect = { route -> viewModel.onIntent(CallIntent.RouteSelect(route)) },
        onEndCall = { viewModel.onIntent(CallIntent.EndCall) },
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
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Call",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "State: ${state.screenState}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.isLoading) {
            PurrStatusChip(label = "Updating call state")
        }
        PurrStatusChip(label = "Route: ${state.activeRoute}")
        PurrStatusChip(label = "Mic: ${state.localAudioState}")
        PurrStatusChip(label = "Recording: ${state.recordingState}")
        PurrStatusChip(label = if (state.isForegroundServiceActive) "Foreground service active" else "Foreground service idle")
        room?.let { activeRoom ->
            LiveKitRoomStatus(room = activeRoom)
            RemoteAudioVisualizer(room = activeRoom)
        }

        Button(
            onClick = onConnect,
            enabled = !state.isLoading && (state.screenState == CallScreenState.Idle || state.screenState == CallScreenState.Ended),
        ) {
            Text("Connect call")
        }

        Button(
            onClick = onMuteToggle,
            enabled = !state.isLoading && state.screenState == CallScreenState.Active,
        ) {
            Text("Toggle mute")
        }

        state.availableRoutes.forEach { route ->
            Button(
                onClick = { onRouteSelect(route) },
                enabled = !state.isLoading && state.screenState == CallScreenState.Active,
            ) {
                Text("Route: ${route.name}")
            }
        }

        Button(
            onClick = onEndCall,
            enabled = !state.isLoading && state.screenState != CallScreenState.Idle && state.screenState != CallScreenState.Ended,
        ) {
            Text("End call")
        }

        Button(onClick = onBack) {
            Text("Back")
        }
    }
}

@OptIn(Beta::class)
@Composable
private fun RemoteAudioVisualizer(room: Room) {
    val remoteIdentity = room.remoteParticipants.keys.firstOrNull() ?: return
    val trackReferences = rememberParticipantTrackReferences(
        sources = listOf(Track.Source.MICROPHONE),
        participantIdentity = remoteIdentity,
        passedRoom = room,
        onlySubscribed = true,
    )
    val audioTrackReference = trackReferences.firstOrNull()
    if (audioTrackReference != null) {
        Text(
            text = "Remote participant connected",
            style = MaterialTheme.typography.bodyMedium,
        )
        AudioBarVisualizer(audioTrackRef = audioTrackReference)
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
