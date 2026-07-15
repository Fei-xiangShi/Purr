package life.fxs.purr.platform.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingCallPushPayloadParserTest {
    private val parser = IncomingCallPushPayloadParser()

    @Test
    fun `fresh incoming call wake signal is accepted`() {
        val parsed = parser.parse(
            data = payload(startedAtEpochMillis = NOW - 1_000L),
            nowEpochMillis = NOW,
        )

        assertThat(parsed).isEqualTo(IncomingCallWakeSignal(CALL_ID, NOW - 1_000L))
    }

    @Test
    fun `stale future malformed and unrelated payloads are rejected`() {
        assertThat(parser.parse(payload(NOW - 90_001L), NOW)).isNull()
        assertThat(parser.parse(payload(NOW + 30_001L), NOW)).isNull()
        assertThat(parser.parse(payload(NOW) + ("callId" to "invalid/call"), NOW)).isNull()
        assertThat(parser.parse(payload(NOW) + ("type" to "presence"), NOW)).isNull()
        assertThat(parser.parse(payload(NOW) - "startedAtEpochMillis", NOW)).isNull()
    }

    private fun payload(startedAtEpochMillis: Long) = mapOf(
        "type" to "incoming_call",
        "callId" to CALL_ID,
        "startedAtEpochMillis" to startedAtEpochMillis.toString(),
    )

    private companion object {
        const val NOW = 1_752_580_800_000L
        const val CALL_ID = "call-123"
    }
}
