package life.fxs.purr.core.media.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import life.fxs.purr.core.model.AudioRoute
import org.junit.Test

class CallAudioSessionControllerTest {
    @Test
    fun `activation acquires mode focus and route in order`() = runBlocking {
        val harness = harness(AudioRoute.Earpiece)

        harness.controller.activate()

        assertThat(harness.actions).containsExactly(
            "mode:Conversational",
            "focus:Conversational",
            "route:restore",
        ).inOrder()
        assertThat(harness.controller.state.value)
            .isEqualTo(CallAudioSessionState.Active(CallAudioProfile.Conversational))
    }

    @Test
    fun `listen-only is restricted to an active bluetooth route`() = runBlocking {
        val harness = harness(AudioRoute.Earpiece)
        harness.controller.activate()
        harness.actions.clear()

        val outcome = harness.controller.transitionTo(CallAudioProfile.ListenOnly)

        assertThat(outcome).isEqualTo(CallAudioTransitionOutcome.NotApplicable)
        assertThat(harness.actions).isEmpty()
        assertThat(harness.controller.state.value)
            .isEqualTo(CallAudioSessionState.Active(CallAudioProfile.Conversational))
    }

    @Test
    fun `failed listen-only transition rolls back to conversational mode`() = runBlocking {
        val harness = harness(AudioRoute.Bluetooth)
        harness.controller.activate()
        harness.actions.clear()
        harness.modeController.failures += CallAudioProfile.ListenOnly

        val outcome = harness.controller.transitionTo(CallAudioProfile.ListenOnly)

        assertThat(outcome).isInstanceOf(CallAudioTransitionOutcome.Recovered::class.java)
        assertThat(harness.actions).containsExactly(
            "route:release",
            "mode:ListenOnly",
            "mode:Conversational",
            "focus:Conversational",
            "route:restore",
        ).inOrder()
        assertThat(harness.controller.state.value).isInstanceOf(CallAudioSessionState.Degraded::class.java)
        assertThat(harness.controller.state.value.currentProfile)
            .isEqualTo(CallAudioProfile.Conversational)
    }

    @Test
    fun `failed state must reapply its last known profile before becoming active`() = runBlocking {
        val harness = harness(AudioRoute.Bluetooth)
        harness.controller.activate()
        harness.actions.clear()
        harness.modeController.failures += CallAudioProfile.ListenOnly
        harness.modeController.failures += CallAudioProfile.Conversational
        val failed = harness.controller.transitionTo(CallAudioProfile.ListenOnly)
        assertThat(failed).isInstanceOf(CallAudioTransitionOutcome.Failed::class.java)
        harness.actions.clear()

        val recovered = harness.controller.transitionTo(CallAudioProfile.Conversational)

        assertThat(recovered).isEqualTo(CallAudioTransitionOutcome.Applied)
        assertThat(harness.actions).containsExactly(
            "mode:Conversational",
            "focus:Conversational",
            "route:restore",
        ).inOrder()
        assertThat(harness.controller.state.value)
            .isEqualTo(CallAudioSessionState.Active(CallAudioProfile.Conversational))
    }

    @Test
    fun `lost focus forces the active profile to be reapplied`() = runBlocking {
        val harness = harness(AudioRoute.Earpiece)
        harness.controller.activate()
        harness.actions.clear()
        harness.focusManager.loseFocus()

        val outcome = harness.controller.transitionTo(CallAudioProfile.Conversational)

        assertThat(outcome).isEqualTo(CallAudioTransitionOutcome.Applied)
        assertThat(harness.actions).containsExactly(
            "mode:Conversational",
            "focus:Conversational",
            "route:restore",
        ).inOrder()
    }

    @Test
    fun `release attempts every owned resource and aggregates failures`() = runBlocking {
        val harness = harness(AudioRoute.Bluetooth)
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

    private fun harness(route: AudioRoute): Harness {
        val actions = mutableListOf<String>()
        val modeController = FakeModeController(actions)
        val focusManager = FakeFocusManager(actions)
        val routeController = FakeRouteController(actions, route)
        return Harness(
            actions = actions,
            modeController = modeController,
            focusManager = focusManager,
            routeController = routeController,
            controller = CoordinatedCallAudioSessionController(
                modeController = modeController,
                focusManager = focusManager,
                routeController = routeController,
                listenOnlyEligibility = BluetoothListenOnlyEligibility(),
            ),
        )
    }
}

private data class Harness(
    val actions: MutableList<String>,
    val modeController: FakeModeController,
    val focusManager: FakeFocusManager,
    val routeController: FakeRouteController,
    val controller: CoordinatedCallAudioSessionController,
)

private class FakeModeController(
    private val actions: MutableList<String>,
) : CallAudioModeController {
    val failures = mutableListOf<CallAudioProfile>()

    override suspend fun apply(profile: CallAudioProfile) {
        actions += "mode:$profile"
        if (failures.firstOrNull() == profile) {
            failures.removeAt(0)
            error("mode transition failed")
        }
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

    fun loseFocus() {
        mutableState.value = CallAudioFocusState.Lost
    }

    override suspend fun requestFocus(profile: CallAudioProfile): Boolean {
        actions += "focus:$profile"
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
    initialRoute: AudioRoute,
) : AudioRouteController {
    override val availableRoutes = MutableStateFlow(listOf(initialRoute))
    override val activeRoute = MutableStateFlow(initialRoute)
    var releaseFailure: Throwable? = null

    override suspend fun selectRoute(route: AudioRoute) {
        actions += "route:select:$route"
        activeRoute.value = route
    }

    override suspend fun restorePreferredRoute() {
        actions += "route:restore"
    }

    override suspend fun releaseCallRoute() {
        actions += "route:release"
        releaseFailure?.let { throw it }
    }
}
