package com.share64.app.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.core.OutgoingFile
import com.share64.app.core.TransferItem
import com.share64.app.mobile.MainUiState
import com.share64.app.mobile.MainViewModel
import com.share64.app.mobile.WebShareUi
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice

/**
 * The Send tab: the files you picked, then the devices around you. Tap a device to send the files
 * to it (with nothing picked yet, tapping a device opens the file picker for that device).
 */
@Composable
fun SendScreen(
    vm: MainViewModel,
    state: MainUiState,
    web: WebShareUi?,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onSelectFiles: () -> Unit,
    onSendTo: (PeerDevice) -> Unit,
    onOpenQr: () -> Unit,
    onOpenWebShare: () -> Unit,
    onTurnOnSharing: () -> Unit,
) {
    val context = LocalContext.current
    val hasFiles = state.pendingFiles.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            MainHeader(onOpenSettings)
        }

        if (needsNetworkNotice(state)) {
            item(key = "notice", span = { GridItemSpan(maxLineSpan) }) {
                NetworkNotice(state, onTurnOnSharing)
            }
        }

        item(key = "selected", span = { GridItemSpan(maxLineSpan) }) {
            SelectedFilesCard(
                files = state.pendingFiles,
                onSelect = onSelectFiles,
                onClear = vm::clearPending,
                onRemove = vm::removePending,
            )
        }

        web?.let { session ->
            item(key = "web-banner", span = { GridItemSpan(maxLineSpan) }) {
                WebShareBanner(session, onOpen = onOpenWebShare, onStop = vm::stopWebShare, modifier = Modifier.animateItem())
            }
        }

        item(key = "devices-title", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = if (hasFiles) "Send to" else "Nearby",
                count = state.peers.size,
                actionLabel = if (state.sharingEnabled) "Use QR code" else null,
                onAction = onOpenQr,
            )
        }
        if (!state.sharingEnabled || state.peers.isEmpty()) {
            item(key = "devices-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyDevices(
                    searching = state.status is DiscoveryStatus.Running,
                    sharingOff = !state.sharingEnabled,
                )
            }
        } else {
            itemsIndexed(state.peers, key = { _, peer -> peer.id }) { index, peer ->
                DeviceCard(
                    peer = peer,
                    index = index,
                    modifier = Modifier.animateItem(),
                    onClick = { onSendTo(peer) },
                )
            }
        }

        transferItems("Transfers", state.transfers.filter { it.isSend }, vm, context)
    }
}

/** The big "SHARE64" title with the Settings button, the same on both tabs. */
@Composable
internal fun MainHeader(onOpenSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ScreenHeader(
        title = "SHARE64",
        subtitle = "Local Wi-Fi file sharing",
        trailing = {
            CookieIconButton(
                onClick = onOpenSettings,
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                container = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                buttonSize = 56.dp,
                lobes = 9,
            )
        },
    )
}

internal fun needsNetworkNotice(state: MainUiState): Boolean =
    !state.sharingEnabled || state.status is DiscoveryStatus.NoNetwork || state.status is DiscoveryStatus.Error

/**
 * Shown only when something is wrong: sharing is switched off, there is no network, or discovery
 * hit a problem. Nothing at all appears while everything works.
 */
@Composable
internal fun NetworkNotice(state: MainUiState, onTurnOnSharing: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = state.status
    val off = !state.sharingEnabled
    val title: String
    val text: String
    when {
        off -> {
            title = "Sharing is off"
            text = "Turn sharing on to send and receive files."
        }
        status is DiscoveryStatus.NoNetwork -> {
            title = "No Wi-Fi or hotspot"
            text = "Join the same Wi-Fi as the other device, or turn on a hotspot on one phone and connect the other to it."
        }
        status is DiscoveryStatus.Error -> {
            title = "Discovery problem"
            text = "${status.message}. Retrying…"
        }
        else -> return
    }
    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth().animateContentSize(),
        color = if (off) scheme.surfaceContainerHigh else scheme.errorContainer,
        contentColor = if (off) scheme.onSurface else scheme.onErrorContainer,
        restRadius = 36.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (off) {
                ExpressiveButton(onClick = onTurnOnSharing) { Text("Turn sharing on") }
            }
        }
    }
}

/**
 * "None selected" with a Select button, or the picked files: how many, how big, a list that opens
 * with the arrow, and Clear files.
 */
@Composable
internal fun SelectedFilesCard(
    files: List<OutgoingFile>,
    onSelect: () -> Unit,
    onClear: () -> Unit,
    onRemove: (OutgoingFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val has = files.isNotEmpty()
    LaunchedEffect(has) { if (!has) expanded = false }
    val known = files.filter { it.size >= 0 }.sumOf { it.size }
    val container by animateColorAsState(
        targetValue = if (has) scheme.primaryContainer else scheme.surfaceContainerHigh,
        animationSpec = tween(300),
        label = "filesCard",
    )
    val onContainer = if (has) scheme.onPrimaryContainer else scheme.onSurface

    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth().animateContentSize(),
        color = container,
        contentColor = onContainer,
        restRadius = 40.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CookieBadge(
                    size = 58.dp,
                    color = if (has) scheme.primary else scheme.secondaryContainer,
                    lobes = 9,
                    amplitude = 0.08f,
                    spin = has,
                ) {
                    Icon(
                        imageVector = if (has) Icons.Default.Check else ShareIcons.Attach,
                        contentDescription = null,
                        tint = if (has) scheme.onPrimary else scheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (has) "Selected files" else "Select files to share",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (has) {
                            "${files.size} file${if (files.size == 1) "" else "s"}" + if (known > 0) " · ${Formatting.bytes(known)}" else ""
                        } else {
                            "None selected"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainer.copy(alpha = 0.78f),
                    )
                }
                if (has) {
                    CookieIconButton(
                        onClick = { expanded = !expanded },
                        icon = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide files" else "Show files",
                        container = scheme.primary.copy(alpha = 0.16f),
                        content = onContainer,
                        buttonSize = 48.dp,
                        lobes = 7,
                    )
                }
            }
            if (has && expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    files.forEachIndexed { index, file -> SelectedFileRow(file, index, files.size, onRemove) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (has) {
                    ExpressiveButton(onClick = onClear, kind = ButtonKind.Text) { Text("Clear files") }
                    ExpressiveButton(onClick = onSelect, kind = ButtonKind.Tonal) { Text("Add more") }
                } else {
                    ExpressiveButton(onClick = onSelect) { Text("Select") }
                }
            }
        }
    }
}

@Composable
private fun SelectedFileRow(file: OutgoingFile, index: Int, count: Int, onRemove: (OutgoingFile) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = segmentedShape(index, count, outer = 24.dp, inner = 8.dp),
        color = scheme.primary.copy(alpha = 0.10f),
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (file.size >= 0) {
                    Text(
                        Formatting.bytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
            }
            IconButton(onClick = { onRemove(file) }) {
                Icon(Icons.Default.Close, contentDescription = "Remove ${file.name}")
            }
        }
    }
}

/** A titled run of transfer cards, spanning the full width of the grid. Nothing is added for an empty list. */
internal fun LazyGridScope.transferItems(title: String, list: List<TransferItem>, vm: MainViewModel, context: Context) {
    if (list.isEmpty()) return
    item(key = "$title-title", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(title, count = list.size)
    }
    items(list, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { t ->
        TransferCard(
            item = t,
            modifier = Modifier.animateItem(),
            onCancel = { vm.cancel(t.id) },
            onDismiss = { vm.dismiss(t.id) },
            onOpen = {
                val uri = vm.receivedUri(t.id)
                if (uri == null || !openFile(context, uri)) vm.postMessage("No app can open this file")
            },
        )
    }
}

internal fun openFile(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
