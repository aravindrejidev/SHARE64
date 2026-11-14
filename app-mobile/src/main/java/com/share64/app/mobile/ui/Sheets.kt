package com.share64.app.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val known = files.filter { it.size >= 0 }.sumOf { it.size }
    val title = if (files.size == 1) files[0].name else "${files.size} files"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp),
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CookieBadge(size = 64.dp, color = scheme.primary, lobes = 9, amplitude = 0.09f, spin = true) {
                    Icon(ShareIcons.Upload, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Send $title",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (known > 0) {
                        Surface(shape = CircleShape, color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
                            Text(
                                text = Formatting.bytes(known),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (peers.isEmpty()) {
                Text(
                    "No devices found yet. Make sure SHARE64 is open on the other device, on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                peers.forEachIndexed { i, p -> PeerRow(p, index = i, count = peers.size, onClick = { onPick(p) }) }
            }
            Spacer(Modifier.height(14.dp))
            ExpressiveButton(onClick = onManual, kind = ButtonKind.Tonal, modifier = Modifier.align(Alignment.End)) {
                Text("Add by address")
            }
        }
    }
}
