package com.share64.app.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.AppInfo
import com.share64.app.core.TransferItem
import com.share64.app.core.R as CoreR
import com.share64.app.tv.TvUiState
import com.share64.app.tv.TvViewModel
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerTxt
import kotlinx.coroutines.delay

@Composable
fun TvHomeScreen(vm: TvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val firstItem = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onFilesPicked(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Many TV boxes (Fire TV, some Android TV builds) ship without a system file picker.
    fun pickFiles() {
        try {
            picker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            toast = "This TV has no file picker. Send files from your phone to this TV instead."
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { toast = it } }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4_000)
            toast = null
        }
    }
    // Start with focus on the first tile so the remote works immediately.
    LaunchedEffect(Unit) {
        delay(500)
        runCatching { firstItem.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 48 x 27 dp keeps content inside the TV overscan-safe area.
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SHARE64", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Visible as ${state.deviceName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton("About", onClick = { showAbout = true })
                    TvButton("Settings", onClick = { showSettings = true })
                }
            }
            StatusLine(state)

            SectionTitle("Send to a nearby device")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            ) {
                items(state.peers, key = { it.id }) { peer ->
                    val isFirst = peer.id == state.peers.first().id
                    DeviceTile(
                        peer = peer,
                        modifier = if (isFirst) Modifier.focusRequester(firstItem) else Modifier,
                        onClick = {
                            vm.chooseTarget(peer)
                            pickFiles()
                        },
                    )
                }
                item(key = "manual") {
                    ActionTile(
                        title = "Add by address",
                        subtitle = if (state.peers.isEmpty()) "Nothing found yet" else "Enter an IP address",
                        modifier = if (state.peers.isEmpty()) Modifier.focusRequester(firstItem) else Modifier,
                        onClick = { showManual = true },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                TransferColumn(
                    title = "Incoming",
                    emptyText = "Files sent to this TV appear here.",
                    items = state.incoming,
                    vm = vm,
                    context = context,
                    onMessage = { toast = it },
                    modifier = Modifier.weight(1f),
                )
                TransferColumn(
                    title = "Outgoing",
                    emptyText = "Pick a device above to send files.",
                    items = state.outgoing,
                    vm = vm,
                    context = context,
                    onMessage = { toast = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        toast?.let { text ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            ) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }

    state.offer?.let { offer ->
        val acceptFocus = remember(offer.id) { FocusRequester() }
        TvDialog(onDismiss = {}) { // must be answered with Accept or Decline
            Text("Incoming file", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${offer.sender} wants to send you “${offer.fileName}” (${offer.size}).",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Accept", onClick = { vm.accept(offer.id) }, primary = true, modifier = Modifier.focusRequester(acceptFocus))
                TvButton("Decline", onClick = { vm.decline(offer.id) })
            }
            LaunchedEffect(offer.id) {
                delay(150)
                runCatching { acceptFocus.requestFocus() }
            }
        }
    }

    if (showManual) {
        var text by remember { mutableStateOf("") }
        TvDialog(onDismiss = { showManual = false }) {
            Text("Add by address", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Enter the IP address shown in the other device's settings, e.g. 192.168.1.20 or 192.168.1.20:52364.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("IP address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) { vm.connectManually(text); showManual = false }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(
                    "Connect",
                    onClick = { vm.connectManually(text); showManual = false },
                    primary = true,
                    enabled = text.isNotBlank(),
                )
                TvButton("Cancel", onClick = { showManual = false })
            }
        }
    }

    if (showAbout) {
        val build = remember { AppInfo.read(context) }
        val closeFocus = remember { FocusRequester() }
        TvDialog(onDismiss = { showAbout = false }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(
                    modifier = Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF070607)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(CoreR.drawable.logo_mark),
                        contentDescription = "SHARE64 logo",
                        modifier = Modifier.padding(22.dp),
                    )
                }
                Column {
                    Text(AppInfo.APP_NAME, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Version ${build.versionName} · ${build.abiLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(AppInfo.TAGLINE, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Developer: ${AppInfo.DEVELOPER_NAME} · github.com/${AppInfo.DEVELOPER_GITHUB}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Source code and releases: ${AppInfo.REPO_URL}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TvButton("Close", onClick = { showAbout = false }, primary = true, modifier = Modifier.focusRequester(closeFocus))
            LaunchedEffect(Unit) {
                delay(150)
                runCatching { closeFocus.requestFocus() }
            }
        }
    }

    if (showSettings) {
        var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
        TvDialog(onDismiss = { showSettings = false }) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PeerTxt.MAX_NAME_CHARS) },
                singleLine = true,
                label = { Text("Device name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) vm.setDeviceName(name.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(
                    "Save name",
                    onClick = { vm.setDeviceName(name.trim()) },
                    primary = true,
                    enabled = name.isNotBlank() && name.trim() != settings.deviceName,
                )
                TvButton("Close", onClick = { showSettings = false })
            }
            TvToggleRow(
                title = "Keep receiving in background",
                subtitle = "Stay visible to other devices while SHARE64 is closed.",
                checked = settings.receiveInBackground,
                onChange = { vm.setReceiveInBackground(it) },
            )
            TvToggleRow(
                title = "Accept files automatically",
                subtitle = "Anyone on your Wi-Fi can send files without asking.",
                checked = settings.autoAccept,
                onChange = { vm.setAutoAccept(it) },
            )
            Text(
                if (state.addresses.isEmpty()) "Not connected to a network"
                else "Your address: " + state.addresses.joinToString(", ") { if (state.port > 0) "$it:${state.port}" else it },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Received files are saved in ${vm.saveLocation}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusLine(state: TvUiState) {
    val problem = state.status is DiscoveryStatus.NoNetwork || state.status is DiscoveryStatus.Error
    val text = when (val s = state.status) {
        is DiscoveryStatus.NoNetwork -> "No network. Connect this TV to the same Wi-Fi or Ethernet as the other device."
        is DiscoveryStatus.Error -> "Discovery problem (${s.message}). Retrying…"
        is DiscoveryStatus.Running -> "Ready to receive · ${s.address}${if (state.port > 0) ":${state.port}" else ""}"
        is DiscoveryStatus.Stopped -> "Starting…"
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TransferColumn(
    title: String,
    emptyText: String,
    items: List<TransferItem>,
    vm: TvViewModel,
    context: Context,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionTitle(title)
        if (items.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                items(items, key = { it.id }) { t ->
                    TvTransferRow(
                        item = t,
                        onCancel = { vm.cancel(t.id) },
                        onDismiss = { vm.dismiss(t.id) },
                        onOpen = {
                            val uri = vm.receivedUri(t.id)
                            if (uri == null || !openFile(context, uri)) onMessage("No app on this TV can open that file")
                        },
                    )
                }
            }
        }
    }
}

private fun openFile(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
