package com.quickgit.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF004B7C),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFB0C6DC),
    onSecondary = Color(0xFF1A3347),
    secondaryContainer = Color(0xFF324A5F),
    onSecondaryContainer = Color(0xFFCCE5FF),
    tertiary = Color(0xFFD6BEE4),
    background = Color(0xFF1A1C1E),
    surface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFF43474E),
    onSurface = Color(0xFFE2E2E6),
    onSurfaceVariant = Color(0xFFC3C6CF),
    error = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFFDFCFF),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFBA1A1A),
)

@Composable
fun QuickGitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
