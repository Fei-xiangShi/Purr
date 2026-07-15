package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallControlColorsTest {
    @Test
    fun `enabled microphone icon is emphasized`() {
        val color = microphoneIconColor(muted = false)

        assertThat(color).isEqualTo(MICROPHONE_ENABLED_ICON_COLOR)
        assertThat(color.alpha).isEqualTo(1f)
    }

    @Test
    fun `muted microphone icon is de-emphasized`() {
        val color = microphoneIconColor(muted = true)

        assertThat(color).isEqualTo(MICROPHONE_MUTED_ICON_COLOR)
        assertThat(color.alpha).isLessThan(MICROPHONE_ENABLED_ICON_COLOR.alpha)
    }
}
