package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenShareBitrateControllerTest {
    @Test
    fun `empty queues on a static screen never imply insufficient bandwidth`() {
        val controller = ScreenShareBitrateController(16_000_000)
        repeat(100) { controller.sample(it * 200L, 0, 28, 0, 0) }
        assertThat(controller.bitrate).isEqualTo(16_000_000)
    }

    @Test
    fun `brief keyframe bursts do not reduce quality`() {
        val controller = ScreenShareBitrateController(16_000_000)
        controller.sample(0, 16, 28, 0, 0)
        controller.sample(200, 0, 28, 0, 0)
        controller.sample(400, 16, 28, 0, 0)
        controller.sample(600, 0, 28, 0, 0)
        assertThat(controller.bitrate).isEqualTo(16_000_000)
    }

    @Test
    fun `sustained backpressure decreases quickly but at most once per second`() {
        val controller = ScreenShareBitrateController(16_000_000)
        controller.sample(0, 10, 28, 0, 0)
        controller.sample(200, 10, 28, 0, 0)
        assertThat(controller.bitrate).isEqualTo(16_000_000)
        controller.sample(400, 10, 28, 0, 0)
        assertThat(controller.bitrate).isEqualTo(12_000_000)
        controller.sample(600, 10, 28, 0, 0)
        assertThat(controller.bitrate).isEqualTo(12_000_000)
        controller.sample(1_400, 10, 28, 0, 0)
        assertThat(controller.bitrate).isEqualTo(9_000_000)
    }

    @Test
    fun `video drops request recovery without a keyframe storm`() {
        val controller = ScreenShareBitrateController(16_000_000)
        assertThat(controller.sample(0, 20, 28, 1, 0).requestKeyframe).isFalse()
        assertThat(controller.sample(200, 0, 28, 1, 0).requestKeyframe).isTrue()
        assertThat(controller.sample(400, 0, 28, 2, 0).requestKeyframe).isFalse()
        assertThat(controller.sample(1_000, 0, 28, 3, 0).requestKeyframe).isFalse()
        assertThat(controller.sample(1_200, 0, 28, 3, 0).requestKeyframe).isTrue()
        assertThat(controller.sample(2_000, 0, 28, 3, 0).requestKeyframe).isFalse()
    }

    @Test
    fun `audio drops lower video budget without requesting a video keyframe`() {
        val controller = ScreenShareBitrateController(16_000_000)
        val decision = controller.sample(0, 0, 28, 0, 1)
        assertThat(decision.bitrate).isEqualTo(12_000_000)
        assertThat(decision.requestKeyframe).isFalse()
    }

    @Test
    fun `recovery waits for a stable queue and never exceeds the selected ceiling`() {
        val controller = ScreenShareBitrateController(6_000_000)
        controller.sample(0, 20, 20, 1, 0)
        controller.sample(200, 0, 20, 1, 0)
        controller.sample(5_000, 0, 20, 1, 0)
        assertThat(controller.bitrate).isEqualTo(4_500_000)
        controller.sample(5_200, 0, 20, 1, 0)
        assertThat(controller.bitrate).isEqualTo(4_800_000)
        repeat(20) { controller.sample(10_200 + it * 5_000L, 0, 20, 1, 0) }
        assertThat(controller.bitrate).isEqualTo(6_000_000)
    }

    @Test
    fun `persistent congestion respects the bitrate floor`() {
        val controller = ScreenShareBitrateController(24_000_000)
        repeat(100) { controller.sample(it * 1_000L, 28, 28, it + 1L, 0) }
        assertThat(controller.bitrate).isEqualTo(500_000)
    }

    @Test
    fun `counter reset is not interpreted as a drop`() {
        val controller = ScreenShareBitrateController(6_000_000)
        controller.sample(0, 0, 20, 3, 2)
        val afterReset = controller.sample(1_000, 0, 20, 0, 0)
        assertThat(afterReset.bitrate).isEqualTo(4_500_000)
        assertThat(afterReset.requestKeyframe).isFalse()
        assertThat(controller.sample(1_200, 0, 20, 1, 0).requestKeyframe).isTrue()
    }
}
