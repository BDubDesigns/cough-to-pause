package dev.harness.coughtopause.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FD6C8),
    onPrimary = Color(0xFF06231F),
    secondary = Color(0xFFB9C7C3),
    surface = Color(0xFF121615),
    surfaceVariant = Color(0xFF1D2422),
    background = Color(0xFF0C100F),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF116B5E),
    onPrimary = Color.White,
    secondary = Color(0xFF4B5A56),
    surface = Color(0xFFF6FAF8),
    surfaceVariant = Color(0xFFE1EBE7),
    background = Color(0xFFFBFDFC),
)

/** Monospace style for telemetry: numbers must line up when read on a phone in daylight. */
val TelemetryStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

@Composable
fun HarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
