package com.cityguide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFFFF8F00),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1F1E),
    surfaceVariant = Color(0xFFE6EFED),
    background = Color(0xFFF6FAF9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF004F48),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFFFB74D),
    surface = Color(0xFF1C2120),
    onSurface = Color(0xFFE0E4E3),
    surfaceVariant = Color(0xFF2B3331),
    background = Color(0xFF111514)
)

@Composable
fun CityGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
