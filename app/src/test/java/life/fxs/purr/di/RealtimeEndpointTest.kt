package life.fxs.purr.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RealtimeEndpointTest {
    @Test
    fun `https API uses secure WebSocket`() {
        assertThat(realtimeEndpoint("https://api.purr.fxs.life/"))
            .isEqualTo("wss://api.purr.fxs.life/realtime")
    }

    @Test
    fun `http API uses WebSocket`() {
        assertThat(realtimeEndpoint("http://10.0.2.2:8080/"))
            .isEqualTo("ws://10.0.2.2:8080/realtime")
    }

    @Test
    fun `base path is preserved`() {
        assertThat(realtimeEndpoint("https://example.test/api/"))
            .isEqualTo("wss://example.test/api/realtime")
    }
}
