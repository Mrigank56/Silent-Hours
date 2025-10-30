package com.astris.callblocker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.astris.silenthours.ui.theme.modernTypography

// Pitch black background, vibrant blue accents
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2196F3),       // Blue highlight
    onPrimary = Color.White,
    secondary = Color(0xFF42A5F5),
    onSecondary = Color.White,
    tertiary = Color(0xFF90CAF9),

    background = Color(0xFF121212),    // Deep gray-black background
    onBackground = Color(0xFFE0E0E0),  // Light text for readability

    surface = Color(0xFF1E1E1E),       // Slightly lighter than background for cards
    onSurface = Color(0xFFECECEC),

    surfaceVariant = Color(0xFF2A2A2A), // Medium gray for elevated cards
    onSurfaceVariant = Color(0xFFE0E0E0),

    error = Color(0xFFFF5350)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    secondary = Color(0xFF2196F3),
    onSecondary = Color.White,
    tertiary = Color(0xFF64B5F6),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE3F2FD),
    onSurfaceVariant = Color(0xFF37474F),
    error = Color(0xFFD32F2F)
)

@Composable
fun SilentHoursTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colors,
        typography = modernTypography,
        shapes = modernShapes,
        content = content
    )
}
