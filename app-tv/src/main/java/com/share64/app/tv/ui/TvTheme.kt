package com.share64.app.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** TVs are viewed in dim rooms from far away: always dark, high contrast, no wallpaper colors. */
private val TvDark = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF283CA1),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF81C784),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF1A1C22),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF2B2E3A),
    onSurfaceVariant = Color(0xFFC6C5D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F909A),
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvDark, content = content)
}
