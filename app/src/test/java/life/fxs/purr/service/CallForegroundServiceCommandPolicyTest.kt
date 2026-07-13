package life.fxs.purr.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallForegroundServiceCommandPolicyTest {
    @Test
    fun `hang up is accepted only for current non blank call`() {
        assertThat(
            CallForegroundServiceCommandPolicy.acceptsHangUp(
                requestedCallId = "call-1",
                activeCallId = "call-1",
            ),
        ).isTrue()
    }

    @Test
    fun `stale or malformed hang up is rejected`() {
        assertThat(
            CallForegroundServiceCommandPolicy.acceptsHangUp("call-old", "call-new"),
        ).isFalse()
        assertThat(
            CallForegroundServiceCommandPolicy.acceptsHangUp(null, "call-new"),
        ).isFalse()
        assertThat(
            CallForegroundServiceCommandPolicy.acceptsHangUp("", "call-new"),
        ).isFalse()
        assertThat(
            CallForegroundServiceCommandPolicy.acceptsHangUp("call-new", null),
        ).isFalse()
    }

    @Test
    fun `pending intent identity is scoped to one call`() {
        val oldCall = CallForegroundServiceCommandPolicy.pendingIntentIdentifier(
            command = "hang_up",
            callId = "call-old",
        )
        val newCall = CallForegroundServiceCommandPolicy.pendingIntentIdentifier(
            command = "hang_up",
            callId = "call-new",
        )

        assertThat(oldCall).isNotEqualTo(newCall)
    }
}
