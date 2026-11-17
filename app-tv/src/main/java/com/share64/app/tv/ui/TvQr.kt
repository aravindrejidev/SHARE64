package com.share64.app.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice
import com.share64.network.QrCode
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * This device's QR code, drawn module by module (no bitmap). The three corner eyes pop in first so a
 * scanner can already lock on, then the rest of the code ripples out from the centre with a springy
 * overshoot. Always dark-on-white with a full quiet zone, whatever the app theme, because that is what
 * scanners read best.
 */
@Composable
fun TvQrCodeView(text: String, modifier: Modifier = Modifier, dark: Color = Color(0xFF14121A)) {
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
 * A TV has no camera, so it shows its code and a phone scans it. When a device appears while the
 * dialog is open (the phone that scanned us), the dialog says so and closes by itself.
 */
@Composable
fun TvQrDialog(
    payload: String?,
    deviceName: String,
    status: DiscoveryStatus,
    address: String,
    peers: List<PeerDevice>,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val knownIds = remember { peers.map { it.id }.toSet() }
    val arrived = peers.firstOrNull { it.id !in knownIds && it.reachable }
    LaunchedEffect(arrived?.id) {
        if (arrived != null) {
            delay(2400)
            onDismiss()
        }
    }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    TvDialog(onDismiss = onDismiss) {
        Text("Connect with QR", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(36.dp),
                color = Color.White,
                modifier = Modifier.size(280.dp),
            ) {
                if (payload != null) {
                    TvQrCodeView(text = payload, modifier = Modifier.fillMaxSize().padding(10.dp))
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("Not reachable yet", color = Color(0xFF14121A), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Column(modifier = Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (arrived != null) {
                    Text("CONNECTED", style = MaterialTheme.typography.labelLarge, color = scheme.tertiary)
                    Text(arrived.displayName, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Surface(shape = CircleShape, color = scheme.secondaryContainer) {
                        Text(
                            if (status is DiscoveryStatus.Running) address else "No network",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    Text(
                        "On your phone open SHARE64, tap Connect, choose Scan code and point the camera at this screen.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        TvButton("Close", onClick = onDismiss, primary = true, modifier = Modifier.focusRequester(closeFocus))
    }
}
