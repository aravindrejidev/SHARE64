package com.share64.app.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.share64.network.QrCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * This device's QR code, drawn module by module (no bitmap). The three corner eyes pop in first so a
 * scanner can already lock on, then the rest of the code ripples out from the centre with a springy
 * overshoot. Always dark-on-white with a full quiet zone, whatever the app theme, because that is what
 * scanners read best.
 */
@Composable
fun QrCodeView(text: String, modifier: Modifier = Modifier, dark: Color = Color(0xFF14121A)) {
    val qr = remember(text) {
        try {
            QrCode.encode(text, QrCode.Level.MEDIUM)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val reveal = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 1300, easing = FastOutSlowInEasing))
    }
    if (qr == null) return

    val quiet = 4
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .drawWithCache {
                val total = qr.size + quiet * 2
                val cell = size.minDimension / total
                // The finished code is one cached path; only the reveal animation draws square by square.
                val finished = Path()
                for (y in 0 until qr.size) for (x in 0 until qr.size) {
                    if (qr.isDark(x, y)) {
                        finished.addRect(Rect((x + quiet) * cell, (y + quiet) * cell, (x + quiet + 1) * cell + 0.5f, (y + quiet + 1) * cell + 0.5f))
                    }
                }
                val centre = (qr.size - 1) / 2f
                val maxDistance = hypot(centre, centre)
                onDrawBehind {
                    drawRect(Color.White)
                    val p = reveal.value
                    if (p >= 1f) {
                        drawPath(finished, dark)
                    } else {
                        for (y in 0 until qr.size) for (x in 0 until qr.size) {
                            if (!qr.isDark(x, y)) continue
                            val local = if (qr.isFinder(x, y)) {
                                (p * 4f).coerceIn(0f, 1f)
                            } else {
                                ((p * 1.3f - hypot(x - centre, y - centre) / maxDistance * 0.9f) / 0.35f).coerceIn(0f, 1f)
                            }
                            if (local <= 0f) continue
                            val t = local - 1f
                            val scale = 1f + 2.70158f * t * t * t + 1.70158f * t * t // ease-out-back: overshoots, then settles
                            val side = cell * scale
                            drawRect(
                                color = dark,
                                topLeft = Offset((x + quiet + 0.5f) * cell - side / 2f, (y + quiet + 0.5f) * cell - side / 2f),
                                size = Size(side, side),
                            )
                        }
                    }
                }
            },
    )
}

/**
 * The viewfinder drawn over the camera picture: four corner brackets that breathe, a light line that
 * sweeps up and down, and on [locked] the brackets spring inward and the line goes away.
 */
@Composable
fun ScanFrameOverlay(color: Color, locked: Boolean, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "scanFrame")
    val sweep by infinite.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val lock by animateFloatAsState(
        targetValue = if (locked) 1f else 0f,
        animationSpec = expressiveSpring(0.5f, Spring.StiffnessMedium),
        label = "lock",
    )
    Canvas(modifier = modifier) {
        val minSide = size.minDimension
        val inset = minSide * (0.09f + 0.015f * breath * (1f - lock) + 0.13f * lock)
        val arm = minSide * 0.17f
        val stroke = 5.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        bracket(left, top, 1f, 1f)
        bracket(right, top, -1f, 1f)
        bracket(left, bottom, 1f, -1f)
        bracket(right, bottom, -1f, -1f)
        if (lock < 0.5f) {
            val y = top + (bottom - top) * sweep
            val glow = 70f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0f), color.copy(alpha = 0.22f)),
                    startY = y - glow,
                    endY = y,
                ),
                topLeft = Offset(left, y - glow),
                size = Size(right - left, glow),
                alpha = 1f - lock * 2f,
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, color, Color.Transparent),
                    startX = left,
                    endX = right,
                ),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = 1f - lock * 2f,
            )
        }
    }
}

/**
 * The "paired!" moment: a cookie pops in with a bounce, a ring of dots bursts outward and fades, and
 * the check mark draws itself.
 */
@Composable
fun PairedBurst(cookie: Color, onCookie: Color, dots: List<Color>, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }
        launch { burst.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        delay(260)
        check.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    Box(modifier = modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = burst.value
            val eased = 1f - (1f - t) * (1f - t)
            for (i in 0 until 14) {
                val angle = (i * (360f / 14f) + (i % 2) * 9f) * (PI / 180.0)
                val reach = size.minDimension / 2f * (0.42f + 0.55f * eased) * (0.78f + 0.22f * ((i * 7) % 5) / 4f)
                val radius = (5f + (i % 3) * 3f).dp.toPx() * (1f - t * 0.65f)
                drawCircle(
                    color = dots[i % dots.size],
                    radius = radius,
                    center = Offset(center.x + (cos(angle) * reach).toFloat(), center.y + (sin(angle) * reach).toFloat()),
                    alpha = (1f - t).coerceIn(0f, 1f),
                )
            }
        }
        CookieBadge(
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
            size = 132.dp,
            color = cookie,
            lobes = 10,
            amplitude = 0.08f,
            spin = true,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(size.width * 0.29f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.67f)
                    lineTo(size.width * 0.73f, size.height * 0.35f)
                }
                val measure = PathMeasure()
                measure.setPath(path, false)
                val part = Path()
                measure.getSegment(0f, measure.length * check.value, part, true)
                drawPath(part, onCookie, style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
