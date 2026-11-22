package com.share64.app.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.share64.app.mobile.MainUiState
import com.share64.app.mobile.WebShareUi
import com.share64.network.PeerDevice

/**
 * The QR code, rising from the bottom. A floating switch at the foot of the sheet chooses what the
 * code is for: "Via SHARE64" (this device's pairing code, scanned in the SHARE64 app) or "Share with
 * anyone" (a link any camera app can open, so people without SHARE64 can download the selected files).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShareSheet(
    state: MainUiState,
    web: WebShareUi?,
    initialMode: Int,
    onDismiss: () -> Unit,
    onSelectFiles: () -> Unit,
    onStartWebShare: () -> Unit,
    onStopWebShare: () -> Unit,
    onAutoAllow: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTurnOnSharing: () -> Unit,
    onPeerConnected: (PeerDevice) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by rememberSaveable { mutableIntStateOf(initialMode) }

    // A device that shows up while this device's own code is on screen is most likely the one that scanned it.
    val knownIds = remember { state.peers.map { it.id }.toSet() }
    LaunchedEffect(state.peers, mode) {
        if (mode == 0) {
            val newcomer = state.peers.firstOrNull { it.id !in knownIds && it.reachable }
            if (newcomer != null) onPeerConnected(newcomer)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp),
        containerColor = scheme.surfaceContainerLow,
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Share using QR code", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    text = if (mode == 0) {
                        "Ask the other person to scan this in SHARE64: Receive, then Scan QR code."
                    } else {
                        "Anyone can scan this with their camera app and download the files in a browser."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                    label = "qrMode",
                ) { m ->
                    if (m == 0) {
                        ShowCodePane(
                            state = state,
                            onTurnOnSharing = onTurnOnSharing,
                            modifier = Modifier.fillMaxWidth(),
                            qrFraction = 0.6f,
                        )
                    } else {
                        AnywherePane(
                            state = state,
                            web = web,
                            onSelectFiles = onSelectFiles,
                            onStart = onStartWebShare,
                            onStop = onStopWebShare,
                            onAutoAllow = onAutoAllow,
                            onApprove = onApprove,
                            onDeny = onDeny,
                            onRemove = onRemove,
                            onTurnOnSharing = onTurnOnSharing,
                        )
                    }
                }
            }
            FloatingModeToggle(
                mode = mode,
                onSelect = { index ->
                    mode = index
                    // Choosing "Share with anyone" with files ready starts the session right away.
                    if (index == 1 && web == null && state.sharingEnabled && state.pendingFiles.isNotEmpty() && state.addresses.isNotEmpty()) {
                        onStartWebShare()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            )
        }
    }
}

/** What "Share with anyone" shows: the running session, or the one thing still missing before it can start. */
@Composable
private fun AnywherePane(
    state: MainUiState,
    web: WebShareUi?,
    onSelectFiles: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAutoAllow: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTurnOnSharing: () -> Unit,
) {
    val count = state.pendingFiles.size
    when {
        web != null -> SessionPane(
            web = web,
            onStop = onStop,
            onAutoAllow = onAutoAllow,
            onApprove = onApprove,
            onDeny = onDeny,
            onRemove = onRemove,
            modifier = Modifier.fillMaxWidth(),
            qrFraction = 0.6f,
        )
        !state.sharingEnabled -> InfoCard(
            title = "Sharing is off",
            text = "Turn sharing on first.",
            buttonLabel = "Turn sharing on",
            onButton = onTurnOnSharing,
        )
        count == 0 -> InfoCard(
            title = "Select files first",
            text = "Pick the files you want to share, then start sharing.",
            buttonLabel = "Select files",
            onButton = onSelectFiles,
        )
        state.addresses.isEmpty() -> InfoCard(
            title = "No Wi-Fi or hotspot",
            text = "Join a Wi-Fi network, or turn on your hotspot and let the other person join it. No internet is needed.",
        )
        else -> InfoCard(
            title = "Share $count file${if (count == 1) "" else "s"} with anyone",
            text = "Your phone starts a small web page, just for this. Whoever scans the code downloads the files in their " +
                "browser, and you tap Allow for each new device.",
            buttonLabel = "Start sharing",
            onButton = onStart,
        )
    }
}

/** The pill that floats at the bottom of the sheet: Via SHARE64 | Share with anyone. */
@Composable
private fun FloatingModeToggle(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(34.dp),
        color = scheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 14.dp,
        modifier = modifier.padding(horizontal = 20.dp),
    ) {
        ExpressiveToggleGroup(
            options = listOf("Via SHARE64", "Share with anyone"),
            selectedIndex = mode,
            onSelect = onSelect,
            modifier = Modifier.padding(6.dp),
        )
    }
}
