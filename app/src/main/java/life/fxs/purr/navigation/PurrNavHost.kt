package life.fxs.purr.navigation

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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import life.fxs.purr.feature.auth.AuthScreenRoute
import life.fxs.purr.feature.call.CallScreenRoute
import life.fxs.purr.feature.call.CallHistoryScreenRoute
import life.fxs.purr.feature.home.HomeScreenRoute
import life.fxs.purr.feature.settings.SettingsScreenRoute

private const val AUTH_ROUTE = "auth"
private const val HOME_ROUTE = "home"
private const val SETTINGS_ROUTE = "settings"
private const val CALL_ROUTE = "call"
private const val CALL_HISTORY_ROUTE = "call-history"
private const val PAIR_ID_ARG = "pairId"
private const val CALL_DESTINATION = "$CALL_ROUTE/{$PAIR_ID_ARG}"

@Composable
fun PurrNavHost(
    initialCallPairId: String? = null,
    initialCallRequestId: Long? = null,
    onCallRequestConsumed: (Long) -> Unit = {},
    viewModel: SessionGateViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val gateState by viewModel.state.collectAsStateWithLifecycle()
    val currentOnCallRequestConsumed by rememberUpdatedState(onCallRequestConsumed)

    if (!gateState.isReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (gateState.isAuthenticated) HOME_ROUTE else AUTH_ROUTE

    // Navigation Compose owns destination-level predictive back and seeks its transition from
    // gesture progress. Screens should not register competing back callbacks.
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
                    navController.navigate("$CALL_ROUTE/$pairId") {
                        launchSingleTop = true
                    }
                },
                onOpenCallHistory = { navController.navigate(CALL_HISTORY_ROUTE) },
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
            )
        }
        composable(
            route = CALL_DESTINATION,
            arguments = listOf(navArgument(PAIR_ID_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val pairId = backStackEntry.arguments?.getString(PAIR_ID_ARG).orEmpty()
            CallScreenRoute(
                pairId = pairId,
                onCallEnded = {
                    if (!navController.popBackStack()) {
                        navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(CALL_HISTORY_ROUTE) {
            CallHistoryScreenRoute()
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreenRoute(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(AUTH_ROUTE) {
                        popUpTo(HOME_ROUTE) { inclusive = true }
                    }
                },
            )
        }
    }

    val callRequest = initialCallPairId
        ?.takeIf(String::isNotBlank)
        ?.let {
            CallNavigationRequest(
                pairId = it,
                requestId = initialCallRequestId ?: 0L,
            )
        }

    LaunchedEffect(gateState.isReady, gateState.isAuthenticated, callRequest) {
        if (gateState.isReady && gateState.isAuthenticated && callRequest != null) {
            navController.navigate("$CALL_ROUTE/${callRequest.pairId}") {
                launchSingleTop = true
            }
            if (callRequest.requestId != 0L) {
                currentOnCallRequestConsumed(callRequest.requestId)
            }
        }
    }
}
