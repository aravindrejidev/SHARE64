package com.share64.app.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.share64.app.core.ThemeMode

/*
 * Fallback palette (used when Dynamic color is off or on Android 11 and older):
 * violet + magenta + lime, so the primary / secondary / tertiary containers that the UI
 * "colour-blocks" with are clearly different from each other.
 */
private val LightFallback = lightColorScheme(
    primary = Color(0xFF5E43D8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFFA13B8E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD7F2),
    onSecondaryContainer = Color(0xFF3A0030),
    tertiary = Color(0xFF5B6300),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4F06B),
    onTertiaryContainer = Color(0xFF1B1E00),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE6E0F0),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1FD),
    surfaceContainer = Color(0xFFEFEAF8),
    surfaceContainerHigh = Color(0xFFE9E4F2),
    surfaceContainerHighest = Color(0xFFE3DEEC),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4D0),
)

private val DarkFallback = darkColorScheme(
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
    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141318),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFC9C5D0),
    surfaceContainerLowest = Color(0xFF0F0E13),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
)

/** Expressive type: big, heavy display and headline styles, bold titles and labels. */
private val BaseTypography = Typography()
private val ExpressiveTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = BaseTypography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displaySmall = BaseTypography.displaySmall.copy(
        fontSize = 38.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = BaseTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
)

/** Much rounder than the M3 baseline: cards use extraLarge (40dp), buttons are full pills. */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/** Pure-black surfaces for OLED screens; containers step up from black so cards stay readable. */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceVariant = Color(0xFF1C1C1C),
)

fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material 3 Expressive look: Material You dynamic color (optional), pure-black AMOLED mode,
 * heavy type and very large corner radii. Spring motion lives in Expressive.kt.
 */
@Composable
fun Share64Theme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(themeMode, isSystemInDarkTheme())
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    val scheme = if (dark && amoled) base.toAmoled() else base
    MaterialTheme(colorScheme = scheme, typography = ExpressiveTypography, shapes = ExpressiveShapes, content = content)
}
