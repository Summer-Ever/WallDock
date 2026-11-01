package com.walldock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A5BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6FF),
    onPrimaryContainer = Color(0xFF0A1B54),
    secondary = Color(0xFF00A0A8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F6F7),
    onSecondaryContainer = Color(0xFF00363A),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF12141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF5A6072),
    outline = Color(0xFFD3D9E6),
    outlineVariant = Color(0xFFE6EAF3),
)

@Composable
fun WallDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content,
    )
}
