package life.fxs.purr.di

import com.google.common.truth.Truth.assertThat
import okhttp3.Authenticator
import org.junit.Test

class MediaTransportClientTest {
    @Test
    fun `WHEP client cannot overwrite media token with API authentication`() {
        val client = MediaModule.provideMediaTransportClient()

        assertThat(client.interceptors).isEmpty()
        assertThat(client.networkInterceptors).isEmpty()
        assertThat(client.authenticator).isSameInstanceAs(Authenticator.NONE)
    }
}
