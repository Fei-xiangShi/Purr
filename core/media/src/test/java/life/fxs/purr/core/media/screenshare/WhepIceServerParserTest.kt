package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers
import org.junit.Test

class WhepIceServerParserTest {
    @Test
    fun `parses STUN and authenticated TURN links from WHEP options`() {
        val headers = Headers.Builder()
            .add("Link", "<stun:stun.example.com:3478>; rel=\"ice-server\"")
            .add(
                "Link",
                "<turns:turn.example.com:5349?transport=tcp>; rel=\"ice-server\"; " +
                    "username=\"purr-user\"; credential=\"purr-secret\"",
            )
            .build()

        val servers = headers.toIceServers()

        assertThat(servers).hasSize(2)
        assertThat(servers[0].urls).containsExactly("stun:stun.example.com:3478")
        assertThat(servers[1].urls)
            .containsExactly("turns:turn.example.com:5349?transport=tcp")
        assertThat(servers[1].username).isEqualTo("purr-user")
        assertThat(servers[1].password).isEqualTo("purr-secret")
    }

    @Test
    fun `ignores unrelated link relations`() {
        val headers = Headers.Builder()
            .add("Link", "<https://media.test/help>; rel=\"alternate\"")
            .build()

        assertThat(headers.toIceServers()).isEmpty()
    }
}
