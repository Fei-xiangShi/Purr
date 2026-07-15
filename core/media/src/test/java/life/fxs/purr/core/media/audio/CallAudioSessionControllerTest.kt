package life.fxs.purr.core.media.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import life.fxs.purr.core.model.AudioRoute
import org.junit.Test

class CallAudioSessionControllerTest {
    @Test
    fun `activation acquires mode focus and default route in order`() = runBlocking {
        val harness = harness()

        harness.controller.activate()

        assertThat(harness.actions).containsExactly(
            "mode:activate",
            "focus:request",
            "route:default",
        ).inOrder()
        assertThat(harness.controller.state.value).isEqualTo(CallAudioSessionState.Active)
    }

    @Test
    fun `activation is idempotent once the session is active`() = runBlocking {
        val harness = harness()
        harness.controller.activate()
        harness.actions.clear()

        harness.controller.activate()

        assertThat(harness.actions).isEmpty()
        assertThat(harness.controller.state.value).isEqualTo(CallAudioSessionState.Active)
    }

    @Test
    fun `failed activation releases every acquired resource and returns to released`() = runBlocking {
        val harness = harness()
        val routeFailure = IllegalStateException("default route failed")
        harness.routeController.defaultFailure = routeFailure

        val result = runCatching { harness.controller.activate() }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(routeFailure)
        assertThat(harness.actions).containsExactly(
            "mode:activate",
            "focus:request",
            "route:default",
            "route:release",
            "focus:abandon",
            "mode:release",
        ).inOrder()
        assertThat(harness.controller.state.value).isEqualTo(CallAudioSessionState.Released)
    }

    @Test
    fun `release attempts every owned resource and aggregates failures`() = runBlocking {
        val harness = harness()
        harness.controller.activate()
        harness.actions.clear()
        val routeFailure = IllegalStateException("route cleanup failed")
        val focusFailure = IllegalStateException("focus cleanup failed")
        harness.routeController.releaseFailure = routeFailure
        harness.focusManager.abandonFailure = focusFailure

        val result = runCatching { harness.controller.release() }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(routeFailure)
        assertThat(routeFailure.suppressed.single()).isSameInstanceAs(focusFailure)
        assertThat(harness.actions).containsExactly(
            "route:release",
            "focus:abandon",
            "mode:release",
        ).inOrder()
        assertThat(harness.controller.state.value).isEqualTo(CallAudioSessionState.Released)
    }

    private fun harness(): Harness {
        val actions = mutableListOf<String>()
        val modeController = FakeModeController(actions)
        val focusManager = FakeFocusManager(actions)
        val routeController = FakeRouteController(actions)
        return Harness(
            actions = actions,
            focusManager = focusManager,
            routeController = routeController,
            controller = CoordinatedCallAudioSessionController(
                modeController = modeController,
                focusManager = focusManager,
                routeController = routeController,
            ),
        )
    }
}

private data class Harness(
    val actions: MutableList<String>,
    val focusManager: FakeFocusManager,
    val routeController: FakeRouteController,
    val controller: CoordinatedCallAudioSessionController,
)

private class FakeModeController(
    private val actions: MutableList<String>,
) : CallAudioModeController {
    override suspend fun activate() {
        actions += "mode:activate"
    }

    override suspend fun release() {
        actions += "mode:release"
    }
}

private class FakeFocusManager(
    private val actions: MutableList<String>,
) : CallAudioFocusManager {
    private val mutableState = MutableStateFlow(CallAudioFocusState.None)
    override val state: StateFlow<CallAudioFocusState> = mutableState
    var abandonFailure: Throwable? = null

    override suspend fun requestFocus(): Boolean {
        actions += "focus:request"
        mutableState.value = CallAudioFocusState.Granted
        return true
    }

    override suspend fun abandonFocus() {
        actions += "focus:abandon"
        abandonFailure?.let { throw it }
        mutableState.value = CallAudioFocusState.None
    }
}

private class FakeRouteController(
    private val actions: MutableList<String>,
) : AudioRouteController {
    override val availableRoutes = MutableStateFlow(listOf(AudioRoute.Earpiece))
    override val activeRoute = MutableStateFlow(AudioRoute.Earpiece)
    var defaultFailure: Throwable? = null
    var releaseFailure: Throwable? = null

    override suspend fun selectRoute(route: AudioRoute) {
        actions += "route:select:$route"
        activeRoute.value = route
    }

    override suspend fun selectDefaultRoute() {
        actions += "route:default"
        defaultFailure?.let { throw it }
    }

    override suspend fun releaseCallRoute() {
        actions += "route:release"
        releaseFailure?.let { throw it }
    }
}
