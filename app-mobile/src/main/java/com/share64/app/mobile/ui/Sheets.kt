package com.share64.app.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.core.OutgoingFile
import com.share64.network.PeerDevice

/** Choose which device receives the selected files. */
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Send $title", style = MaterialTheme.typography.headlineSmall)
            if (known > 0) {
                Text(
                    Formatting.bytes(known),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            if (peers.isEmpty()) {
                Text(
                    "No devices found yet. Make sure SHARE64 is open on the other device, on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                peers.forEachIndexed { i, p -> PeerRow(p, index = i, count = peers.size, onClick = { onPick(p) }) }
            }
            Spacer(Modifier.height(12.dp))
            ExpressiveButton(onClick = onManual, kind = ButtonKind.Tonal, modifier = Modifier.align(Alignment.End)) {
                Text("Add by address")
            }
        }
    }
}
