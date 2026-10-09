package life.fxs.purr.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallForegroundServiceTypesTest {
    private val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun `api 30 plus requests phoneCall and microphone`() {
        val types = mutableListOf<Int?>()
        CallForegroundServiceTypes.start(Build.VERSION_CODES.R) { types += it }
        assertThat(types).containsExactly(both)
    }

    @Test
    fun `security exception falls back to microphone only`() {
        val types = mutableListOf<Int?>()
        CallForegroundServiceTypes.start(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types += it
            if (it == both) throw SecurityException("denied")
        }
        assertThat(types).containsExactly(both, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE).inOrder()
    }

    @Test
    fun `fallback failure propagates to the outer handler`() {
        var attempts = 0
        try {
            CallForegroundServiceTypes.start(Build.VERSION_CODES.R) { attempts++; throw SecurityException("denied") }
            error("expected failure")
        } catch (_: SecurityException) {
        }
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `api 29 uses phoneCall only`() {
        val types = mutableListOf<Int?>()
        CallForegroundServiceTypes.start(Build.VERSION_CODES.Q) { types += it }
        assertThat(types).containsExactly(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }
}
