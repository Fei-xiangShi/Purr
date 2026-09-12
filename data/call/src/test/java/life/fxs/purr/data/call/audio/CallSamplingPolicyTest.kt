package life.fxs.purr.data.call.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallSamplingPolicyTest {
    @Test
    fun `healthy sampler keeps its scheduled cadence`() {
        assertThat(
            nextSampleAtMillis(
                scheduledAtMillis = 1_000L,
                nowMillis = 1_005L,
                intervalMillis = WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS,
            ),
        ).isEqualTo(2_000L)
    }

    @Test
    fun `rtc stats use one second intervals independently of audio ticks`() {
        assertThat(WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS).isEqualTo(1_000L)
        assertThat(
            nextSampleAtMillis(
                scheduledAtMillis = 1_000L,
                nowMillis = 1_048L,
                intervalMillis = WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS,
            ),
        ).isEqualTo(2_000L)
    }

    @Test
    fun `slow sampler skips missed deadlines and avoids busy looping`() {
        assertThat(
            nextSampleAtMillis(
                scheduledAtMillis = 1_000L,
                nowMillis = 2_100L,
                intervalMillis = WEBRTC_STATS_SAMPLE_INTERVAL_MILLIS,
            ),
        ).isEqualTo(3_100L)
    }
}
