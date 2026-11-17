package app.connect.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Coral = Color(0xFFE5484D)
private val CoralDark = Color(0xFFFF8589)
private val Ink = Color(0xFF1B1B1F)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF3E63DD),
    onSecondary = Color.White,
    tertiary = Color(0xFF2B9A66),
    background = Color(0xFFFCFBFA),
    onBackground = Ink,
    surface = Color(0xFFFCFBFA),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EEEC),
    onSurfaceVariant = Color(0xFF5E5A57),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = CoralDark,
    onPrimary = Color(0xFF680012),
    primaryContainer = Color(0xFF92001E),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFF9EB1FF),
    tertiary = Color(0xFF5BD6A0),
    background = Color(0xFF141316),
    onBackground = Color(0xFFE9E6E3),
    surface = Color(0xFF141316),
    onSurface = Color(0xFFE9E6E3),
    surfaceVariant = Color(0xFF2A282C),
    onSurfaceVariant = Color(0xFFBDB8B4),
)

private val ConnectTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val ConnectShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun ConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so screenshots and UI tests look the same on every device.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = ConnectTypography, shapes = ConnectShapes, content = content)
}
