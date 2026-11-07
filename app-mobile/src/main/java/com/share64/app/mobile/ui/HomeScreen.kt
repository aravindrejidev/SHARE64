package com.share64.app.mobile.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.mobile.MainViewModel
import com.share64.network.DiscoveryStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SHARE64", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (state.deviceName.isNotEmpty()) {
                            Text(
                                "Visible as ${state.deviceName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.chooseTarget(null)
                    picker.launch(arrayOf("*/*"))
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Send files") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) { StatusBanner(state) }

            item(key = "devices-title", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Nearby devices", actionLabel = "Add by address", onAction = { showManual = true })
            }
            if (state.peers.isEmpty()) {
                item(key = "devices-empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyDevices(searching = state.status is DiscoveryStatus.Running)
                }
            } else {
                items(state.peers, key = { it.id }) { peer ->
                    DeviceCard(
                        peer = peer,
                        onClick = {
                            vm.chooseTarget(peer)
                            picker.launch(arrayOf("*/*"))
                        },
                    )
                }
            }

            if (state.transfers.isNotEmpty()) {
                item(key = "transfers-title", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Transfers") }
                items(state.transfers, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { t ->
                    TransferCard(
                        item = t,
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

    if (state.pendingFiles.isNotEmpty()) {
        SendSheet(
            files = state.pendingFiles,
            peers = state.peers,
            onPick = { vm.sendPendingTo(it) },
            onManual = { showManual = true },
            onDismiss = { vm.clearPending() },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            addresses = state.addresses,
            port = state.port,
            onName = { vm.setDeviceName(it) },
            onBackground = { vm.setReceiveInBackground(it) },
            onAutoAccept = { vm.setAutoAccept(it) },
            onDismiss = { showSettings = false },
        )
    }

    if (showManual) {
        ManualConnectDialog(
            onConnect = {
                vm.connectManually(it)
                showManual = false
            },
            onDismiss = { showManual = false },
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
