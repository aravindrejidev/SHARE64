package com.share64.app.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.share64.app.mobile.MainViewModel
import com.share64.network.PeerDevice

/**
 * The main screen: two tabs, Send and Receive, switched with the floating bar at the bottom. The big
 * title and the Settings button stay at the top of both. The QR code opens as a sheet from the bottom.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val web by vm.webShare.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var qrMode by rememberSaveable { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
    // Files shared into SHARE64 from another app are waiting on the Send tab.
    LaunchedEffect(state.pendingFiles.size) {
        if (state.pendingFiles.isNotEmpty()) onTabChange(MainTab.Send)
    }
    BackHandler(enabled = tab == MainTab.Receive) { onTabChange(MainTab.Send) }

    fun selectFiles() {
        vm.chooseTarget(null)
        picker.launch(arrayOf("*/*"))
    }

    fun sendTo(peer: PeerDevice) {
        if (state.pendingFiles.isNotEmpty()) {
            vm.sendPendingTo(peer)
        } else {
            // Nothing picked yet: choose the files now and send them straight to this device.
            vm.chooseTarget(peer)
            picker.launch(arrayOf("*/*"))
        }
    }

    fun changeSharing(on: Boolean) {
        // Stopping with transfers running deserves a second thought.
        if (!on && state.activeTransfers > 0) confirmStop = true else vm.setSharingEnabled(on)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            FloatingNavBar(
                selected = tab,
                onSelect = onTabChange,
                receiveBadge = state.offers.size,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spring(0.85f, Spring.StiffnessMediumLow)) { it * dir / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it * dir / 4 } + fadeOut(tween(140)))
            },
            label = "mainTab",
        ) { current ->
            when (current) {
                MainTab.Send -> SendScreen(
                    vm = vm,
                    state = state,
                    web = web,
                    padding = padding,
                    onOpenSettings = onOpenSettings,
                    onSelectFiles = { selectFiles() },
                    onSendTo = { sendTo(it) },
                    onOpenQr = {
                        qrMode = if (web != null) 1 else 0
                        showQr = true
                    },
                    onOpenWebShare = {
                        qrMode = 1
                        showQr = true
                    },
                    onTurnOnSharing = { vm.setSharingEnabled(true) },
                )
                MainTab.Receive -> ReceiveScreen(
                    vm = vm,
                    state = state,
                    padding = padding,
                    onOpenSettings = onOpenSettings,
                    onOpenScan = onOpenScan,
                    onSharingChange = { changeSharing(it) },
                    onTurnOnSharing = { vm.setSharingEnabled(true) },
                )
            }
        }
    }

    if (showQr) {
        QrShareSheet(
            state = state,
            web = web,
            initialMode = qrMode,
            onDismiss = { showQr = false },
            onSelectFiles = { selectFiles() },
            onStartWebShare = { vm.startWebShareFromSelected() },
            onStopWebShare = { vm.stopWebShare() },
            onAutoAllow = vm::setWebAutoAllow,
            onApprove = vm::approveWebClient,
            onDeny = vm::denyWebClient,
            onRemove = vm::removeWebClient,
            onTurnOnSharing = { vm.setSharingEnabled(true) },
            onPeerConnected = { peer ->
                showQr = false
                vm.postMessage("${peer.displayName} connected")
            },
        )
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

    // On the Receive tab the offers are cards with Accept and Decline; elsewhere a dialog asks.
    if (tab != MainTab.Receive) {
        state.offer?.let { offer ->
            OfferDialog(
                offer = offer,
                onAccept = { vm.accept(offer.id) },
                onDecline = { vm.decline(offer.id) },
            )
        }
    }
}
