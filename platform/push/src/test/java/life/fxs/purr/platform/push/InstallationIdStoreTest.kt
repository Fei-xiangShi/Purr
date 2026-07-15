package life.fxs.purr.platform.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstallationIdStoreTest {
    @Test
    fun `installation id is stable across store recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val first = SharedPreferencesInstallationIdStore(context).getOrCreate()
        val second = SharedPreferencesInstallationIdStore(context).getOrCreate()

        assertThat(second).isEqualTo(first)
        assertThat(isValidInstallationId(first)).isTrue()
    }
}
