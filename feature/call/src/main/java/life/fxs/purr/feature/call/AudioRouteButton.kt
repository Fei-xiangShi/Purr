package life.fxs.purr.feature.call

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import life.fxs.purr.core.model.AudioRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRouteButton(
    activeRoute: AudioRoute,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = "音频输出：${activeRoute.displayLabel}"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CALL_CONTROL_BUTTON_BACKGROUND,
                contentColor = Color.White,
                disabledContainerColor = CALL_CONTROL_BUTTON_BACKGROUND.copy(alpha = 0.48f),
                disabledContentColor = Color.White.copy(alpha = 0.48f),
            ),
        ) {
            Icon(
                imageVector = activeRoute.displayIcon,
                contentDescription = label,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
