package life.fxs.purr.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.CallDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PurrNavigationTest {
    @Test fun `expired incoming prompt is removed instead of pushed below a new home`() {
        val nav = controller()
        nav.navigate(INCOMING_CALL_ROUTE)
        val incoming = nav.currentBackStackEntry!!
        nav.returnHomeFrom(incoming)
        assertThat(nav.currentDestination?.route).isEqualTo(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
        // A delayed dismissal from the old prompt cannot close the new page.
        nav.navigate(SETTINGS_ROUTE)
        nav.returnHomeFrom(incoming)
        assertThat(nav.currentDestination?.route).isEqualTo(SETTINGS_ROUTE)
    }

    @Test fun `notification replaces obsolete surfaces and repeated exact call preserves its entry`() {
        val nav = controller()
        nav.navigate(SETTINGS_ROUTE)
        nav.navigate(INCOMING_CALL_ROUTE)
        val route = existingCallRoute("pair / 1", CallDirection.Incoming, "call / 2")
        nav.openCall(route)
        val entry = nav.currentBackStackEntry
        assertThat(nav.previousBackStackEntry?.destination?.route).isEqualTo(HOME_ROUTE)
        nav.openCall(route)
        assertThat(nav.currentBackStackEntry).isSameInstanceAs(entry)
        assertThat(entry?.arguments?.getString(CALL_ID_ARG)).isEqualTo("call / 2")
        assertThat(entry?.arguments?.getString(DIRECTION_ARG)).isEqualTo("Incoming")
        nav.returnHomeFrom(entry!!)
        assertThat(nav.previousBackStackEntry).isNull()
    }

    @Test fun `call replacement ignores old termination callback and back goes straight home`() {
        val nav = controller()
        nav.openCall(newCallRoute("pair"))
        val old = nav.currentBackStackEntry!!
        nav.openCall(existingCallRoute("pair", CallDirection.Outgoing, "call"))
        nav.returnHomeFrom(old)
        assertThat(nav.currentDestination?.route).isEqualTo(EXISTING_CALL_DESTINATION)
        assertThat(nav.popBackStack()).isTrue()
        assertThat(nav.currentDestination?.route).isEqualTo(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
    }

    @Test fun `logout clears all history and fallback restores one home if root is missing`() {
        val nav = controller()
        nav.navigate(SETTINGS_ROUTE)
        nav.openCall(newCallRoute("pair"))
        nav.resetRoot(AUTH_ROUTE)
        assertThat(nav.currentDestination?.route).isEqualTo(AUTH_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
        nav.resetRoot(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
        nav.resetRoot(AUTH_ROUTE)
        nav.navigate(INCOMING_CALL_ROUTE)
        nav.returnHome()
        assertThat(nav.currentDestination?.route).isEqualTo(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
    }

    @Test fun `return home repairs duplicate roots saved by previous releases`() {
        val nav = controller()
        nav.navigate(INCOMING_CALL_ROUTE)
        nav.navigate(HOME_ROUTE)
        nav.returnHome()
        assertThat(nav.currentDestination?.route).isEqualTo(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
        nav.navigate(INCOMING_CALL_ROUTE)
        nav.navigate(HOME_ROUTE)
        nav.openCall(newCallRoute("pair"))
        nav.popBackStack()
        assertThat(nav.currentDestination?.route).isEqualTo(HOME_ROUTE)
        assertThat(nav.previousBackStackEntry).isNull()
    }

    private fun controller() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = HOME_ROUTE) {
            composable(AUTH_ROUTE) {}
            composable(HOME_ROUTE) {}
            composable(SETTINGS_ROUTE) {}
            composable(INCOMING_CALL_ROUTE) {}
            composable(NEW_CALL_DESTINATION, arguments = listOf(navArgument(PAIR_ID_ARG) { type = NavType.StringType })) {}
            composable(EXISTING_CALL_DESTINATION, arguments = listOf(
                navArgument(PAIR_ID_ARG) { type = NavType.StringType },
                navArgument(CALL_ID_ARG) { type = NavType.StringType },
                navArgument(DIRECTION_ARG) { type = NavType.StringType },
            )) {}
        }
    }
}
