package life.fxs.purr.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.feature.home.HomeCallTarget

internal const val AUTH_ROUTE = "auth"
internal const val HOME_ROUTE = "home"
internal const val SETTINGS_ROUTE = "settings"
internal const val INCOMING_CALL_ROUTE = "incoming-call"
internal const val NEW_CALL_ROUTE = "new-call"
internal const val EXISTING_CALL_ROUTE = "existing-call"
internal const val CALL_HISTORY_ROUTE = "call-history"
internal const val PAIR_ID_ARG = "pairId"
internal const val DIRECTION_ARG = "direction"
internal const val CALL_ID_ARG = "callId"
internal const val NEW_CALL_DESTINATION = "$NEW_CALL_ROUTE/{$PAIR_ID_ARG}"
internal const val EXISTING_CALL_DESTINATION =
    "$EXISTING_CALL_ROUTE/{$PAIR_ID_ARG}/{$CALL_ID_ARG}?$DIRECTION_ARG={$DIRECTION_ARG}"

/** Clear obsolete authenticated/unauthenticated destinations, including restored history. */
internal fun NavController.resetRoot(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = false }
        launchSingleTop = true
    }
}

internal fun NavController.returnHome() {
    if (currentDestination?.route != HOME_ROUTE && !popBackStack(HOME_ROUTE, inclusive = false)) {
        resetRoot(HOME_ROUTE)
        return
    }
    // Old releases could save [home, incoming, home]. Repair that history too.
    if (previousBackStackEntry != null) resetRoot(HOME_ROUTE)
}

/** Outgoing screens can finish after another destination is already active. */
internal fun NavController.returnHomeFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry == entry) returnHome()
}

internal fun NavController.openCall(route: String) {
    // Repeated notifications must retain the current ViewModel and WHEP attempt.
    if (currentBackStackEntry?.let { entry ->
        when (entry.destination.route) {
            NEW_CALL_DESTINATION -> newCallRoute(entry.arguments?.getString(PAIR_ID_ARG).orEmpty()) == route
            EXISTING_CALL_DESTINATION -> entry.arguments?.let { args ->
                val direction = args.getString(DIRECTION_ARG)
                    ?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() }
                direction != null && existingCallRoute(args.getString(PAIR_ID_ARG).orEmpty(),
                    direction, args.getString(CALL_ID_ARG).orEmpty()) == route
            } == true
            else -> false
        }
    } == true) return
    returnHome()
    navigate(route) { launchSingleTop = true }
}

internal fun newCallRoute(pairId: String) = "$NEW_CALL_ROUTE/${android.net.Uri.encode(pairId)}"

internal fun existingCallRoute(pairId: String, direction: CallDirection, callId: String) =
    "$EXISTING_CALL_ROUTE/${android.net.Uri.encode(pairId)}/${android.net.Uri.encode(callId)}?$DIRECTION_ARG=${direction.name}"

internal fun HomeCallTarget.route(): String = when (this) {
    is HomeCallTarget.NewOutgoing -> newCallRoute(pairId)
    is HomeCallTarget.Existing -> existingCallRoute(pairId, direction, callId)
}

