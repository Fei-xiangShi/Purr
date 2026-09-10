package life.fxs.purr.data.call.livekit

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.SystemCallInterruptionPhase
import org.junit.Test

class SystemCallInterruptionWireStateTest {
    @Test
    fun `version one state round trips without losing replay identity`() {
        val original = SystemCallInterruptionWireState(
            v = 1,
            callId = "call-1",
            senderGeneration = 42L,
            senderSessionId = "session-1",
            sequence = 7L,
            operationId = "operation-1",
            phase = "suspended",
            degraded = true,
        )

        val decoded = SystemCallInterruptionWireCodec.decode(
            SystemCallInterruptionWireCodec.encode(original),
        )

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded?.toPhase()).isEqualTo(SystemCallInterruptionPhase.Suspended)
    }

    @Test
    fun `unknown version invalid phase and oversized payload are ignored`() {
        val unknownVersion = """{"v":2,"callId":"call-1","senderGeneration":1,"senderSessionId":"s","sequence":1,"operationId":"o","phase":"active","degraded":false}"""
        val invalidPhase = unknownVersion.replace("\"v\":2", "\"v\":1")
            .replace("\"active\"", "\"unknown\"")
        val oversized = "x".repeat(SystemCallInterruptionWireCodec.MAX_VALUE_LENGTH + 1)

        assertThat(SystemCallInterruptionWireCodec.decode(unknownVersion)).isNull()
        assertThat(SystemCallInterruptionWireCodec.decode(invalidPhase)).isNull()
        assertThat(SystemCallInterruptionWireCodec.decode(oversized)).isNull()
    }
}
