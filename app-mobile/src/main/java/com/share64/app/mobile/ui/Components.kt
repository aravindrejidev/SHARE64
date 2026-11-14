package com.share64.app.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.share64.app.core.TransferItem
import com.share64.app.mobile.IncomingOffer
import com.share64.app.mobile.MainUiState
import com.share64.core.TransferState
import com.share64.network.AddressParser
import com.share64.network.DeviceType
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice

// ---------------------------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------------------------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    count: Int? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count != null && count > 0) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (actionLabel != null) ExpressiveButton(onClick = onAction, kind = ButtonKind.Tonal) { Text(actionLabel) }
    }
}

fun typeLabel(type: DeviceType): String = when (type) {
    DeviceType.PHONE -> "Phone"
    DeviceType.TV -> "TV"
    DeviceType.OTHER -> "Device"
}

fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.PHONE -> ShareIcons.Phone
    DeviceType.TV -> ShareIcons.Tv
    DeviceType.OTHER -> ShareIcons.Devices
}

/** Cookie avatar: a phone icon, a TV icon, or a generic device icon, depending on the device. */
@Composable
fun Avatar(type: DeviceType, name: String, modifier: Modifier = Modifier, avatarSize: Dp = 48.dp) {
    val scheme = MaterialTheme.colorScheme
    CookieBadge(modifier = modifier, size = avatarSize, color = scheme.primary, lobes = 8, amplitude = 0.07f) {
        Icon(deviceIcon(type), contentDescription = name, tint = scheme.onPrimary, modifier = Modifier.size(avatarSize / 2))
    }
}

@Composable
private fun OnlinePill(online: Boolean, palette: CardPalette) {
    Surface(shape = CircleShape, color = palette.accent.copy(alpha = 0.18f), contentColor = palette.onContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (online) palette.accent else palette.onContainer.copy(alpha = 0.4f)),
            )
            Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Status hero
// ---------------------------------------------------------------------------------------------

/** The big card at the top of Home: pulsing cookie, device name, address. Turns red on problems. */
@Composable
fun StatusHero(state: MainUiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = state.status
    val problem = status is DiscoveryStatus.NoNetwork || status is DiscoveryStatus.Error
    val running = status is DiscoveryStatus.Running

    val label: String
    val title: String
    val detail: String?
    when (status) {
        is DiscoveryStatus.NoNetwork -> {
            label = "OFFLINE"
            title = "No Wi-Fi network"
            detail = "Connect to the same Wi-Fi as the other device."
        }
        is DiscoveryStatus.Error -> {
            label = "PROBLEM"
            title = "Discovery problem"
            detail = "${status.message}. Retrying…"
        }
        is DiscoveryStatus.Running -> {
            label = "VISIBLE AS"
            title = state.deviceName.ifBlank { "This device" }
            detail = status.address + if (state.port > 0) ":${state.port}" else ""
        }
        is DiscoveryStatus.Stopped -> {
            label = "STARTING"
            title = "Starting SHARE64…"
            detail = null
        }
    }

    val container by animateColorAsState(
        targetValue = if (problem) scheme.errorContainer else scheme.primaryContainer,
        animationSpec = tween(450),
        label = "heroContainer",
    )
    val onContainer = if (problem) scheme.onErrorContainer else scheme.onPrimaryContainer
    val accent = if (problem) scheme.error else scheme.primary
    val onAccent = if (problem) scheme.onError else scheme.onPrimary

    val spin = rememberInfiniteTransition(label = "heroBackdrop").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing)),
        label = "heroSpin",
    )

    Surface(
        shape = RoundedCornerShape(44.dp),
        color = container,
        contentColor = onContainer,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Box {
            // A huge, faint cookie bleeding off the top-right corner.
            Canvas(modifier = Modifier.matchParentSize()) {
                val path = Path()
                path.addBlob(
                    center = Offset(size.width * 0.98f, size.height * 0.05f),
                    radius = size.height * 0.95f,
                    lobes = 10,
                    amplitude = 0.08f,
                    rotationDeg = spin.value,
                )
                drawPath(path, accent.copy(alpha = 0.10f))
            }
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    if (running) PulseRings(color = accent)
                    CookieBadge(size = 72.dp, color = accent, lobes = 9, amplitude = 0.09f, spin = !problem) {
                        Icon(
                            imageVector = if (problem) Icons.Default.Warning else ShareIcons.Wifi,
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = onContainer.copy(alpha = 0.7f))
                    Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail != null) {
                        if (running) {
                            Surface(shape = CircleShape, color = accent.copy(alpha = 0.16f)) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                        } else {
                            Text(detail, style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------------------------

/** A device as a colour-blocked card. [index] picks the colour and staggers the entrance. */
@Composable
fun DeviceCard(peer: PeerDevice, onClick: () -> Unit, modifier: Modifier = Modifier, index: Int = 0) {
    val p = cardPalette(index)
    BouncyCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(188.dp).popIn(index),
        color = p.container,
        contentColor = p.onContainer,
        restRadius = 36.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                CookieBadge(size = 60.dp, color = p.accent, lobes = 8 + index % 3, amplitude = 0.08f) {
                    Icon(deviceIcon(peer.type), contentDescription = null, tint = p.onAccent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                OnlinePill(peer.reachable, p)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (peer.reachable) "${typeLabel(peer.type)} · ${peer.host}" else "Not responding",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Radar-style empty state: rings ping outward while SHARE64 is searching. */
@Composable
fun EmptyDevices(searching: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 40.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                if (searching) PulseRings(color = scheme.primary)
                CookieBadge(size = 76.dp, color = scheme.tertiaryContainer, lobes = 10, amplitude = 0.07f, spin = true) {
                    Icon(
                        ShareIcons.Wifi,
                        contentDescription = null,
                        tint = scheme.onTertiaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Text(
                text = if (searching) "Looking for devices…" else "No devices yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Open SHARE64 on the other device and connect both to the same Wi-Fi. " +
                    "If nothing shows up, use “Add by address”.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Transfers
// ---------------------------------------------------------------------------------------------

@Composable
fun TransferCard(
    item: TransferItem,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val failed = item.state == TransferState.FAILED
    val finished = item.state == TransferState.COMPLETED
    val showProgress = item.state == TransferState.ACTIVE || item.state == TransferState.RETRYING ||
        item.state == TransferState.INTERRUPTED

    val container = when {
        failed -> scheme.errorContainer
        finished -> scheme.surfaceContainerHigh
        item.isSend -> scheme.primaryContainer
        else -> scheme.tertiaryContainer
    }
    val onContainer = when {
        failed -> scheme.onErrorContainer
        finished -> scheme.onSurface
        item.isSend -> scheme.onPrimaryContainer
        else -> scheme.onTertiaryContainer
    }
    val accent = when {
        failed -> scheme.error
        finished -> scheme.secondary
        item.isSend -> scheme.primary
        else -> scheme.tertiary
    }
    val onAccent = when {
        failed -> scheme.onError
        finished -> scheme.onSecondary
        item.isSend -> scheme.onPrimary
        else -> scheme.onTertiary
    }

    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth().animateContentSize(),
        color = container,
        contentColor = onContainer,
        restRadius = 36.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CookieBadge(size = 54.dp, color = accent, lobes = 7, amplitude = 0.09f, spin = showProgress) {
                    Icon(
                        imageVector = if (item.isSend) ShareIcons.ArrowUp else ShareIcons.ArrowDown,
                        contentDescription = if (item.isSend) "Sending" else "Receiving",
                        tint = onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = item.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.78f),
                    )
                }
                if (showProgress && !item.indeterminate) {
                    Text("${(item.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                } else if (item.canDismiss) {
                    CookieIconButton(
                        onClick = onDismiss,
                        icon = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        container = accent.copy(alpha = 0.18f),
                        content = onContainer,
                        buttonSize = 44.dp,
                        lobes = 7,
                    )
                }
            }
            if (showProgress) {
                WavyProgress(
                    progress = if (item.indeterminate) null else item.progress,
                    color = accent,
                    trackColor = accent.copy(alpha = 0.25f),
                )
            }
            if (item.detail.isNotEmpty() || item.canCancel || item.canOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.72f),
                        modifier = Modifier.weight(1f),
                    )
                    if (item.canCancel) ExpressiveButton(onClick = onCancel, kind = ButtonKind.Text) { Text("Cancel") }
                    if (item.canOpen) ExpressiveButton(onClick = onOpen, kind = ButtonKind.Filled) { Text("Open") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
fun OfferDialog(offer: IncomingOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {}, // must be answered explicitly
        shape = RoundedCornerShape(44.dp),
        containerColor = scheme.surfaceContainerHigh,
        icon = {
            CookieBadge(size = 64.dp, color = scheme.tertiary, lobes = 9, amplitude = 0.09f, spin = true) {
                Icon(ShareIcons.ArrowDown, contentDescription = null, tint = scheme.onTertiary, modifier = Modifier.size(30.dp))
            }
        },
        title = { Text("Incoming file", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center) },
        text = {
            Text(
                "${offer.sender} wants to send you “${offer.fileName}” (${offer.size}).",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = { ExpressiveButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { ExpressiveButton(onClick = onDecline, kind = ButtonKind.Tonal) { Text("Decline") } },
    )
}

/** One-line text input with validation; optional neutral action (e.g. "Default"). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = Int.MAX_VALUE,
    confirmLabel: String = "Save",
    neutralLabel: String? = null,
    onNeutral: () -> Unit = {},
    validate: (String) -> String? = { null },
) {
    var text by remember { mutableStateOf(initial) }
    val error = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(40.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(maxLength) },
                    singleLine = true,
                    label = { Text(label) },
                    isError = error != null,
                    supportingText = { if (error != null) Text(error) },
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            ExpressiveButton(onClick = { onConfirm(text.trim()) }, enabled = error == null) { Text(confirmLabel) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (neutralLabel != null) ExpressiveButton(onClick = onNeutral, kind = ButtonKind.Text) { Text(neutralLabel) }
                ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Text) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ManualConnectDialog(onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    TextInputDialog(
        title = "Add by address",
        label = "IP address",
        initial = "",
        supporting = "Enter the address shown in the other device's settings, e.g. 192.168.1.20 or 192.168.1.20:52364.",
        keyboardType = KeyboardType.Uri,
        confirmLabel = "Connect",
        validate = { if (it.isBlank() || AddressParser.parse(it) != null) null else "Not a valid address" },
        onConfirm = onConnect,
        onDismiss = onDismiss,
    )
}

/** A tappable, colour-blocked row for choosing a device, shaped by its position in the list. */
@Composable
fun PeerRow(peer: PeerDevice, index: Int, count: Int, onClick: () -> Unit) {
    val p = cardPalette(index)
    Surface(
        onClick = onClick,
        shape = segmentedShape(index, count, outer = 32.dp, inner = 8.dp),
        color = p.container,
        contentColor = p.onContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieBadge(size = 52.dp, color = p.accent, lobes = 8, amplitude = 0.07f) {
                Icon(deviceIcon(peer.type), contentDescription = null, tint = p.onAccent, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${typeLabel(peer.type)} · ${peer.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
