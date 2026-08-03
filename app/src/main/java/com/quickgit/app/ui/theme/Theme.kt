package com.quickgit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GitBlue = Color(0xFF2E6BE6)
val GitGreen = Color(0xFF2DA44E)
val GitRed = Color(0xFFCF222E)
val GitAmber = Color(0xFFBF8700)

private val LightColors = lightColorScheme(
    primary = GitBlue,
    secondary = GitGreen,
    error = GitRed
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    secondary = Color(0xFF56D364),
    error = Color(0xFFFF7B72)
)

@Composable
fun QuickGitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
