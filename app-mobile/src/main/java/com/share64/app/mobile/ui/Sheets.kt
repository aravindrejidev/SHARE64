package com.share64.app.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.core.OutgoingFile
import com.share64.app.mobile.SettingsState
import com.share64.network.PeerDevice
import com.share64.network.PeerTxt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSheet(
    files: List<OutgoingFile>,
    peers: List<PeerDevice>,
    onPick: (PeerDevice) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val known = files.filter { it.size >= 0 }.sumOf { it.size }
    val title = if (files.size == 1) files[0].name else "${files.size} files"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Send $title", style = MaterialTheme.typography.titleLarge)
            if (known > 0) {
                Text(
                    Formatting.bytes(known),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (peers.isEmpty()) {
                Text(
                    "No devices found yet. Make sure SHARE64 is open on the other device, on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                peers.forEach { PeerRow(it, onClick = { onPick(it) }) }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onManual) { Text("Add by address") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: SettingsState,
    addresses: List<String>,
    port: Int,
    onName: (String) -> Unit,
    onBackground: (Boolean) -> Unit,
    onAutoAccept: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PeerTxt.MAX_NAME_CHARS) },
                singleLine = true,
                label = { Text("Device name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onName(name.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { onName(name.trim()) },
                    enabled = name.isNotBlank() && name.trim() != settings.deviceName,
                ) { Text("Save name") }
            }
            SwitchRow(
                title = "Keep receiving in background",
                subtitle = "Stay visible to other devices while SHARE64 is closed.",
                checked = settings.receiveInBackground,
                onChange = onBackground,
            )
            SwitchRow(
                title = "Accept files automatically",
                subtitle = "Anyone on your Wi-Fi can send you files without asking. Leave off unless you trust the network.",
                checked = settings.autoAccept,
                onChange = onAutoAccept,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Your address", style = MaterialTheme.typography.titleSmall)
            Text(
                if (addresses.isEmpty()) "Not connected to a network"
                else addresses.joinToString("\n") { if (port > 0) "$it:$port" else it },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Received files are saved in Downloads/SHARE64.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
