package com.stampshot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4FC3F7)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06212E),
    secondary = Color(0xFF90A4AE),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFECEFF3),
    surfaceVariant = Color(0xFF1B2129),
    onSurfaceVariant = Color(0xFFB8C0CA),
    background = Color.Black,
    onBackground = Color(0xFFECEFF3),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF12161A),
    surfaceVariant = Color(0xFFE3E8EE),
    onSurfaceVariant = Color(0xFF4A525C),
)

@Composable
fun StampShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
