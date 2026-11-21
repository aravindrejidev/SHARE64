package com.share64.app.mobile.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.mobile.MainViewModel
import com.share64.network.DiscoveryStatus

/**
 * Home, Material 3 Expressive style: a big heavy title, a status hero card, colour-blocked device
 * cards that spring in, wavy-progress transfer cards and a FAB menu with bouncy motion.
 */
@Composable
fun HomeScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenConnect: () -> Unit, onOpenWebShare: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val web by vm.webShare.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var fabExpanded by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onFilesPicked(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = fabExpanded) { fabExpanded = false }
    LaunchedEffect(state.sharingEnabled) { if (!state.sharingEnabled) fabExpanded = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(end = 88.dp)) },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 132.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
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

                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    StatusHero(
                        state = state,
                        onSharingChange = { on ->
                            // Stopping with transfers running deserves a second thought.
                            if (!on && state.activeTransfers > 0) confirmStop = true else vm.setSharingEnabled(on)
                        },
                    )
                }

                web?.let { session ->
                    item(key = "web-banner", span = { GridItemSpan(maxLineSpan) }) {
                        WebShareBanner(session, onOpen = onOpenWebShare, onStop = vm::stopWebShare, modifier = Modifier.animateItem())
                    }
                }

                item(key = "devices-title", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        "Nearby",
                        count = state.peers.size,
                        actionLabel = if (state.sharingEnabled) "Connect with QR" else null,
                        onAction = onOpenConnect,
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
                            onClick = {
                                vm.chooseTarget(peer)
                                picker.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }

                if (state.transfers.isNotEmpty()) {
                    item(key = "transfers-title", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("Transfers", count = state.transfers.size)
                    }
                    items(state.transfers, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { t ->
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
            }
        }

        AnimatedVisibility(visible = fabExpanded, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        fabExpanded = false
                    },
            )
        }

        if (state.sharingEnabled) {
            ExpressiveFabMenu(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                actions = listOf(
                    FabAction("Send files", Icons.AutoMirrored.Filled.Send) {
                        vm.chooseTarget(null)
                        picker.launch(arrayOf("*/*"))
                    },
                    FabAction("Connect with QR", ShareIcons.QrCode) { onOpenConnect() },
                    FabAction("Share with anyone", ShareIcons.Link) { onOpenWebShare() },
                ),
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            )
        }
    }

    if (confirmStop) {
        val n = state.activeTransfers
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            shape = RoundedCornerShape(40.dp),
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("Stop sharing?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "$n transfer${if (n == 1) " is" else "s are"} running. They pause here, nothing already " +
                        "received is lost, and sending the file again continues where it stopped.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                ExpressiveButton(onClick = {
                    confirmStop = false
                    vm.setSharingEnabled(false)
                }) { Text("Stop sharing") }
            },
            dismissButton = {
                ExpressiveButton(onClick = { confirmStop = false }, kind = ButtonKind.Tonal) { Text("Keep sharing") }
            },
        )
    }

    if (state.pendingFiles.isNotEmpty()) {
        SendSheet(
            files = state.pendingFiles,
            peers = state.peers,
            onPick = { vm.sendPendingTo(it) },
            onManual = onOpenConnect,
            onWebShare = {
                vm.startWebShareFromPending()
                onOpenWebShare()
            },
            onDismiss = { vm.clearPending() },
        )
    }

    state.offer?.let { offer ->
        OfferDialog(
            offer = offer,
            onAccept = { vm.accept(offer.id) },
            onDecline = { vm.decline(offer.id) },
        )
    }
}

private fun openFile(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
