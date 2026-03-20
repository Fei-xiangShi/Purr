package life.fxs.purr.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PurrDarkColorScheme = darkColorScheme()

@Composable
fun PurrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PurrDarkColorScheme,
        content = content,
    )
}
