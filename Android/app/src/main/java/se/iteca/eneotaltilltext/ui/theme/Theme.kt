package se.iteca.eneotaltilltext.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val IosBlue = Color(0xFF007AFF)
val LocalWarningColor = staticCompositionLocalOf { Color(0xFFFF9500) }

private val LightColors = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    secondary = IosBlue,
    tertiary = Color(0xFF5856D6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondary = Color(0xFF0A84FF),
    tertiary = Color(0xFF5E5CE6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE5E5EA),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF2C2C2E),
    surfaceContainer = Color(0xFF3A3A3C),
    surfaceContainerHigh = Color(0xFF48484A),
    surfaceContainerHighest = Color(0xFF545456),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF48484A),
)

@Composable
fun EneoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val warning = if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    CompositionLocalProvider(LocalWarningColor provides warning) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
