package com.share64.app.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.share64.app.core.AppSettings
import com.share64.app.core.DownloadLocations
import com.share64.app.core.Formatting
import com.share64.app.core.OutgoingFile
import com.share64.app.core.OutgoingFiles
import com.share64.app.core.PortRules
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
import com.share64.app.core.ThemeMode
import com.share64.app.core.TransferItem
import com.share64.app.core.TransferPresenter
import com.share64.core.TransferSnapshot
import com.share64.core.TransferState
import com.share64.network.ConnectInfo
import com.share64.network.DeviceIdentity
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice
import com.share64.network.QrConnectResult
import com.share64.network.QrParse
import com.share64.network.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IncomingOffer(val id: Long, val fileName: String, val sender: String, val size: String)

data class MainUiState(
    val ready: Boolean = false,
    val deviceName: String = "",
    val status: DiscoveryStatus = DiscoveryStatus.Stopped,
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val peers: List<PeerDevice> = emptyList(),
    val transfers: List<TransferItem> = emptyList(),
    val offer: IncomingOffer? = null,
    val pendingFiles: List<OutgoingFile> = emptyList(),
    /** Master switch: false means no engine, no listener, no background service, no wake locks. */
    val sharingEnabled: Boolean = true,
    /** Transfers that are moving (or reconnecting) right now. */
    val activeTransfers: Int = 0,
    /** The text of this device's QR code; null while it cannot be reached (no network, sharing off). */
    val qrPayload: String? = null,
)

/** Where a QR scan stands. */
sealed interface QrUiState {
    data object Idle : QrUiState
    data object Connecting : QrUiState
    data class Connected(val peer: PeerDevice) : QrUiState
    data class Failed(val title: String, val detail: String) : QrUiState
}

data class SettingsState(
    val deviceName: String,
    val receiveInBackground: Boolean,
    val autoAccept: Boolean,
    /** Port the receiver tries first; the active one is [MainUiState.port]. */
    val port: Int,
    /** Human-readable download folder, e.g. "Downloads/SHARE64". */
    val downloadLabel: String,
    val customDownloadFolder: Boolean,
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val amoled: Boolean,
    /** Look for a new release whenever the app opens. */
    val autoUpdateCheck: Boolean,
)

private data class NodeSnapshot(
    val ready: Boolean = false,
    val deviceName: String = "",
    val status: DiscoveryStatus = DiscoveryStatus.Stopped,
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val peers: List<PeerDevice> = emptyList(),
    val transfers: Map<Long, TransferSnapshot> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Application = app
    private val settings = AppSettings(app)
    private val identity = DeviceIdentity(app)

    private val _settings = MutableStateFlow(readSettings())
    val settingsState: StateFlow<SettingsState> = _settings.asStateFlow()

    private val sharing = MutableStateFlow(settings.sharingEnabled)

    private val pending = MutableStateFlow<List<OutgoingFile>>(emptyList())
    /** Set when the user tapped a device card before picking files: send straight to it. */
    private var targetPeer: PeerDevice? = null

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-shot messages for the snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val nodeState: Flow<NodeSnapshot> = ShareRuntime.node.flatMapLatest { node ->
        if (node == null) {
            flowOf(NodeSnapshot())
        } else {
            combine(node.peers, node.transfers, node.discoveryStatus, node.localAddresses, node.listeningPort) {
                    peers, transfers, status, addresses, port ->
                NodeSnapshot(true, node.identity.displayName, status, addresses, port, peers, transfers)
            }
        }
    }

    val state: StateFlow<MainUiState> = combine(
        nodeState,
        pending,
        ShareRuntime.receivedUris,
        ShareRuntime.receivedLocations,
        sharing,
    ) { node, files, received, locations, sharingOn ->
        val ordered = TransferPresenter.order(node.transfers.values)
        val offer = ordered.firstOrNull { it.state == TransferState.OFFERED }
        MainUiState(
            ready = node.ready,
            deviceName = node.deviceName.ifEmpty { identity.displayName },
            status = node.status,
            addresses = node.addresses,
            port = node.port,
            peers = node.peers,
            transfers = ordered.map {
                TransferPresenter.present(it, received.containsKey(it.id), savedTo = locations[it.id])
            },
            offer = offer?.let { IncomingOffer(it.id, it.name, it.peerName ?: "another device", Formatting.bytes(it.totalBytes)) },
            pendingFiles = files,
            qrPayload = qrPayloadFor(node),
            sharingEnabled = sharingOn,
            activeTransfers = ordered.count { it.state == TransferState.ACTIVE || it.state == TransferState.RETRYING },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState(deviceName = identity.displayName))

    // ---------------------------------------------------------------- sending

    /** Call right before launching the file picker from a device card. */
    fun chooseTarget(peer: PeerDevice?) { targetPeer = peer }

    fun onFilesPicked(uris: List<Uri>) {
        val target = targetPeer
        targetPeer = null
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.map { OutgoingFiles.describe(appContext, it) }
            if (target != null) sendTo(target, files) else pending.value = files
        }
    }

    /** Files shared into the app from another app's share sheet. */
    fun onSharedUris(uris: List<Uri>) {
        targetPeer = null
        onFilesPicked(uris)
    }

    fun sendPendingTo(peer: PeerDevice) {
        val files = pending.value
        pending.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { sendTo(peer, files) }
    }

    fun clearPending() { pending.value = emptyList() }

    private suspend fun sendTo(peer: PeerDevice, files: List<OutgoingFile>) {
        val node = ShareRuntime.node.value
        if (node == null) {
            _messages.send(
                if (settings.sharingEnabled) "SHARE64 is still starting — try again in a moment"
                else "Sharing is off. Turn it on first.",
            )
            return
        }
        var started = 0
        for (f in files) if (OutgoingFiles.send(appContext, node, peer, f) != 0L) started++
        _messages.send(
            when {
                started == 0 -> "Couldn't open the selected file${if (files.size == 1) "" else "s"}"
                started < files.size -> "Sending $started of ${files.size} files to ${peer.displayName}"
                started == 1 -> "Sending ${files[0].name} to ${peer.displayName}"
                else -> "Sending $started files to ${peer.displayName}"
            },
        )
    }

    // ---------------------------------------------------------------- transfer actions

    fun accept(id: Long) { ShareRuntime.node.value?.accept(id) }
    fun decline(id: Long) { ShareRuntime.node.value?.decline(id) }
    fun cancel(id: Long) { ShareRuntime.node.value?.cancel(id) }
    fun dismiss(id: Long) { ShareRuntime.node.value?.dismiss(id) }

    fun receivedUri(id: Long): Uri? = ShareRuntime.receivedUris.value[id]

    fun connectManually(input: String) {
        viewModelScope.launch {
            val node = ShareRuntime.node.value
            if (node == null) {
                _messages.send(
                    if (settings.sharingEnabled) "SHARE64 is still starting — try again in a moment"
                    else "Sharing is off. Turn it on first.",
                )
                return@launch
            }
            node.connectManually(input).fold(
                onSuccess = { _messages.send("Added ${it.displayName}") },
                onFailure = { _messages.send(it.message ?: "Could not connect") },
            )
        }
    }

    fun postMessage(text: String) { _messages.trySend(text) }

    // ---------------------------------------------------------------- QR pairing

    private val _qr = MutableStateFlow<QrUiState>(QrUiState.Idle)
    val qr: StateFlow<QrUiState> = _qr.asStateFlow()

    private fun qrPayloadFor(node: NodeSnapshot): String? {
        if (!node.ready || node.port <= 0 || node.addresses.isEmpty()) return null
        val me = identity.local()
        return QrPayload.encode(ConnectInfo(me.id, node.deviceName.ifEmpty { me.displayName }, me.type, node.port, node.addresses))
    }

    /** A code came out of the camera. Only the first one counts until the result is dealt with. */
    fun onQrScanned(text: String) {
        if (_qr.value != QrUiState.Idle) return
        _qr.value = QrUiState.Connecting
        viewModelScope.launch {
            val node = ShareRuntime.node.value
            _qr.value = if (node == null) {
                if (settings.sharingEnabled) QrUiState.Failed("Still starting", "SHARE64 is getting ready. Try again in a moment.")
                else QrUiState.Failed("Sharing is off", "Turn sharing on, then scan again.")
            } else {
                when (val r = node.connectFromQr(text)) {
                    is QrConnectResult.Connected -> QrUiState.Connected(r.peer)
                    is QrConnectResult.Unreachable -> QrUiState.Failed(
                        "Couldn't reach ${r.info.name}",
                        "Both devices must be on the same Wi-Fi, or one on the other's hotspot. Then try again.",
                    )
                    is QrConnectResult.Incompatible -> QrUiState.Failed(
                        "Different SHARE64 version",
                        "${r.info.name} runs a version that can't talk to this one. Update both apps.",
                    )
                    is QrConnectResult.OwnCode -> QrUiState.Failed("That's this phone's code", "Scan the code shown on the other device.")
                    is QrConnectResult.Invalid -> when (r.reason) {
                        QrParse.Reason.NOT_SHARE64 -> QrUiState.Failed("Not a SHARE64 code", "Scan the code shown in SHARE64 on the other device.")
                        QrParse.Reason.NEWER_VERSION -> QrUiState.Failed("Newer SHARE64 code", "Update SHARE64 on this phone, then scan again.")
                        else -> QrUiState.Failed("Damaged code", "Ask the other device to show its code again.")
                    }
                }
            }
        }
    }

    /** Back to scanning (after a failure, or when leaving the screen). */
    fun resetQr() {
        _qr.value = QrUiState.Idle
    }

    // ---------------------------------------------------------------- master switch

    /**
     * Starts or stops the whole sharing stack. Off: the native engine closes its sockets, the
     * background service stops, wake locks are released and the notification disappears; unfinished
     * receives keep their partial data and resume when the file is sent again. On: everything
     * starts again from scratch.
     */
    fun setSharingEnabled(enabled: Boolean) {
        if (settings.sharingEnabled == enabled && sharing.value == enabled) return
        settings.sharingEnabled = enabled
        sharing.value = enabled
        if (enabled) {
            ShareService.start(appContext)
            postMessage("Sharing is on")
        } else {
            pending.value = emptyList()
            targetPeer = null
            ShareService.stop(appContext)
            postMessage("Sharing is off")
        }
    }

    // ---------------------------------------------------------------- settings

    fun setDeviceName(name: String) {
        if (name.isBlank()) return
        identity.displayName = name
        _settings.value = readSettings()
        ShareService.restart(appContext) // the advertised name is fixed per engine instance
    }

    fun setPort(port: Int) {
        if (port !in PortRules.MIN..PortRules.MAX || port == settings.port) return
        settings.port = port
        _settings.value = readSettings()
        ShareService.restart(appContext) // re-binds the listener
        postMessage("Port set to $port")
    }

    /** Pass null to go back to Downloads/SHARE64. */
    fun setDownloadFolder(tree: Uri?) {
        val old = settings.downloadTreeUri
        if (tree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                appContext.contentResolver.takePersistableUriPermission(tree, flags)
            } catch (_: SecurityException) {
                postMessage("Couldn't get permission for that folder")
                return
            }
            if (!DownloadLocations.isUsable(appContext, tree)) {
                postMessage("That folder can't be written to. Pick a different one.")
                return
            }
            settings.downloadTreeUri = tree.toString()
        } else {
            settings.downloadTreeUri = null
        }
        if (old != null && old != tree?.toString()) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _settings.value = readSettings()
        postMessage("Files will be saved to ${_settings.value.downloadLabel}")
    }

    fun setReceiveInBackground(enabled: Boolean) {
        settings.receiveInBackground = enabled
        _settings.value = readSettings()
    }

    fun setAutoAccept(enabled: Boolean) {
        settings.autoAccept = enabled
        _settings.value = readSettings()
        ShareService.restart(appContext)
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _settings.value = readSettings()
    }

    fun setDynamicColor(enabled: Boolean) {
        settings.dynamicColor = enabled
        _settings.value = readSettings()
    }

    fun setAmoled(enabled: Boolean) {
        settings.amoled = enabled
        _settings.value = readSettings()
    }

    fun setAutoUpdateCheck(enabled: Boolean) {
        settings.autoUpdateCheck = enabled
        _settings.value = readSettings()
    }

    private fun readSettings(): SettingsState {
        val tree = settings.downloadTreeUri
        return SettingsState(
            deviceName = identity.displayName,
            receiveInBackground = settings.receiveInBackground,
            autoAccept = settings.autoAccept,
            port = settings.port,
            downloadLabel = DownloadLocations.label(tree),
            customDownloadFolder = tree != null,
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            amoled = settings.amoled,
            autoUpdateCheck = settings.autoUpdateCheck,
        )
    }
}
