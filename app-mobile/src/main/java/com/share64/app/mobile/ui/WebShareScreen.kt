package com.share64.app.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.Formatting
import com.share64.app.mobile.MainViewModel
import com.share64.app.mobile.WebShareUi
import com.share64.network.web.ClientState
import com.share64.network.web.WebClient
import kotlinx.coroutines.delay

/**
 * "Share with anyone": for people who don't have SHARE64. Pick files, show a QR code, and anyone who
 * scans it with a normal camera app gets a web page to download them. This device's SHARE64 users who
 * scan it in the app receive the files through the app instead.
 */
@Composable
fun WebShareScreen(vm: MainViewModel, onBack: () -> Unit) {
    val web by vm.webShare.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.startWebShare(uris)
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp),
        ) {
            ScreenHeader(title = "Share with anyone", subtitle = "No app needed on the other side", onBack = onBack)
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = web != null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(spring(0.8f, Spring.StiffnessMediumLow), initialScale = 0.94f)) togetherWith fadeOut(tween(140))
                },
                label = "webShare",
            ) { active ->
                val session = web
                if (active && session != null) {
                    SessionPane(
                        web = session,
                        onStop = vm::stopWebShare,
                        onAutoAllow = vm::setWebAutoAllow,
                        onApprove = vm::approveWebClient,
                        onDeny = vm::denyWebClient,
                        onRemove = vm::removeWebClient,
                    )
                } else {
                    IntroPane(
                        sharingEnabled = state.sharingEnabled,
                        hasNetwork = state.addresses.isNotEmpty(),
                        onChoose = { picker.launch(arrayOf("*/*")) },
                        onTurnOnSharing = { vm.setSharingEnabled(true) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Before a session: what this is and how it works
// ---------------------------------------------------------------------------------------------

@Composable
private fun IntroPane(sharingEnabled: Boolean, hasNetwork: Boolean, onChoose: () -> Unit, onTurnOnSharing: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BouncyCard(
            onClick = null,
            modifier = Modifier.fillMaxWidth().popIn(0),
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            restRadius = 44.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CookieBadge(size = 72.dp, color = scheme.primary, lobes = 9, amplitude = 0.09f, spin = true) {
                    Icon(ShareIcons.Link, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(34.dp))
                }
                Text("Friends without SHARE64 can still get your files", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pick the files and show the QR code. Anyone who scans it with their normal camera, iPhone or Android, " +
                        "opens a page in the browser and downloads them. Nothing to install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
        }
        StepCard(1, "Choose files", "Photos, videos, documents, anything.", 1)
        StepCard(2, "Show the QR code", "Your phone starts a small web page, just for this.", 2)
        StepCard(3, "They scan and download", "You tap Allow for each new device. SHARE64 users get it inside the app.", 0)

        Spacer(Modifier.height(4.dp))
        when {
            !sharingEnabled -> InfoBlock("Sharing is off", "Turn sharing on first.", "Turn sharing on", onTurnOnSharing)
            !hasNetwork -> InfoBlock(
                "No Wi-Fi or hotspot",
                "Join a Wi-Fi network, or turn on your hotspot and let the other person join it. No internet is needed.",
                null,
                {},
            )
            else -> ExpressiveButton(onClick = onChoose, modifier = Modifier.fillMaxWidth().popIn(4)) { Text("Choose files") }
        }
        Text(
            "Works on the same Wi-Fi, or on your phone's hotspot (no internet needed). Only the files you pick are shared, " +
                "and the page is not encrypted, so you decide who gets in.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StepCard(number: Int, title: String, text: String, paletteIndex: Int) {
    val p = cardPalette(paletteIndex)
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth().popIn(number),
        color = p.container,
        contentColor = p.onContainer,
        restRadius = 32.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieBadge(size = 48.dp, color = p.accent, lobes = 8, amplitude = 0.08f) {
                Text(number.toString(), style = MaterialTheme.typography.titleLarge, color = p.onAccent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = p.onContainer.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun InfoBlock(title: String, text: String, buttonLabel: String?, onButton: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth().popIn(4),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 36.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (buttonLabel != null) ExpressiveButton(onClick = onButton) { Text(buttonLabel) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A running session
// ---------------------------------------------------------------------------------------------

@Composable
private fun SessionPane(
    web: WebShareUi,
    onStop: () -> Unit,
    onAutoAllow: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    val url = web.urls.getOrNull(selected.coerceIn(0, maxOf(web.urls.size - 1, 0)))
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    val pending = web.clients.count { it.state == ClientState.PENDING }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (url == null) {
            InfoBlock("No Wi-Fi or hotspot", "The link appears again as soon as this device is on a network.", null, {})
        } else {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                PulseRings(modifier = Modifier.matchParentSize(), color = scheme.primary)
                Surface(
                    shape = RoundedCornerShape(40.dp),
                    color = Color.White,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth(0.68f).aspectRatio(1f).popIn(0),
                ) {
                    QrCodeView(text = url, modifier = Modifier.fillMaxSize().padding(12.dp))
                }
            }
            Text(
                "Scan with any camera, or open the link in a browser",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh) {
                Text(
                    url.removePrefix("http://"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (web.urls.size > 1) {
                Text("This phone is on more than one network. Pick the one your friend is on:", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    web.urls.forEachIndexed { i, u ->
                        ExpressiveButton(onClick = { selected = i }, kind = if (i == selected) ButtonKind.Filled else ButtonKind.Tonal) {
                            Text(u.removePrefix("http://").substringBefore(':'))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SHARE64 link", url))
                    copied = true
                }, kind = ButtonKind.Tonal) { Text(if (copied) "Copied" else "Copy link") }
                ExpressiveButton(onClick = onStop) { Text("Stop sharing") }
            }
        }

        // devices
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Devices", count = web.clients.size.takeIf { it > 0 })
            AutoAllowRow(web.autoAllow, onAutoAllow)
            if (web.clients.isEmpty()) {
                Text(
                    "Nobody yet. When someone opens the link, a card with Allow and Decline appears here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            web.clients.forEachIndexed { index, c ->
                ClientCard(c, index, onApprove, onDeny, onRemove)
            }
        }

        // files
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionHeader("Files", count = web.files.size)
            Spacer(Modifier.height(8.dp))
            web.files.forEachIndexed { i, f ->
                Surface(
                    shape = segmentedShape(i, web.files.size),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (f.size >= 0) Text(Formatting.bytes(f.size), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            if (pending > 0) "Tap Allow only for people you know." else "The share stops by itself after 30 minutes without activity.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun AutoAllowRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onChange(!checked) },
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Let new devices in automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Convenient at home. Anyone on the network with the link can download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun ClientCard(
    c: WebClient,
    index: Int,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pending = c.state == ClientState.PENDING
    val denied = c.state == ClientState.DENIED
    val container = when {
        pending -> scheme.tertiaryContainer
        denied -> scheme.errorContainer
        else -> scheme.surfaceContainerHigh
    }
    val onContainer = when {
        pending -> scheme.onTertiaryContainer
        denied -> scheme.onErrorContainer
        else -> scheme.onSurface
    }
    val accent = when {
        pending -> scheme.tertiary
        denied -> scheme.error
        else -> cardPalette(index).accent
    }
    val onAccent = when {
        pending -> scheme.onTertiary
        denied -> scheme.onError
        else -> cardPalette(index).onAccent
    }
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth().popIn(0),
        color = container,
        contentColor = onContainer,
        restRadius = 32.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CookieBadge(size = 52.dp, color = accent, lobes = 8, amplitude = 0.08f, spin = pending || c.activeDownloads > 0) {
                    Icon(
                        if (c.label.contains("phone", true) || c.label.contains("android", true)) ShareIcons.Phone else ShareIcons.Devices,
                        contentDescription = null,
                        tint = onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = when {
                            pending -> "${c.ip} wants your files"
                            denied -> "${c.ip} · declined"
                            c.activeDownloads > 0 -> "${c.ip} · downloading · ${Formatting.bytes(c.bytesSent)}"
                            c.bytesSent > 0 -> "${c.ip} · got ${Formatting.bytes(c.bytesSent)}"
                            else -> "${c.ip} · allowed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }
                if (!pending) {
                    ExpressiveButton(onClick = { onRemove(c.id) }, kind = ButtonKind.Text) { Text(if (denied) "Forget" else "Remove") }
                }
            }
            if (c.activeDownloads > 0) {
                WavyProgress(progress = null, color = accent, trackColor = accent.copy(alpha = 0.25f))
            }
            if (pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ExpressiveButton(onClick = { onApprove(c.id) }, modifier = Modifier.weight(1f)) { Text("Allow") }
                    ExpressiveButton(onClick = { onDeny(c.id) }, kind = ButtonKind.Tonal, modifier = Modifier.weight(1f)) { Text("Decline") }
                }
            }
        }
    }
}

/** A thin card on Home while a session runs: how many devices, and a quick way back or to stop. */
@Composable
fun WebShareBanner(web: WebShareUi, onOpen: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val waiting = web.clients.count { it.state == ClientState.PENDING }
    val downloading = web.clients.count { it.activeDownloads > 0 }
    BouncyCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        color = if (waiting > 0) scheme.tertiaryContainer else scheme.secondaryContainer,
        contentColor = if (waiting > 0) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
        restRadius = 36.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CookieBadge(size = 46.dp, color = if (waiting > 0) scheme.tertiary else scheme.secondary, lobes = 8, amplitude = 0.08f, spin = true) {
                Icon(ShareIcons.Link, contentDescription = null, tint = if (waiting > 0) scheme.onTertiary else scheme.onSecondary, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        waiting > 0 -> "$waiting device${if (waiting == 1) "" else "s"} waiting for you"
                        downloading > 0 -> "Sending to $downloading device${if (downloading == 1) "" else "s"}"
                        else -> "Sharing with anyone"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${web.files.size} file${if (web.files.size == 1) "" else "s"} · tap to open",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
            ExpressiveButton(onClick = onStop, kind = ButtonKind.Text) { Text("Stop") }
        }
    }
}
