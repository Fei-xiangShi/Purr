package life.fxs.purr.domain.call.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallTimingTest {
    @Test
    fun `running timing interpolates from the server synchronized duration`() {
        val timing = CallTiming(
            startedAtEpochMillis = 10_000L,
            synchronizedDurationMillis = 8_000L,
            synchronizedAtMonotonicMillis = 100_000L,
            isRunning = true,
        )

        assertThat(timing.durationAtMonotonicMillis(102_500L)).isEqualTo(10_500L)
    }

    @Test
    fun `ended timing remains frozen`() {
        val timing = CallTiming(
            startedAtEpochMillis = 10_000L,
            endedAtEpochMillis = 18_000L,
            synchronizedDurationMillis = 8_000L,
            synchronizedAtMonotonicMillis = 100_000L,
            isRunning = false,
        )

        assertThat(timing.durationAtMonotonicMillis(999_000L)).isEqualTo(8_000L)
    }

    @Test
    fun `monotonic clock rollback cannot reduce synchronized duration`() {
        val timing = CallTiming(
            synchronizedDurationMillis = 8_000L,
            synchronizedAtMonotonicMillis = 100_000L,
            isRunning = true,
        )

        assertThat(timing.durationAtMonotonicMillis(99_000L)).isEqualTo(8_000L)
    }
}
