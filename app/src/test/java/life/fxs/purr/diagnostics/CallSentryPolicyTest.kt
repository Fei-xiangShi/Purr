package life.fxs.purr.diagnostics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class CallSentryPolicyTest {
    @Test fun `superseded or user cancelled interruption transactions are not failures`() {
        assertThat(interruptionSpanStatus("superseded")).isEqualTo(io.sentry.SpanStatus.CANCELLED)
        assertThat(interruptionSpanStatus("cancelled")).isEqualTo(io.sentry.SpanStatus.CANCELLED)
        assertThat(interruptionSpanStatus("terminal_failure")).isEqualTo(io.sentry.SpanStatus.INTERNAL_ERROR)
    }
    @Test fun `cancel and repeated retry attempts are not incidents`() {
        assertThat(shouldReportHandledError("CallRuntime", CancellationException(), "phase=runtime.connect event=error")).isFalse()
        assertThat(shouldReportHandledError("CallLiveKit", IllegalStateException(), "phase=livekit.interruption event=resume_failed")).isFalse()
        assertThat(shouldReportHandledError("CallLifecycle", IllegalStateException(), "phase=server.end event=retry")).isFalse()
        assertThat(shouldReportHandledError("CallTelemetry", IllegalStateException(), "event=sample_error")).isFalse()
    }
    @Test fun `only final connection boundary and actual cleanup failures become incidents`() {
        val error = IllegalStateException()
        assertThat(shouldReportHandledError("CallLiveKit", error, "phase=livekit.connect event=error")).isFalse()
        assertThat(shouldReportHandledError("CallRuntime", error, "phase=runtime.connect event=error")).isTrue()
        assertThat(shouldReportHandledError("CallLiveKit", error, "phase=livekit.release event=error")).isTrue()
        assertThat(shouldReportHandledError("CallLifecycle", error, "phase=local.release event=error")).isTrue()
    }
    @Test fun `media urls srt secrets and browser fragments never reach sentry`() {
        val result = PurrSentry.sanitizeForSentry("srt://host:8890?streamid=publish:path:secret&passphrase=srt-key https://host/watch#token=read-key bearerToken=whip-key passphrase=plain-key")
        listOf("srt-key", "read-key", "whip-key", "plain-key", "publish:path").forEach {
            assertThat(result).doesNotContain(it)
        }
    }
}
