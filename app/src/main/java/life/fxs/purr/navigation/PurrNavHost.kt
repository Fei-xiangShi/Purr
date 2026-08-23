package life.fxs.purr.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import life.fxs.purr.feature.auth.AuthScreenRoute
import life.fxs.purr.feature.call.CallHistoryNavHost
import life.fxs.purr.feature.call.RecordingDownloadRequest
import life.fxs.purr.feature.call.CallScreenRoute
import life.fxs.purr.feature.home.HomeScreenRoute
import life.fxs.purr.feature.incomingcall.IncomingCallPromptRoute
import life.fxs.purr.feature.settings.SettingsScreenRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.domain.call.model.CallPreparationRequest
import life.fxs.purr.feature.home.HomeCallTarget

private const val AUTH_ROUTE = "auth"
private const val HOME_ROUTE = "home"
private const val SETTINGS_ROUTE = "settings"
private const val INCOMING_CALL_ROUTE = "incoming-call"
private const val NEW_CALL_ROUTE = "new-call"
private const val EXISTING_CALL_ROUTE = "existing-call"
private const val CALL_HISTORY_ROUTE = "call-history"
private const val PAIR_ID_ARG = "pairId"
private const val DIRECTION_ARG = "direction"
private const val CALL_ID_ARG = "callId"
private const val NEW_CALL_DESTINATION = "$NEW_CALL_ROUTE/{$PAIR_ID_ARG}"
private const val EXISTING_CALL_DESTINATION =
    "$EXISTING_CALL_ROUTE/{$PAIR_ID_ARG}/{$CALL_ID_ARG}?$DIRECTION_ARG={$DIRECTION_ARG}"
private const val PARTNER_AVATAR_SHARED_KEY = "partner-avatar"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PurrNavHost(
    initialCallRequest: CallNavigationRequest? = null,
    onCallRequestConsumed: (Long) -> Unit = {},
    onCallSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    onRecordingDownload: (RecordingDownloadRequest) -> Unit = {},
    viewModel: SessionGateViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val gateState by viewModel.state.collectAsStateWithLifecycle()
    val currentOnCallRequestConsumed by rememberUpdatedState(onCallRequestConsumed)
    val currentEntry by navController.currentBackStackEntryAsState()

    if (!gateState.isReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (gateState.isAuthenticated) HOME_ROUTE else AUTH_ROUTE
    SharedTransitionLayout {
        val transitionScope = this
        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(AUTH_ROUTE) {
                AuthScreenRoute(
                    onLoginSuccess = {
                        navController.navigate(HOME_ROUTE) {
                            popUpTo(AUTH_ROUTE) { inclusive = true }
                        }
                    },
                )
            }
            composable(HOME_ROUTE) {
                HomeScreenRoute(
                    onOpenCall = { target ->
                        navController.navigate(target.route()) {
                            launchSingleTop = true
                        }
                    },
                    onOpenCallHistory = { navController.navigate(CALL_HISTORY_ROUTE) },
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                )
            }
            composable(INCOMING_CALL_ROUTE) {
                val incomingCall = gateState.incomingCall
                if (incomingCall == null) {
                    LaunchedEffect(Unit) {
                        navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                    }
                } else {
                    IncomingCallPromptRoute(
                        partnerName = gateState.partner?.displayName ?: "对方",
                        partnerAvatarUrl = gateState.partner?.avatarUrl,
                        callId = incomingCall.callId,
                        avatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                        onOpenCall = { pairId, callId ->
                            navController.navigate(existingCallRoute(pairId, CallDirection.Incoming, callId)) {
                                popUpTo(INCOMING_CALL_ROUTE) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onDismiss = {
                            if (!navController.popBackStack()) {
                                navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                            }
                        },
                    )
                }
            }
            composable(NEW_CALL_DESTINATION, arguments = listOf(
                    navArgument(PAIR_ID_ARG) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val pairId = backStackEntry.arguments?.getString(PAIR_ID_ARG).orEmpty()
                if (pairId.isBlank()) {
                    LaunchedEffect(Unit) { navController.navigate(HOME_ROUTE) { launchSingleTop = true } }
                } else {
                    CallScreenRoute(
                        request = CallPreparationRequest.NewOutgoing(pairId = pairId, recordingConsent = true),
                        partnerName = gateState.partner?.displayName ?: "对方",
                        partnerAvatarUrl = gateState.partner?.avatarUrl,
                        partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                        onCallSurfaceVisibilityChanged = onCallSurfaceVisibilityChanged,
                        onCallEnded = {
                            if (!navController.popBackStack()) {
                                navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                            }
                        },
                    )
                }
            }
            composable(EXISTING_CALL_DESTINATION, arguments = listOf(
                navArgument(PAIR_ID_ARG) { type = NavType.StringType },
                navArgument(CALL_ID_ARG) { type = NavType.StringType },
                navArgument(DIRECTION_ARG) {
                    type = NavType.StringType
                },
            )) { backStackEntry ->
                val pairId = backStackEntry.arguments?.getString(PAIR_ID_ARG).orEmpty()
                val callId = backStackEntry.arguments?.getString(CALL_ID_ARG).orEmpty()
                val direction = backStackEntry.arguments?.getString(DIRECTION_ARG)
                    ?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() }
                if (pairId.isBlank() || callId.isBlank() || direction == null) {
                    LaunchedEffect(Unit) { navController.navigate(HOME_ROUTE) { launchSingleTop = true } }
                } else {
                    CallScreenRoute(
                        request = CallPreparationRequest.Existing(
                            pairId = pairId,
                            callId = callId,
                            direction = direction,
                            recordingConsent = true,
                        ),
                        partnerName = gateState.partner?.displayName ?: "对方",
                        partnerAvatarUrl = gateState.partner?.avatarUrl,
                        partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                        onCallSurfaceVisibilityChanged = onCallSurfaceVisibilityChanged,
                        onCallEnded = {
                            if (!navController.popBackStack()) navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                        },
                    )
                }
            }
            composable(CALL_HISTORY_ROUTE) {
                CallHistoryNavHost(onDownload = onRecordingDownload)
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreenRoute(
                    onLoggedOut = {
                        navController.navigate(AUTH_ROUTE) {
                            popUpTo(HOME_ROUTE) { inclusive = true }
                        }
                    },
                )
            }
        }
    }

    val incomingCall = gateState.incomingCall
    val currentRoute = currentEntry?.destination?.route
    LaunchedEffect(incomingCall?.callId, currentRoute, gateState.isAuthenticated) {
        val isCallSurface = currentRoute == INCOMING_CALL_ROUTE ||
            currentRoute?.startsWith("$NEW_CALL_ROUTE/") == true ||
            currentRoute?.startsWith("$EXISTING_CALL_ROUTE/") == true
        if (
            gateState.isAuthenticated &&
            incomingCall != null &&
            !isCallSurface
        ) {
            navController.navigate(INCOMING_CALL_ROUTE) { launchSingleTop = true }
        }
    }

    LaunchedEffect(gateState.isReady, gateState.isAuthenticated, initialCallRequest) {
        if (gateState.isReady && gateState.isAuthenticated && initialCallRequest != null) {
            navController.navigate(existingCallRoute(
                initialCallRequest.pairId,
                initialCallRequest.direction,
                initialCallRequest.callId,
            )) {
                launchSingleTop = true
            }
            currentOnCallRequestConsumed(initialCallRequest.requestId)
        }
    }
}

private fun newCallRoute(pairId: String) = "$NEW_CALL_ROUTE/${android.net.Uri.encode(pairId)}"

private fun existingCallRoute(pairId: String, direction: CallDirection, callId: String) =
    "$EXISTING_CALL_ROUTE/${android.net.Uri.encode(pairId)}/${android.net.Uri.encode(callId)}?$DIRECTION_ARG=${direction.name}"

private fun HomeCallTarget.route(): String = when (this) {
    is HomeCallTarget.NewOutgoing -> newCallRoute(pairId)
    is HomeCallTarget.Existing -> existingCallRoute(pairId, direction, callId)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.partnerAvatarElement(
    transitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier = with(transitionScope) {
    sharedElement(
        state = rememberSharedContentState(PARTNER_AVATAR_SHARED_KEY),
        animatedVisibilityScope = animatedVisibilityScope,
    )
}
