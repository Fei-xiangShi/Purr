package life.fxs.purr.core.media.audio

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.AudioRoute
import org.junit.Test

class AudioRouteControllerTest {
    @Test
    fun `call defaults to earpiece when no preference exists`() {
        val route = resolveCallStartRoute(
            preferredRoute = null,
            availableRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
            currentRoute = AudioRoute.Speaker,
        )

        assertThat(route).isEqualTo(AudioRoute.Earpiece)
    }

    @Test
    fun `valid user preference remains authoritative`() {
        val route = resolveCallStartRoute(
            preferredRoute = AudioRoute.Speaker,
            availableRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
            currentRoute = AudioRoute.Earpiece,
        )

        assertThat(route).isEqualTo(AudioRoute.Speaker)
    }

    @Test
    fun `unavailable preference falls back to earpiece`() {
        val route = resolveCallStartRoute(
            preferredRoute = AudioRoute.Bluetooth,
            availableRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
            currentRoute = AudioRoute.Speaker,
        )

        assertThat(route).isEqualTo(AudioRoute.Earpiece)
    }

    @Test
    fun `device without earpiece keeps its current available route`() {
        val route = resolveCallStartRoute(
            preferredRoute = null,
            availableRoutes = listOf(AudioRoute.Bluetooth, AudioRoute.Speaker),
            currentRoute = AudioRoute.Bluetooth,
        )

        assertThat(route).isEqualTo(AudioRoute.Bluetooth)
    }
}
