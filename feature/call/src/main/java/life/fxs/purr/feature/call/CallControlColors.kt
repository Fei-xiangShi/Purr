package life.fxs.purr.feature.call

import androidx.compose.ui.graphics.Color

internal val CALL_CONTROL_BUTTON_BACKGROUND = Color(0x2EFFFFFF)
internal val END_CALL_BUTTON_BACKGROUND = Color(0xFFB3261E)
internal val MICROPHONE_ENABLED_ICON_COLOR = Color.White
internal val MICROPHONE_MUTED_ICON_COLOR = Color.White.copy(alpha = 0.48f)

internal fun microphoneIconColor(muted: Boolean): Color =
    if (muted) MICROPHONE_MUTED_ICON_COLOR else MICROPHONE_ENABLED_ICON_COLOR
