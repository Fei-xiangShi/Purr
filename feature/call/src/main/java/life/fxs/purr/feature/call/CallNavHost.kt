package life.fxs.purr.feature.call

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import life.fxs.purr.core.model.AudioRoute

private const val CALL_OVERVIEW_ROUTE = "overview"
private const val CALL_DIAGNOSTICS_ROUTE = "diagnostics"

/**
 * Owns navigation between screens that belong to an active call. Application-level navigation
 * remains with the app NavHost, so each back stack has one source of truth.
 */
@Composable
internal fun CallNavHost(
    state: CallState,
    callDurationSeconds: Long,
    localAudioLevel: Float,
    remoteAudioLevel: Float,
    partnerName: String,
    partnerAvatarUrl: String?,
    partnerAvatarModifier: Modifier,
    onCallSurfaceVisibilityChanged: (Boolean) -> Unit,
    onMuteToggle: () -> Unit,
    onRouteSelect: (AudioRoute) -> Unit,
    onOpenScreenSharePicker: () -> Unit,
    onDismissScreenSharePicker: () -> Unit,
    onStartMobileScreenShare: () -> Unit,
    onStartObsScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onDismissObsSetup: () -> Unit,
    createRemoteScreenRenderer: (Context) -> View,
    releaseRemoteScreenRenderer: (View) -> Unit,
    onEndCall: () -> Unit,
) {
    val navController = rememberNavController()

    // This nested stack gets the first back callback while diagnostics is visible; the outer
    // app stack handles the overview destination once this stack reaches its root.
    NavHost(
        navController = navController,
        startDestination = CALL_OVERVIEW_ROUTE,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(CALL_OVERVIEW_ROUTE) {
            DisposableEffect(onCallSurfaceVisibilityChanged) {
                onCallSurfaceVisibilityChanged(true)
                onDispose { onCallSurfaceVisibilityChanged(false) }
            }
            CallScreen(
                state = state,
                callDurationSeconds = callDurationSeconds,
                localAudioLevel = localAudioLevel,
                remoteAudioLevel = remoteAudioLevel,
                partnerName = partnerName,
                partnerAvatarUrl = partnerAvatarUrl,
                partnerAvatarModifier = partnerAvatarModifier,
                onMuteToggle = onMuteToggle,
                onRouteSelect = onRouteSelect,
                onOpenScreenSharePicker = onOpenScreenSharePicker,
                onDismissScreenSharePicker = onDismissScreenSharePicker,
                onStartMobileScreenShare = onStartMobileScreenShare,
                onStartObsScreenShare = onStartObsScreenShare,
                onStopScreenShare = onStopScreenShare,
                onDismissObsSetup = onDismissObsSetup,
                createRemoteScreenRenderer = createRemoteScreenRenderer,
                releaseRemoteScreenRenderer = releaseRemoteScreenRenderer,
                onShowDiagnostics = {
                    navController.navigate(CALL_DIAGNOSTICS_ROUTE) {
                        launchSingleTop = true
                    }
                },
                onEndCall = onEndCall,
            )
        }
        composable(CALL_DIAGNOSTICS_ROUTE) {
            CallDiagnosticsScreen(
                context = state.toDiagnosticsContext(),
                callDurationSeconds = callDurationSeconds,
            )
        }
    }
}
