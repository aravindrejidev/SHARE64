package com.share64.app.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.mobile.MainUiState
import com.share64.app.mobile.MainViewModel
import com.share64.app.mobile.QrUiState
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice
import kotlin.math.roundToInt

/**
 * Pairing with a QR code, in two tabs: "Show my code" (this device's address, name and id as a code)
 * and "Scan code" (the in-app camera). Whoever scans connects at once; the other device notices the
 * visit by itself and adds the scanner back, so both sides end up seeing each other.
 */
@Composable
fun ConnectScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val qr by vm.qr.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var dismissedArrival by rememberSaveable { mutableStateOf<String?>(null) }
    // Devices that show up while this screen is open on the "show" tab are most likely the one that scanned us.
    val knownIds = remember { state.peers.map { it.id }.toSet() }
    val arrival: PeerDevice? =
        if (tab == 0 && qr == QrUiState.Idle) {
            state.peers.firstOrNull { it.id !in knownIds && it.reachable && it.id != dismissedArrival }
        } else {
            null
        }
    val paired: PeerDevice? = (qr as? QrUiState.Connected)?.peer ?: arrival

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.onFilesPicked(uris)
            onBack()
        }
    }
    DisposableEffect(Unit) { onDispose { vm.resetQr() } }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                    .padding(horizontal = 16.dp),
            ) {
                ScreenHeader(title = "Connect", subtitle = "Pair two devices with a QR code", onBack = onBack)
                Spacer(Modifier.height(8.dp))
                ExpressiveToggleGroup(
                    options = listOf("Show my code", "Scan code"),
                    selectedIndex = tab,
                    onSelect = {
                        tab = it
                        torch = false
                        vm.resetQr()
                    },
                )
                Spacer(Modifier.height(16.dp))
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(spring(0.8f, Spring.StiffnessMediumLow)) { it * dir / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(180)) { -it * dir / 3 } + fadeOut(tween(140)))
                    },
                    label = "connectTab",
                ) { page ->
                    if (page == 0) {
                        ShowCodePane(state = state, onTurnOnSharing = { vm.setSharingEnabled(true) })
                    } else {
                        ScanPane(
                            qr = qr,
                            torch = torch,
                            onTorch = { torch = it },
                            onCode = vm::onQrScanned,
                            onRetry = vm::resetQr,
                            onTypeAddress = { showManual = true },
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = paired,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(spring(0.75f, Spring.StiffnessMediumLow), initialScale = 0.92f)) togetherWith fadeOut(tween(160))
            },
            label = "paired",
        ) { peer ->
            if (peer != null) {
                PairedOverlay(
                    peer = peer,
                    onSend = {
                        vm.chooseTarget(peer)
                        picker.launch(arrayOf("*/*"))
                    },
                    onDone = {
                        dismissedArrival = peer.id
                        vm.resetQr()
                        onBack()
                    },
                )
            } else {
                Spacer(Modifier)
            }
        }
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
}

// ---------------------------------------------------------------------------------------------
// Show my code
// ---------------------------------------------------------------------------------------------

@Composable
private fun ShowCodePane(state: MainUiState, onTurnOnSharing: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val payload = state.qrPayload
        when {
            !state.sharingEnabled -> InfoCard(
                title = "Sharing is off",
                text = "Turn sharing on to show your code.",
                buttonLabel = "Turn sharing on",
                onButton = onTurnOnSharing,
            )
            payload == null -> InfoCard(
                title = if (state.status is DiscoveryStatus.NoNetwork) "No Wi-Fi or hotspot" else "Getting ready…",
                text = "Join a Wi-Fi network, or turn on a hotspot on one device and connect the other to it. " +
                    "Your code appears as soon as this device can be reached.",
                loading = state.status !is DiscoveryStatus.NoNetwork,
            )
            else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PulseRings(modifier = Modifier.matchParentSize(), color = scheme.primary)
                    Surface(
                        shape = RoundedCornerShape(40.dp),
                        color = Color.White,
                        shadowElevation = 10.dp,
                        modifier = Modifier.fillMaxWidth(0.68f).aspectRatio(1f).popIn(0),
                    ) {
                        QrCodeView(text = payload, modifier = Modifier.fillMaxSize().padding(12.dp))
                    }
                }
                Text(
                    state.deviceName.ifBlank { "This device" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.popIn(1),
                )
                val running = state.status as? DiscoveryStatus.Running
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.popIn(2)) {
                    if (running != null) Pill(running.network.label, scheme.tertiaryContainer, scheme.onTertiaryContainer)
                    Pill(
                        state.addresses.firstOrNull().orEmpty() + if (state.port > 0) ":${state.port}" else "",
                        scheme.secondaryContainer,
                        scheme.onSecondaryContainer,
                    )
                }
                Text(
                    "On the other device open SHARE64, tap Connect, choose Scan code and point its camera here. " +
                        "Both devices will then see each other.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp).popIn(3),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    buttonLabel: String? = null,
    onButton: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth().popIn(0),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 40.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loading) {
                ExpressiveLoading(indicatorSize = 56.dp)
            } else {
                CookieBadge(size = 64.dp, color = scheme.tertiaryContainer, lobes = 9, amplitude = 0.08f, spin = true) {
                    Icon(ShareIcons.QrCode, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(30.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (buttonLabel != null) ExpressiveButton(onClick = onButton) { Text(buttonLabel) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scan code
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanPane(
    qr: QrUiState,
    torch: Boolean,
    onTorch: (Boolean) -> Unit,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onTypeAddress: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    var cameraBroken by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) refused = true
    }
    // The person just chose "Scan code": ask for the camera right away.
    LaunchedEffect(Unit) {
        if (!granted && context.hasCamera()) permission.launch(Manifest.permission.CAMERA)
    }
    val blockedForGood = refused && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !context.hasCamera() || cameraBroken -> InfoCard(
                    title = "Camera not available",
                    text = "This device can't scan. Show your code on the other device, or type the address below.",
                )
                !granted -> InfoCard(
                    title = if (blockedForGood) "Camera is blocked" else "Camera access needed",
                    text = if (blockedForGood) {
                        "SHARE64 only uses the camera to read the QR code. Allow it in the app settings."
                    } else {
                        "SHARE64 only uses the camera to read the QR code. Nothing is recorded or sent anywhere."
                    },
                    buttonLabel = if (blockedForGood) "Open settings" else "Allow camera",
                    onButton = {
                        if (blockedForGood) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        } else {
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                else -> Viewfinder(
                    qr = qr,
                    torch = torch,
                    onTorch = onTorch,
                    onCode = onCode,
                    onRetry = onRetry,
                    onCameraFailure = { cameraBroken = true },
                )
            }
        }
        ExpressiveButton(onClick = onTypeAddress, kind = ButtonKind.Text) { Text("Can't scan? Type the address") }
    }
}

@Composable
private fun Viewfinder(
    qr: QrUiState,
    torch: Boolean,
    onTorch: (Boolean) -> Unit,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onCameraFailure: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val busy = qr !is QrUiState.Idle
    Box(
        modifier = Modifier
            .fillMaxSize()
            .heightIn(min = 300.dp)
            .clip(RoundedCornerShape(44.dp))
            .popIn(0),
    ) {
        QrScannerView(
            modifier = Modifier.fillMaxSize(),
            torchOn = torch,
            paused = busy,
            onCode = { text ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCode(text)
            },
            onCameraFailure = onCameraFailure,
        )
        ScanFrameOverlay(color = Color.White, locked = qr is QrUiState.Connecting, modifier = Modifier.fillMaxSize())

        // flashlight
        CookieIconButton(
            onClick = { onTorch(!torch) },
            icon = ShareIcons.Flash,
            contentDescription = "Flashlight",
            container = if (torch) scheme.tertiary else Color.Black.copy(alpha = 0.45f),
            content = if (torch) scheme.onTertiary else Color.White,
            buttonSize = 48.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )

        // hint
        AnimatedVisibility(
            visible = qr is QrUiState.Idle,
            modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
                Text(
                    "Point at the code on the other device",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        // connecting / failed
        AnimatedVisibility(
            visible = qr is QrUiState.Connecting || qr is QrUiState.Failed,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) {
                when (qr) {
                    is QrUiState.Failed -> ScanFailure(title = qr.title, detail = qr.detail, onRetry = onRetry)
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ExpressiveLoading(indicatorSize = 72.dp, color = Color.White)
                        Text("Connecting…", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                }
            }
        }
    }
}

/** The error card shakes once, so a failed scan is felt as well as read. */
@Composable
private fun ScanFailure(title: String, detail: String, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shake = remember { Animatable(0f) }
    LaunchedEffect(title, detail) {
        repeat(3) {
            shake.animateTo(14f, tween(55))
            shake.animateTo(-14f, tween(55))
        }
        shake.animateTo(0f, spring(0.4f, Spring.StiffnessMedium))
    }
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
        modifier = Modifier
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CookieBadge(size = 52.dp, color = scheme.error, lobes = 8, amplitude = 0.08f) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = scheme.onError, modifier = Modifier.size(26.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            ExpressiveButton(onClick = onRetry) { Text("Scan again") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Paired
// ---------------------------------------------------------------------------------------------

@Composable
private fun PairedOverlay(peer: PeerDevice, onSend: () -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.surface.copy(alpha = 0.97f)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PairedBurst(
                cookie = scheme.primary,
                onCookie = scheme.onPrimary,
                dots = listOf(scheme.primary, scheme.tertiary, scheme.secondary),
            )
            Spacer(Modifier.height(8.dp))
            Text("CONNECTED", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.popIn(2))
            Text(
                peer.displayName,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.popIn(3),
            )
            Text(
                "${typeLabel(peer.type)} · ${peer.host}",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.popIn(4),
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.popIn(5)) {
                ExpressiveButton(onClick = onSend) { Text("Send a file") }
                ExpressiveButton(onClick = onDone, kind = ButtonKind.Tonal) { Text("Done") }
            }
        }
    }
}
