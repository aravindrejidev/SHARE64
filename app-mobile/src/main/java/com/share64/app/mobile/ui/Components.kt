package com.share64.app.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.share64.network.DeviceType
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun StatusBanner(state: MainUiState) {
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
            text = "Ready to receive · ${s.address}${if (state.port > 0) ":${state.port}" else ""}"
            problem = false
        }
        is DiscoveryStatus.Stopped -> {
            text = if (state.ready) "Starting…" else "Starting SHARE64…"
            problem = false
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
fun Avatar(type: DeviceType, name: String, size: Dp = 40.dp) {
    val label = if (type == DeviceType.TV) "TV" else name.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
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
fun DeviceCard(peer: PeerDevice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
fun EmptyDevices(searching: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
            }
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
    val failed = item.state == TransferState.FAILED
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (item.isSend) "↑" else "↓", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.canDismiss) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                }
            }
            val showProgress = item.state == TransferState.ACTIVE || item.state == TransferState.RETRYING ||
                item.state == TransferState.INTERRUPTED
            if (showProgress) {
                if (item.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (item.canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
                if (item.canOpen) FilledTonalButton(onClick = onOpen) { Text("Open") }
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
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

@Composable
fun ManualConnectDialog(onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the IP address shown in the other device's settings, e.g. 192.168.1.20 or 192.168.1.20:52364.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("IP address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConnect(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(text) }, enabled = text.isNotBlank()) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small helper used by sheets: a tappable row for choosing a device. */
@Composable
fun PeerRow(peer: PeerDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
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
