package life.fxs.purr.feature.call

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

internal const val CALL_HISTORY_DATE_ARG = "date"
internal const val CALL_HISTORY_CALL_ID_ARG = "callId"

private const val CALENDAR_ROUTE = "calendar"
private const val DAY_ROUTE = "day/{$CALL_HISTORY_DATE_ARG}"
private const val DETAIL_ROUTE = "detail/{$CALL_HISTORY_CALL_ID_ARG}"
private const val NAV_MOTION_MILLIS = 300

@Composable
fun CallHistoryNavHost(
    onDownload: (RecordingDownloadRequest) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = CALENDAR_ROUTE,
        enterTransition = {
            slideInHorizontally(tween(NAV_MOTION_MILLIS, easing = FastOutSlowInEasing)) { it / 4 } +
                fadeIn(tween(NAV_MOTION_MILLIS))
        },
        exitTransition = {
            slideOutHorizontally(tween(NAV_MOTION_MILLIS, easing = FastOutSlowInEasing)) { -it / 6 } +
                fadeOut(tween(NAV_MOTION_MILLIS))
        },
        popEnterTransition = {
            slideInHorizontally(tween(NAV_MOTION_MILLIS, easing = FastOutSlowInEasing)) { -it / 6 } +
                fadeIn(tween(NAV_MOTION_MILLIS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(NAV_MOTION_MILLIS, easing = FastOutSlowInEasing)) { it / 4 } +
                fadeOut(tween(NAV_MOTION_MILLIS))
        },
    ) {
        composable(CALENDAR_ROUTE) { entry ->
            CallHistoryScreenRoute(
                onOpenDay = { date ->
                    if (navController.currentBackStackEntry == entry) {
                        navController.navigate("day/${date}") { launchSingleTop = true }
                    }
                },
            )
        }
        composable(
            route = DAY_ROUTE,
            arguments = listOf(navArgument(CALL_HISTORY_DATE_ARG) { type = NavType.StringType }),
        ) { entry ->
            CallDayScreenRoute(
                onBack = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
                onOpenCall = { callId ->
                    if (navController.currentBackStackEntry == entry) {
                        navController.navigate("detail/${Uri.encode(callId)}") { launchSingleTop = true }
                    }
                },
            )
        }
        composable(
            route = DETAIL_ROUTE,
            arguments = listOf(navArgument(CALL_HISTORY_CALL_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            CallDetailScreenRoute(
                onBack = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
                onDownload = onDownload,
            )
        }
    }
}
