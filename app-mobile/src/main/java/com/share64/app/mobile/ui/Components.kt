package com.share64.app.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardColors
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) ExpressiveButton(onClick = onAction, kind = ButtonKind.Text) { Text(actionLabel) }
    }
}

@Composable
fun StatusBanner(state: MainUiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val text: String
    val problem: Boolean
    when (val s = state.status) {
        is DiscoveryStatus.NoNetwork -> {
            text = "No Wi-Fi network. Connect to the same Wi-Fi as the other device."
            problem = true
        }
        is DiscoveryStatus.Error -> {
            text = "Discovery problem (${s.message}). Retrying…"
            problem = true
        }
        is DiscoveryStatus.Running -> {
            text = "Visible as ${state.deviceName}\n${s.address}${if (state.port > 0) ":${state.port}" else ""}"
            problem = false
        }
        is DiscoveryStatus.Stopped -> {
            text = "Starting SHARE64…"
            problem = false
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (problem) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (problem) scheme.onErrorContainer else scheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (problem) scheme.error else scheme.tertiary),
            )
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Phones are round, TVs rounded squares, anything else a softer square. */
@Composable
fun Avatar(type: DeviceType, name: String, modifier: Modifier = Modifier, avatarSize: Dp = 44.dp) {
    val label = if (type == DeviceType.TV) "TV" else name.trim().take(1).uppercase().ifEmpty { "?" }
    val shape: Shape = when (type) {
        DeviceType.PHONE -> CircleShape
        DeviceType.TV -> RoundedCornerShape(14.dp)
        DeviceType.OTHER -> RoundedCornerShape(20.dp)
    }
    Box(
        modifier = modifier.size(avatarSize).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    }
}

fun typeLabel(type: DeviceType): String = when (type) {
    DeviceType.PHONE -> "Phone"
    DeviceType.TV -> "TV"
    DeviceType.OTHER -> "Device"
}

@Composable
private fun cardColors(): CardColors =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
fun DeviceCard(peer: PeerDevice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer.type, peer.displayName)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (peer.reachable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline),
                )
            }
            Text(peer.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (peer.reachable) "${typeLabel(peer.type)} · ${peer.host}" else "Not responding",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyDevices(searching: Boolean, modifier: Modifier = Modifier) {
    Card(shape = MaterialTheme.shapes.large, colors = cardColors(), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (searching) ExpressiveLoading()
            Text(
                "Looking for devices… Open SHARE64 on the other device and connect both to the same Wi-Fi. " +
                    "If nothing shows up, use “Add by address”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
    Card(shape = MaterialTheme.shapes.large, colors = cardColors(), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (item.isSend) scheme.tertiaryContainer else scheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (item.isSend) "↑" else "↓",
                        color = if (item.isSend) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                if (item.canDismiss) {
                    FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                }
            }
            val showProgress = item.state == TransferState.ACTIVE || item.state == TransferState.RETRYING ||
                item.state == TransferState.INTERRUPTED
            if (showProgress) {
                WavyProgress(progress = if (item.indeterminate) null else item.progress)
            }
            if (item.detail.isNotEmpty() || item.canCancel || item.canOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.canCancel) ExpressiveButton(onClick = onCancel, kind = ButtonKind.Text) { Text("Cancel") }
                    if (item.canOpen) ExpressiveButton(onClick = onOpen, kind = ButtonKind.Tonal) { Text("Open") }
                }
            }
        }
    }
}

@Composable
fun OfferDialog(offer: IncomingOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // must be answered explicitly
        title = { Text("Incoming file") },
        text = { Text("${offer.sender} wants to send you “${offer.fileName}” (${offer.size}).") },
        confirmButton = { ExpressiveButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { ExpressiveButton(onClick = onDecline, kind = ButtonKind.Text) { Text("Decline") } },
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
        title = { Text(title) },
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
                    shape = MaterialTheme.shapes.medium,
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

/** A tappable row for choosing a device, shaped by its position in the list. */
@Composable
fun PeerRow(peer: PeerDevice, index: Int, count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = segmentedShape(index, count, outer = 24.dp, inner = 6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(peer.type, peer.displayName)
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${typeLabel(peer.type)} · ${peer.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
