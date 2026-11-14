package com.share64.app.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Material 3 Expressive building blocks.
 *
 * The official Expressive component artifacts (material3 1.5.x) need Compose 1.12, which in turn
 * requires AGP 9 + compileSdk 37. To keep the build on the proven toolchain, these are the same
 * ideas (cookie shapes, shape-morphing presses, wavy progress, FAB menu, connected button groups,
 * spring motion) built on stable Material 3 1.4 / Compose 1.9 APIs.
 */

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

/** The expressive "spatial" spring: lively, with a visible overshoot. */
fun <T> expressiveSpring(
    damping: Float = 0.6f,
    stiffness: Float = Spring.StiffnessMediumLow,
): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness)

private val PressSpringDp = spring<Dp>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

/** Slightly shrinks the element while it is pressed. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Pops the element in with a springy scale + fade when it first appears. [index] staggers
 * neighbours (first eight only, so long lists never feel slow).
 */
@Composable
fun Modifier.popIn(index: Int = 0): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, 8) * 60L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "popIn",
    )
    return this.graphicsLayer {
        val s = 0.78f + 0.22f * progress
        scaleX = s
        scaleY = s
        alpha = progress.coerceIn(0f, 1f)
    }
}

// ---------------------------------------------------------------------------------------------
// Cookie / blob shapes
// ---------------------------------------------------------------------------------------------

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

/** A [Shape] version of [addBlob], for clip() and Surface(shape = ...). */
class BlobShape(
    private val lobes: Int,
    private val amplitude: Float,
    private val rotationDeg: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        path.addBlob(Offset(size.width / 2f, size.height / 2f), min(size.width, size.height) / 2f, lobes, amplitude, rotationDeg)
        return Outline.Generic(path)
    }
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

/** Icon button shaped like a cookie that relaxes to a circle (and twists) while pressed. */
@Composable
fun CookieIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    buttonSize: Dp = 52.dp,
    lobes: Int = 8,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val amplitude by animateFloatAsState(
        targetValue = if (pressed) 0f else 0.07f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "cookieAmplitude",
    )
    val twist by animateFloatAsState(
        targetValue = if (pressed) 24f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "cookieTwist",
    )
    Surface(
        onClick = onClick,
        shape = BlobShape(lobes, amplitude, twist),
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = modifier.size(buttonSize).pressScale(interaction),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
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

// ---------------------------------------------------------------------------------------------
// Colour blocking
// ---------------------------------------------------------------------------------------------

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
 * A large rounded card whose corners tighten and whose body shrinks while pressed, driven by
 * bouncy springs. With [onClick] = null it is a plain (non-clickable) card.
 */
@Composable
fun BouncyCard(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    restRadius: Dp = 36.dp,
    pressedRadius: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val animated by animateDpAsState(if (pressed) pressedRadius else restRadius, PressSpringDp, label = "cardRadius")
    val shape = RoundedCornerShape(if (animated < 0.dp) 0.dp else animated)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = color,
            contentColor = contentColor,
            interactionSource = interaction,
            modifier = modifier.pressScale(interaction),
            content = content,
        )
    } else {
        Surface(shape = shape, color = color, contentColor = contentColor, modifier = modifier, content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons, toggles, headers
// ---------------------------------------------------------------------------------------------

enum class ButtonKind { Filled, Tonal, Outlined, Text }

/** Tall pill button whose corners tighten while pressed, driven by a bouncy spring. */
@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) 12.dp else 40.dp, PressSpringDp, label = "buttonRadius")
    val shape = RoundedCornerShape(radius)
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val sized = modifier.heightIn(min = 52.dp).pressScale(interaction, 0.97f)
    when (kind) {
        ButtonKind.Filled -> Button(onClick, sized, enabled, shape = shape, contentPadding = padding, interactionSource = interaction, content = content)
        ButtonKind.Tonal -> FilledTonalButton(onClick, sized, enabled, shape = shape, contentPadding = padding, interactionSource = interaction, content = content)
        ButtonKind.Outlined -> OutlinedButton(onClick, sized, enabled, shape = shape, contentPadding = padding, interactionSource = interaction, content = content)
        ButtonKind.Text -> TextButton(onClick, sized, enabled, shape = shape, contentPadding = padding, interactionSource = interaction, content = content)
    }
}

/**
 * A connected button group (single choice). The selected button grows, turns solid and goes fully
 * round; the others stay compact. Pressing squeezes any of them.
 */
@Composable
fun ExpressiveToggleGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val radius by animateDpAsState(
                targetValue = when {
                    pressed -> 10.dp
                    selected -> 26.dp
                    else -> 14.dp
                },
                animationSpec = PressSpringDp,
                label = "toggleRadius",
            )
            val grow by animateFloatAsState(
                targetValue = if (selected) 1.35f else 1f,
                animationSpec = expressiveSpring(0.7f, Spring.StiffnessMediumLow),
                label = "toggleWeight",
            )
            val container by animateColorAsState(
                targetValue = if (selected) scheme.primary else scheme.secondaryContainer,
                animationSpec = tween(220),
                label = "toggleColor",
            )
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(if (radius < 0.dp) 0.dp else radius),
                color = container,
                contentColor = if (selected) scheme.onPrimary else scheme.onSecondaryContainer,
                interactionSource = interaction,
                modifier = Modifier.weight(grow.coerceAtLeast(0.1f)).height(52.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp),
    )
}

/** Big heavy page title with an optional cookie back button and trailing action. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onBack != null) {
            CookieIconButton(onClick = onBack, icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** Outer corners large, inner corners small: rows of one group read as a single segmented list. */
fun segmentedShape(index: Int, count: Int, outer: Dp = 32.dp, inner: Dp = 8.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

// ---------------------------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------------------------

/**
 * Progress as a moving sine wave. progress = null draws an indeterminate sweep.
 * The wave phase is read inside the draw block, so animation never recomposes.
 */
@Composable
fun WavyProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    amplitude: Dp = 4.dp,
    wavelength: Dp = 34.dp,
    strokeWidth: Dp = 6.dp,
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

/** A cookie that spins while its scallops breathe in and out: the expressive loading indicator. */
@Composable
fun ExpressiveLoading(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "loading")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "rotation",
    )
    val amplitude = transition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "amplitude",
    )
    Canvas(modifier = modifier.size(indicatorSize)) {
        val path = Path()
        path.addBlob(center, size.minDimension / 2f, 7, amplitude.value, rotation.value, steps = 120)
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------------------------------------
// FAB menu
// ---------------------------------------------------------------------------------------------

data class FabAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Main FAB that fans out labelled actions with springy enter/exit motion. */
@Composable
fun ExpressiveFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<FabAction>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 135f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "fabRotation",
    )
    val radius by animateDpAsState(if (expanded) 36.dp else 26.dp, PressSpringDp, label = "fabRadius")
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.5f,
                transformOrigin = TransformOrigin(1f, 1f),
            ) + slideInVertically { it / 3 },
            exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)) + slideOutVertically { it / 4 },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { i, action ->
                    val p = cardPalette(i + 1)
                    ExtendedFloatingActionButton(
                        onClick = {
                            onExpandedChange(false)
                            action.onClick()
                        },
                        icon = { Icon(action.icon, contentDescription = null) },
                        text = { Text(action.label, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(28.dp),
                        containerColor = p.container,
                        contentColor = p.onContainer,
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            shape = RoundedCornerShape(radius),
            containerColor = if (expanded) scheme.primary else scheme.primaryContainer,
            contentColor = if (expanded) scheme.onPrimary else scheme.onPrimaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = if (expanded) "Close menu" else "Open menu",
                modifier = Modifier.size(32.dp).rotate(rotation),
            )
        }
    }
}
