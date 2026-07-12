package life.fxs.purr.config

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.BuildConfig
import org.junit.Test

class ReleaseConfigTest {
    @Test
    fun `release uses the production API subdomain`() {
        assertThat(BuildConfig.PURR_BASE_URL).isEqualTo("https://api.purr.fxs.life/")
        assertThat(AppConfig.requireBaseUrl()).isEqualTo(BuildConfig.PURR_BASE_URL)
    }
}
