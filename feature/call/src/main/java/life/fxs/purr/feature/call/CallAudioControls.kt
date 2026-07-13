package life.fxs.purr.feature.call

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophoneLevelButton(
    label: String,
    muted: Boolean,
    level: Float,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { androidx.compose.material3.Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = LIGHT_ICON_BUTTON_BACKGROUND,
                contentColor = Color.White,
                disabledContainerColor = LIGHT_ICON_BUTTON_BACKGROUND.copy(alpha = 0.48f),
                disabledContentColor = Color.White.copy(alpha = 0.48f),
            ),
        ) {
            if (muted) {
                Icon(
                    imageVector = Icons.Rounded.MicOff,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                MicrophoneLevelIcon(level = level, contentDescription = label)
            }
        }
    }
}

@Composable
private fun MicrophoneLevelIcon(
    level: Float,
    contentDescription: String,
) {
    val normalizedLevel = level.coerceIn(0f, 1f)
    val microphonePainter = rememberVectorPainter(Icons.Rounded.Mic)
    Canvas(
        modifier = Modifier
            .size(32.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        with(microphonePainter) {
            draw(
                size = size,
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.34f)),
            )
        }
        if (normalizedLevel > 0f) {
            clipRect(top = size.height * (1f - normalizedLevel)) {
                with(microphonePainter) {
                    draw(size = size, colorFilter = ColorFilter.tint(MICROPHONE_LEVEL_BLUE))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { androidx.compose.material3.Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = Color.White,
                disabledContainerColor = containerColor.copy(alpha = 0.48f),
                disabledContentColor = Color.White.copy(alpha = 0.48f),
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private val LIGHT_ICON_BUTTON_BACKGROUND = Color(0xFF263238)
private val MICROPHONE_LEVEL_BLUE = Color(0xFF64B5F6)
