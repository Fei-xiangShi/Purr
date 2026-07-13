package life.fxs.purr.data.call.mapper

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.network.model.CallStatusDto
import life.fxs.purr.domain.call.model.CallTiming
import life.fxs.purr.domain.call.model.RecordingState
import org.junit.Test

class CallStatusMapperTest {
    @Test
    fun `server duration is authoritative for active calls`() {
        val timing = status(
            state = "active",
            durationMillis = 12_000L,
        ).toCallTiming(
            synchronizedAtMonotonicMillis = 50_000L,
            clientNowEpochMillis = 999_999L,
        )

        assertThat(timing.synchronizedDurationMillis).isEqualTo(12_000L)
        assertThat(timing.synchronizedAtMonotonicMillis).isEqualTo(50_000L)
        assertThat(timing.isRunning).isTrue()
    }

    @Test
    fun `ended call duration is derived from server endpoints when duration is absent`() {
        val timing = status(
            state = "ended",
            endedAtEpochMillis = 16_000L,
        ).toCallTiming(
            synchronizedAtMonotonicMillis = 50_000L,
            clientNowEpochMillis = 999_999L,
        )

        assertThat(timing.synchronizedDurationMillis).isEqualTo(6_000L)
        assertThat(timing.isRunning).isFalse()
    }

    @Test
    fun `waiting call has no timing even if legacy server sends a creation timestamp`() {
        val timing = status(state = "idle").toCallTiming()

        assertThat(timing).isEqualTo(CallTiming())
    }

    @Test
    fun `unknown recording state is represented as failure`() {
        assertThat("unexpected".toRecordingState()).isInstanceOf(RecordingState.Failed::class.java)
    }

    private fun status(
        state: String,
        endedAtEpochMillis: Long? = null,
        durationMillis: Long? = null,
    ) = CallStatusDto(
        callId = "call-1",
        pairId = "pair-1",
        state = state,
        recordingStatus = "idle",
        startedAtEpochMillis = 10_000L,
        endedAtEpochMillis = endedAtEpochMillis,
        durationMillis = durationMillis,
    )
}
