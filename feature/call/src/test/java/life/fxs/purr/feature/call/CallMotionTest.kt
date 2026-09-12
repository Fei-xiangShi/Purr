package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallMotionTest {
    @Test
    fun `display cadence is at least sixty frames per second`() {
        assertThat(CALL_UI_FRAME_INTERVAL_MILLIS).isAtMost(1_000 / 60)
    }

    @Test
    fun `chart time cursor follows monotonic time without preceding latest sample`() {
        assertThat(chartTimeCursorMillis(latestSampleTimeMillis = 1_000L, nowMillis = 1_040L))
            .isEqualTo(1_040L)
        assertThat(chartTimeCursorMillis(latestSampleTimeMillis = 1_050L, nowMillis = 1_040L))
            .isEqualTo(1_050L)
    }

    @Test
    fun `chart visibility is bounded by timestamp window`() {
        val cursor = 62_500.0

        assertThat(isChartSampleVisible(2_500L, cursor)).isTrue()
        assertThat(isChartSampleVisible(2_499L, cursor)).isFalse()
        assertThat(isChartSampleVisible(62_501L, cursor)).isFalse()
    }

    @Test
    fun `chart path breaks across missing samples`() {
        assertThat(shouldBreakChartPath(previousTimeMillis = 1_000L, currentTimeMillis = 1_050L))
            .isFalse()
        assertThat(shouldBreakChartPath(previousTimeMillis = 1_000L, currentTimeMillis = 4_001L))
            .isTrue()
        assertThat(shouldBreakChartPath(previousTimeMillis = 1_050L, currentTimeMillis = 1_000L))
            .isTrue()
    }

    @Test
    fun `network chart uses the same bounded sample capacity as its time window`() {
        assertThat(NETWORK_CHART_SAMPLE_CAPACITY).isEqualTo(150)
        assertThat(NETWORK_CHART_WINDOW_MILLIS).isGreaterThan(0L)
    }
}
