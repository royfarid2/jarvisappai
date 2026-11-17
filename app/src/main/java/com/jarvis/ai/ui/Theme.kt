package com.jarvis.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Cyan = Color(0xFF4DD0E1)
val ArcBlue = Color(0xFF3A86FF)

private val DarkColors = darkColorScheme(
    primary = Cyan, onPrimary = Color(0xFF00282D),
    secondary = ArcBlue, background = Color(0xFF0A0B0D), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF12141A), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1B1E26), onSurfaceVariant = Color(0xFF9AA1AB),
    outline = Color(0xFF2A2E38), error = Color(0xFFFF6B6B),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF00707D), onPrimary = Color.White,
    secondary = ArcBlue, background = Color(0xFFF6F7F9), onBackground = Color(0xFF14161A),
    surface = Color.White, onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFE9ECF1), onSurfaceVariant = Color(0xFF5B6270),
    outline = Color(0xFFD0D4DC), error = Color(0xFFB3261E),
)

// Claude-inspired: a literary serif for conversation text, clean sans for interface chrome.
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif
private val JarvisTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Serif, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontFamily = Serif, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Serif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.4.sp),
)

@Composable
fun JarvisTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = JarvisTypography, content = content)
}
