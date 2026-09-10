package life.fxs.purr.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PurrSentryTest {
    @Test
    fun `call reference is deterministic and never contains the raw call id`() {
        val first = PurrSentry.callReference("business-call-123")
        val second = PurrSentry.callReference("business-call-123")

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(16)
        assertThat(first).doesNotContain("business-call-123")
    }

    @Test
    fun `sanitizer removes call ids credentials phone values sdp and interruption payloads`() {
        val raw = """
            callId=business-call-123 Authorization=Bearer-secret token=abc123
            phone=+86 138 0013 8000
            a=ice-pwd:super-secret
            life.fxs.purr.call.interruption={"callId":"business-call-123","operationId":"op"}
        """.trimIndent()

        val sanitized = PurrSentry.sanitizeForSentry(raw)

        assertThat(sanitized).contains("call_ref=${PurrSentry.callReference("business-call-123")}")
        assertThat(sanitized).doesNotContain("business-call-123")
        assertThat(sanitized).doesNotContain("Bearer-secret")
        assertThat(sanitized).doesNotContain("abc123")
        assertThat(sanitized).doesNotContain("138 0013 8000")
        assertThat(sanitized).doesNotContain("super-secret")
        assertThat(sanitized).doesNotContain("operationId")
    }
}
