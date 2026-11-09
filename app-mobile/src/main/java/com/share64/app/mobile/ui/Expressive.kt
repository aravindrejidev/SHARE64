package com.share64.app.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Material 3 Expressive building blocks.
 *
 * The official Expressive component artifacts (material3 1.5.x) need Compose 1.12, which in turn
 * requires AGP 9.2 + compileSdk 37. To keep the build on the proven toolchain, these are the
 * same ideas (shape-morphing presses, wavy progress, FAB menu, segmented groups, spring motion)
 * built on stable Material 3 1.4 APIs.
 */

private val PressSpringDp = spring<Dp>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

enum class ButtonKind { Filled, Tonal, Outlined, Text }

/** Pill button whose corners tighten while pressed, driven by a bouncy spring. */
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
    val radius by animateDpAsState(if (pressed) 10.dp else 40.dp, PressSpringDp, label = "buttonRadius")
    val shape = RoundedCornerShape(radius)
    when (kind) {
        ButtonKind.Filled -> Button(onClick, modifier, enabled, shape = shape, interactionSource = interaction, content = content)
        ButtonKind.Tonal -> FilledTonalButton(onClick, modifier, enabled, shape = shape, interactionSource = interaction, content = content)
        ButtonKind.Outlined -> OutlinedButton(onClick, modifier, enabled, shape = shape, interactionSource = interaction, content = content)
        ButtonKind.Text -> TextButton(onClick, modifier, enabled, shape = shape, interactionSource = interaction, content = content)
    }
}

/** Slightly shrinks the element while it is pressed. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Outer corners large, inner corners small: rows of one group read as a single segmented list. */
fun segmentedShape(index: Int, count: Int, outer: Dp = 28.dp, inner: Dp = 6.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

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
    amplitude: Dp = 3.dp,
    wavelength: Dp = 32.dp,
    strokeWidth: Dp = 5.dp,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
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

/** A rounded shape that spins and morphs between squircle and circle. */
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
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "rotation",
    )
    val morph = transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "morph",
    )
    Canvas(modifier = modifier.size(indicatorSize)) {
        val side = this.size.minDimension * 0.7f
        rotate(rotation.value) {
            drawRoundRect(
                color = color,
                topLeft = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * morph.value),
            )
        }
    }
}

data class FabAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Main FAB that fans out labelled actions with springy enter/exit motion. */
@Composable
fun ExpressiveFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<FabAction>,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "fabRotation",
    )
    val radius by animateDpAsState(if (expanded) 28.dp else 20.dp, PressSpringDp, label = "fabRadius")
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.6f,
                transformOrigin = TransformOrigin(1f, 1f),
            ) + slideInVertically { it / 3 },
            exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)) + slideOutVertically { it / 4 },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { action ->
                    ExtendedFloatingActionButton(
                        onClick = {
                            onExpandedChange(false)
                            action.onClick()
                        },
                        icon = { Icon(action.icon, contentDescription = null) },
                        text = { Text(action.label, fontWeight = FontWeight.SemiBold) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            shape = RoundedCornerShape(radius),
            containerColor = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (expanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Default.Add, contentDescription = if (expanded) "Close menu" else "Open menu", modifier = Modifier.rotate(rotation))
        }
    }
}
