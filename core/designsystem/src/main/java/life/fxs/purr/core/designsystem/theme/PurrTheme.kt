package life.fxs.purr.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val PurrDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF96CA),
    onPrimary = Color(0xFF3B1127),
    primaryContainer = Color(0xFF63304A),
    onPrimaryContainer = Color(0xFFFFD8E8),
    inversePrimary = Color(0xFF8A4568),
    secondary = Color(0xFFC8B6FF),
    onSecondary = Color(0xFF2E1C57),
    secondaryContainer = Color(0xFF46326F),
    onSecondaryContainer = Color(0xFFE9E0FF),
    tertiary = Color(0xFF8FE8DC),
    onTertiary = Color(0xFF0F3A37),
    tertiaryContainer = Color(0xFF26514C),
    onTertiaryContainer = Color(0xFFB5FFF4),
    background = Color(0xFF140F1B),
    onBackground = Color(0xFFF7EEF8),
    surface = Color(0xFF221928),
    onSurface = Color(0xFFF7EEF8),
    surfaceVariant = Color(0xFF34283D),
    onSurfaceVariant = Color(0xFFE6D7E8),
    inverseSurface = Color(0xFFF7EEF8),
    inverseOnSurface = Color(0xFF211923),
    error = Color(0xFFFFB3C7),
    onError = Color(0xFF560F28),
    errorContainer = Color(0xFF733048),
    onErrorContainer = Color(0xFFFFD8E3),
    outline = Color(0xFF98869F),
)

private val PurrShapes = Shapes(
    extraSmall = RoundedCornerShape(18.dp),
    small = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(30.dp),
    large = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

private val PurrTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun PurrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PurrDarkColorScheme,
        shapes = PurrShapes,
        typography = PurrTypography,
        content = content,
    )
}
