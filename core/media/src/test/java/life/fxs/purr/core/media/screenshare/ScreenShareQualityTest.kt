package life.fxs.purr.core.media.screenshare

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenShareQualityTest {
    @Test
    fun `portrait screens retain their aspect ratio without the old long side cap`() {
        assertThat(ScreenShareQuality.FULL_HD60.sizeFor(1440, 3200))
            .isEqualTo(ScreenShareVideoSize(1080, 2400))
        assertThat(ScreenShareQuality.QHD60.sizeFor(1440, 3200))
            .isEqualTo(ScreenShareVideoSize(1440, 3200))
    }

    @Test
    fun `landscape targets preserve orientation and resolution`() {
        assertThat(ScreenShareQuality.HD60.sizeFor(2560, 1440))
            .isEqualTo(ScreenShareVideoSize(1280, 720))
        assertThat(ScreenShareQuality.FULL_HD30.sizeFor(2560, 1440))
            .isEqualTo(ScreenShareVideoSize(1920, 1080))
    }

    @Test
    fun `a smaller source is never upscaled and encoder dimensions remain even`() {
        assertThat(ScreenShareQuality.QHD60.sizeFor(721, 1281))
            .isEqualTo(ScreenShareVideoSize(720, 1280))
        assertThat(ScreenShareQuality.HD30.sizeFor(0, 1))
            .isEqualTo(ScreenShareVideoSize(2, 2))
    }
}
