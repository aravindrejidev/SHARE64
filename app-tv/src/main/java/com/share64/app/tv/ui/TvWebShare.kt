package com.share64.app.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.tv.TvWebShareUi
import com.share64.network.web.ClientState
import com.share64.network.web.WebClient
import kotlinx.coroutines.delay

/**
 * "Share with anyone" on the TV: the TV shows a QR code, a phone scans it with its normal camera and
 * downloads the files in the browser (no app needed). Devices that ask for the files appear here with
 * Allow / Decline, answered with the remote. A phone that has SHARE64 receives inside the app instead.
 */
@Composable
fun TvWebShareDialog(
    web: TvWebShareUi,
    onStop: () -> Unit,
    onAutoAllow: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember { mutableIntStateOf(0) }
    val url = web.urls.getOrNull(selected.coerceIn(0, maxOf(web.urls.size - 1, 0)))
    val firstPending = web.clients.firstOrNull { it.state == ClientState.PENDING }
    val allowFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    // A device just asked for the files: put the remote on its Allow button. Otherwise on Close.
    LaunchedEffect(firstPending?.id) {
        delay(200)
        runCatching { if (firstPending != null) allowFocus.requestFocus() else closeFocus.requestFocus() }
    }

    TvDialog(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CookieBadge(size = 68.dp, color = scheme.primary, lobes = 9, amplitude = 0.09f, spin = true) {
                Icon(TvIcons.Link, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(32.dp))
            }
            Column {
                Text("Share with anyone", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "No app needed on the other side",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (url == null) {
            Text(
                "No Wi-Fi or Ethernet. The code appears again as soon as this TV is on a network.",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.error,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(32.dp), color = Color.White, modifier = Modifier.size(260.dp)) {
                    TvQrCodeView(text = url, modifier = Modifier.fillMaxSize().padding(10.dp))
                }
                Column(modifier = Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scan with any phone camera", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Or type this address into a browser on the same network:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer) {
                        Text(
                            url.removePrefix("http://"),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Text(
                        "Phones with SHARE64 get the files inside the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    if (web.urls.size > 1) {
                        TvButton(
                            "Network ${selected.coerceIn(0, web.urls.size - 1) + 1} of ${web.urls.size}",
                            onClick = { selected = (selected + 1) % web.urls.size },
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Close", onClick = onDismiss, primary = true, modifier = Modifier.focusRequester(closeFocus))
            TvButton("Stop sharing", onClick = onStop)
        }

        TvToggleRow(
            title = "Let new devices in automatically",
            subtitle = "Convenient at home. Anyone on this network with the link can download.",
            checked = web.autoAllow,
            onChange = onAutoAllow,
        )

        SectionTitle("Devices", count = web.clients.size)
        if (web.clients.isEmpty()) {
            Text(
                "Nobody yet. When someone opens the link, Allow and Decline appear here.",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        web.clients.forEachIndexed { index, c ->
            WebClientCard(
                c = c,
                index = index,
                allowFocus = if (c.id == firstPending?.id) allowFocus else null,
                onApprove = onApprove,
                onDeny = onDeny,
                onRemove = onRemove,
            )
        }

        SectionTitle("Files", count = web.files.size)
        val shown = web.files.take(MAX_FILES_SHOWN)
        for (f in shown) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    f.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (f.size >= 0) {
                    Text(Formatting.bytes(f.size), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (web.files.size > shown.size) {
            Text(
                "and ${web.files.size - shown.size} more",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            "The share stops by itself after 30 minutes without activity. The page is not encrypted, so only allow people you know.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

private const val MAX_FILES_SHOWN = 6

@Composable
private fun WebClientCard(
    c: WebClient,
    index: Int,
    allowFocus: FocusRequester?,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pending = c.state == ClientState.PENDING
    val denied = c.state == ClientState.DENIED
    val palette = cardPalette(index)
    val container = when {
        pending -> scheme.tertiaryContainer
        denied -> scheme.errorContainer
        else -> scheme.surfaceContainerHighest
    }
    val onContainer = when {
        pending -> scheme.onTertiaryContainer
        denied -> scheme.onErrorContainer
        else -> scheme.onSurface
    }
    val accent = when {
        pending -> scheme.tertiary
        denied -> scheme.error
        else -> palette.accent
    }
    val onAccent = when {
        pending -> scheme.onTertiary
        denied -> scheme.onError
        else -> palette.onAccent
    }
    Surface(shape = RoundedCornerShape(32.dp), color = container, contentColor = onContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CookieBadge(size = 52.dp, color = accent, lobes = 8, amplitude = 0.08f, spin = pending || c.activeDownloads > 0) {
                    Icon(
                        if (c.label.contains("phone", true) || c.label.contains("android", true)) TvIcons.Phone else TvIcons.Devices,
                        contentDescription = null,
                        tint = onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = when {
                            pending -> "${c.ip} wants your files"
                            denied -> "${c.ip} \u00B7 declined"
                            c.activeDownloads > 0 -> "${c.ip} \u00B7 downloading \u00B7 ${Formatting.bytes(c.bytesSent)}"
                            c.bytesSent > 0 -> "${c.ip} \u00B7 got ${Formatting.bytes(c.bytesSent)}"
                            else -> "${c.ip} \u00B7 allowed"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }
            }
            if (c.activeDownloads > 0) {
                TvWavyProgress(progress = null, color = accent, trackColor = accent.copy(alpha = 0.25f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pending) {
                    TvButton(
                        "Allow",
                        onClick = { onApprove(c.id) },
                        primary = true,
                        modifier = if (allowFocus != null) Modifier.focusRequester(allowFocus) else Modifier,
                    )
                    TvButton("Decline", onClick = { onDeny(c.id) })
                } else {
                    TvButton(if (denied) "Forget" else "Remove", onClick = { onRemove(c.id) })
                }
            }
        }
    }
}
