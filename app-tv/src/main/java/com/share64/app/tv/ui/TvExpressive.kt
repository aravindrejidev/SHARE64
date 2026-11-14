package com.share64.app.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Material 3 Expressive building blocks for the TV app: the same cookie shapes, spring motion,
 * colour blocking and wavy progress as the phone app (the two apps are separate modules, so the
 * small helpers are repeated here on purpose).
 */

/** The expressive "spatial" spring: lively, with a visible overshoot. */
fun <T> expressiveSpring(
    damping: Float = 0.6f,
    stiffness: Float = Spring.StiffnessMediumLow,
): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness)

/**
 * Adds a scalloped circle ("cookie" / "flower") to the path: radius = 1 + amplitude * cos(lobes * angle).
 * amplitude 0 is a perfect circle. The whole shape always fits inside [radius].
 */
fun Path.addBlob(
    center: Offset,
    radius: Float,
    lobes: Int,
    amplitude: Float,
    rotationDeg: Float = 0f,
    steps: Int = 180,
) {
    val rotation = rotationDeg * PI / 180.0
    val n = lobes.toDouble()
    val base = radius / (1f + abs(amplitude))
    for (i in 0..steps) {
        val t = 2.0 * PI * i / steps
        val r = base * (1.0 + amplitude * cos(n * (t - rotation)))
        val x = center.x + (r * cos(t)).toFloat()
        val y = center.y + (r * sin(t)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A filled cookie with content on top. With [spin] the cookie slowly turns; the content stays upright. */
@Composable
fun CookieBadge(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    lobes: Int = 8,
    amplitude: Float = 0.07f,
    spin: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "cookieSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
            label = "cookieAngle",
        )
    } else {
        null
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path()
            path.addBlob(center, this.size.minDimension / 2f, lobes, amplitude, angle?.value ?: 0f)
            drawPath(path, color)
        }
        content()
    }
}

/** Two rings that expand and fade, like a radar ping. Fill the available box. */
@Composable
fun PulseRings(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val phase = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "pulsePhase",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val maxRadius = size.minDimension / 2f - stroke
        val p1 = phase.value
        val p2 = (phase.value + 0.5f) % 1f
        for (p in listOf(p1, p2)) {
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.55f),
                radius = maxRadius * (0.5f + 0.5f * p),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** One "colour block": a container colour, its content colour, and a stronger accent on top of it. */
class CardPalette(val container: Color, val onContainer: Color, val accent: Color, val onAccent: Color)

/** Cycles primary -> tertiary -> secondary, so neighbouring cards never share a colour. */
@Composable
fun cardPalette(index: Int): CardPalette {
    val s = MaterialTheme.colorScheme
    return when (((index % 3) + 3) % 3) {
        0 -> CardPalette(s.primaryContainer, s.onPrimaryContainer, s.primary, s.onPrimary)
        1 -> CardPalette(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary, s.onTertiary)
        else -> CardPalette(s.secondaryContainer, s.onSecondaryContainer, s.secondary, s.onSecondary)
    }
}

/**
 * Progress as a moving sine wave. progress = null draws an indeterminate sweep.
 * The wave phase is read inside the draw block, so animation never recomposes.
 */
@Composable
fun TvWavyProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    amplitude: Dp = 4.dp,
    wavelength: Dp = 34.dp,
    strokeWidth: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "phase",
    )
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier = modifier.fillMaxWidth().height(amplitude * 2 + strokeWidth + 4.dp)) {
        val stroke = strokeWidth.toPx()
        val amp = amplitude.toPx()
        val wave = wavelength.toPx()
        val cy = size.height / 2f
        val inset = stroke / 2f
        val left = inset
        val right = size.width - inset

        val activeStart: Float
        val activeEnd: Float
        if (progress != null) {
            activeStart = left
            activeEnd = left + (right - left) * progress.coerceIn(0f, 1f)
        } else {
            val segment = (right - left) * 0.4f
            val x = (size.width + segment) * sweep.value - segment
            activeStart = max(left, x)
            activeEnd = min(right, x + segment)
        }

        // Flat track. Determinate: only the remaining part (with a small gap). Indeterminate: full line.
        val trackFrom = if (progress != null) min(right, activeEnd + stroke * 1.5f) else left
        if (trackFrom < right) {
            drawLine(trackColor, Offset(trackFrom, cy), Offset(right, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        }

        if (activeEnd - activeStart > 0.5f) {
            val path = Path()
            var x = activeStart
            path.moveTo(x, cy + amp * sin(2.0 * PI * (x / wave - phase.value)).toFloat())
            while (x < activeEnd) {
                x = min(activeEnd, x + 3f)
                path.lineTo(x, cy + amp * sin(2.0 * PI * (x / wave - phase.value)).toFloat())
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
