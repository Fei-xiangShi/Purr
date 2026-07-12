package life.fxs.purr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PurrApplicationTest {
    @Test
    fun applicationStartsWithExpectedPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("life.fxs.purr", context.packageName)
    }
}
