package life.fxs.purr.feature.call

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallAudioControlsTest {
    @Test
    fun `end call background is dark and keeps white icon contrast`() {
        val backgroundLuminance = END_CALL_BUTTON_BACKGROUND.luminance()
        val whiteLuminance = Color.White.luminance()
        val contrastRatio = (whiteLuminance + 0.05f) / (backgroundLuminance + 0.05f)

        assertThat(backgroundLuminance).isLessThan(0.15f)
        assertThat(contrastRatio).isAtLeast(4.5f)
    }
}
