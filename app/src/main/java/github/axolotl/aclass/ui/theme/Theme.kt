package github.axolotl.aclass.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFADC9F2),
    secondary = Color(0xFFAEDCCC),
    tertiary = Color(0xFFEFB8CC),
    background = Color(0xFF141D29),
    surface = Color(0xFF1D2836),
    onSurface = Color(0xFFE2EAF5),
    onBackground = Color(0xFFE2EAF5)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4E709B),
    secondary = Color(0xFF507A69),
    tertiary = Color(0xFF966681),
    background = Color(0xFFF4F7FB),
    surface = Color(0xFFF9FAFD),
    onSurface = Color(0xFF172B42),
    onBackground = Color(0xFF172B42)
)

@Composable
fun AxolotlClassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    customPrimaryColor: Color = Color(0xFF4E709B),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> customColorScheme(customPrimaryColor, darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private fun customColorScheme(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    var primary = if (dark) lerp(seed, Color.White, 0.5f) else lerp(seed, Color.Black, 0.25f)
    if (!dark) while (primary.luminance() > 0.18f) primary = lerp(primary, Color.Black, 0.1f)
    val container = if (dark) lerp(seed, Color.Black, 0.55f) else lerp(seed, Color.White, 0.82f)
    val background = if (dark) lerp(seed, Color.Black, 0.88f) else lerp(seed, Color.White, 0.96f)
    val surface = if (dark) lerp(seed, Color.Black, 0.83f) else lerp(seed, Color.White, 0.98f)
    val ink = if (dark) Color(0xFFE6EAF0) else Color(0xFF17212D)
    val base = if (dark) DarkColorScheme else LightColorScheme
    return base.copy(
        primary = primary, onPrimary = if (dark) Color(0xFF14202B) else Color.White,
        primaryContainer = container, onPrimaryContainer = ink,
        secondary = primary, onSecondary = if (dark) Color(0xFF14202B) else Color.White,
        secondaryContainer = container, onSecondaryContainer = ink,
        tertiary = primary, onTertiary = if (dark) Color(0xFF14202B) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = ink,
        background = background, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = container, onSurfaceVariant = if (dark) Color(0xFFC3CDD8) else Color(0xFF526172),
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = if (dark) lerp(seed, Color.Black, 0.78f) else lerp(seed, Color.White, 0.92f),
        surfaceContainerHigh = if (dark) lerp(seed, Color.Black, 0.72f) else lerp(seed, Color.White, 0.87f),
        surfaceContainerHighest = container,
        outline = if (dark) Color(0xFF8594A4) else Color(0xFF738293),
        outlineVariant = if (dark) lerp(seed, Color.Black, 0.5f) else lerp(seed, Color.White, 0.7f),
    )
}
