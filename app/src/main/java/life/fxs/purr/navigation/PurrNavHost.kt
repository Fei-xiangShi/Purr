package life.fxs.purr.navigation

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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

    // Graph identity must remain stable when authentication changes.
    val startDestination = rememberSaveable { if (gateState.isAuthenticated) HOME_ROUTE else AUTH_ROUTE }
    var presentedIncomingCallId by rememberSaveable { mutableStateOf<String?>(null) }
    SharedTransitionLayout {
        val transitionScope = this
        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(AUTH_ROUTE) {
                AuthScreenRoute(
                    onLoginSuccess = {}, // The authenticated session owns the root transition.
                )
            }
            composable(HOME_ROUTE) { entry ->
                HomeScreenRoute(
                    onOpenCall = { target ->
                        if (navController.currentBackStackEntry == entry) navController.openCall(target.route())
                    },
                    onOpenCallHistory = {
                        if (navController.currentBackStackEntry == entry) {
                            navController.navigate(CALL_HISTORY_ROUTE) { launchSingleTop = true }
                        }
                    },
                    onOpenSettings = {
                        if (navController.currentBackStackEntry == entry) {
                            navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                        }
                    },
                    partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                )
            }
            composable(INCOMING_CALL_ROUTE) { entry ->
                BackHandler {
                    presentedIncomingCallId = gateState.incomingCall?.callId
                    navController.returnHomeFrom(entry)
                }
                val incomingCall = gateState.incomingCall
                if (incomingCall == null) {
                    LaunchedEffect(Unit) {
                        navController.returnHomeFrom(entry)
                    }
                } else {
                    IncomingCallPromptRoute(
                        partnerName = gateState.partner?.displayName ?: "对方",
                        partnerAvatarUrl = gateState.partner?.avatarUrl,
                        callId = incomingCall.callId,
                        avatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                        onOpenCall = { pairId, callId ->
                            if (navController.currentBackStackEntry == entry) {
                                navController.openCall(existingCallRoute(pairId, CallDirection.Incoming, callId))
                            }
                        },
                        onDismiss = {
                            navController.returnHomeFrom(entry)
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
                    LaunchedEffect(Unit) { navController.returnHomeFrom(backStackEntry) }
                } else {
                    CallScreenRoute(
                        request = CallPreparationRequest.NewOutgoing(pairId = pairId, recordingConsent = true),
                        partnerName = gateState.partner?.displayName ?: "对方",
                        partnerAvatarUrl = gateState.partner?.avatarUrl,
                        partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                        onCallSurfaceVisibilityChanged = onCallSurfaceVisibilityChanged,
                        onCallEnded = {
                            navController.returnHomeFrom(backStackEntry)
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
                    LaunchedEffect(Unit) { navController.returnHomeFrom(backStackEntry) }
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
                            navController.returnHomeFrom(backStackEntry)
                        },
                    )
                }
            }
            composable(CALL_HISTORY_ROUTE) {
                CallHistoryNavHost(onDownload = onRecordingDownload)
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreenRoute(
                    onLoggedOut = {}, // Root cleanup follows session state, including token expiry.
                )
            }
        }
    }

    LaunchedEffect(gateState.isAuthenticated) {
        if (!gateState.isAuthenticated) {
            presentedIncomingCallId = null
            navController.resetRoot(AUTH_ROUTE)
        } else if (navController.currentDestination?.route == AUTH_ROUTE) {
            navController.resetRoot(HOME_ROUTE)
        }
    }

    val incomingCall = gateState.incomingCall
    val currentRoute = currentEntry?.destination?.route
    LaunchedEffect(incomingCall?.callId, currentRoute, gateState.isAuthenticated, initialCallRequest) {
        val isCallSurface = currentRoute == INCOMING_CALL_ROUTE ||
            currentRoute?.startsWith("$NEW_CALL_ROUTE/") == true ||
            currentRoute?.startsWith("$EXISTING_CALL_ROUTE/") == true
        if (
            gateState.isAuthenticated &&
            incomingCall != null &&
            incomingCall.callId != presentedIncomingCallId &&
            initialCallRequest == null &&
            currentRoute != null && currentRoute != AUTH_ROUTE &&
            !isCallSurface
        ) {
            presentedIncomingCallId = incomingCall.callId
            navController.returnHome()
            navController.navigate(INCOMING_CALL_ROUTE) { launchSingleTop = true }
        }
    }

    LaunchedEffect(gateState.isReady, gateState.isAuthenticated, initialCallRequest) {
        if (gateState.isReady && gateState.isAuthenticated && initialCallRequest != null) {
            presentedIncomingCallId = initialCallRequest.callId
            navController.openCall(existingCallRoute(
                initialCallRequest.pairId,
                initialCallRequest.direction,
                initialCallRequest.callId,
            ))
            currentOnCallRequestConsumed(initialCallRequest.requestId)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.partnerAvatarElement(
    transitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier = with(transitionScope) {
    this@partnerAvatarElement.sharedElement(
        state = rememberSharedContentState(PARTNER_AVATAR_SHARED_KEY),
        animatedVisibilityScope = animatedVisibilityScope,
    )
}
