package com.share64.app.tv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TVs are viewed in dim rooms from far away: always dark, high contrast, no wallpaper colors.
 * Violet / magenta / lime, so the primary / secondary / tertiary containers used for colour
 * blocking are easy to tell apart from across the room.
 */
private val TvDark = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF2D0F8F),
    primaryContainer = Color(0xFF4529B8),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFFFAEEB),
    onSecondary = Color(0xFF5A0050),
    secondaryContainer = Color(0xFF7C1F6E),
    onSecondaryContainer = Color(0xFFFFD7F2),
    tertiary = Color(0xFFC8D44F),
    onTertiary = Color(0xFF2E3300),
    tertiaryContainer = Color(0xFF434B00),
    onTertiaryContainer = Color(0xFFE4F06B),
    background = Color(0xFF0F0D14),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141318),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF2B2833),
    onSurfaceVariant = Color(0xFFC9C5D0),
    surfaceContainerLowest = Color(0xFF0B0A0F),
    surfaceContainerLow = Color(0xFF1A1820),
    surfaceContainer = Color(0xFF201E27),
    surfaceContainerHigh = Color(0xFF2B2833),
    surfaceContainerHighest = Color(0xFF36333F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
)

private val BaseTypography = Typography()
private val TvTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
)

private val TvShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvDark, typography = TvTypography, shapes = TvShapes, content = content)
}
