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

private const val AUTH_ROUTE = "auth"
private const val HOME_ROUTE = "home"
private const val SETTINGS_ROUTE = "settings"
private const val INCOMING_CALL_ROUTE = "incoming-call"
private const val CALL_ROUTE = "call"
private const val CALL_HISTORY_ROUTE = "call-history"
private const val PAIR_ID_ARG = "pairId"
private const val CALL_DESTINATION = "$CALL_ROUTE/{$PAIR_ID_ARG}"
private const val PARTNER_AVATAR_SHARED_KEY = "partner-avatar"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PurrNavHost(
    initialCallPairId: String? = null,
    initialCallRequestId: Long? = null,
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
                    onOpenCall = { pairId ->
                        navController.navigate("$CALL_ROUTE/$pairId") { launchSingleTop = true }
                    },
                    onOpenCallHistory = { navController.navigate(CALL_HISTORY_ROUTE) },
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    partnerAvatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                )
            }
            composable(INCOMING_CALL_ROUTE) {
                IncomingCallPromptRoute(
                    partnerName = gateState.partner?.displayName ?: "对方",
                    partnerAvatarUrl = gateState.partner?.avatarUrl,
                    avatarModifier = Modifier.partnerAvatarElement(transitionScope, this),
                    onOpenCall = { pairId ->
                        navController.navigate("$CALL_ROUTE/$pairId") {
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
            composable(
                route = CALL_DESTINATION,
                arguments = listOf(navArgument(PAIR_ID_ARG) { type = NavType.StringType }),
            ) { backStackEntry ->
                val pairId = backStackEntry.arguments?.getString(PAIR_ID_ARG).orEmpty()
                CallScreenRoute(
                    pairId = pairId,
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
        if (
            gateState.isAuthenticated &&
            incomingCall != null &&
            currentRoute != INCOMING_CALL_ROUTE &&
            currentRoute != CALL_DESTINATION
        ) {
            navController.navigate(INCOMING_CALL_ROUTE) { launchSingleTop = true }
        }
    }

    val callRequest = initialCallPairId
        ?.takeIf(String::isNotBlank)
        ?.let { CallNavigationRequest(it, initialCallRequestId ?: 0L) }
    LaunchedEffect(gateState.isReady, gateState.isAuthenticated, callRequest) {
        if (gateState.isReady && gateState.isAuthenticated && callRequest != null) {
            navController.navigate("$CALL_ROUTE/${callRequest.pairId}") { launchSingleTop = true }
            if (callRequest.requestId != 0L) currentOnCallRequestConsumed(callRequest.requestId)
        }
    }
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
