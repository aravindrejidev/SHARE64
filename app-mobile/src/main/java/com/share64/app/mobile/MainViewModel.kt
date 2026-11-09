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
import com.share64.network.DeviceIdentity
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice
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
)

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
    ) { node, files, received, locations ->
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
            _messages.send("SHARE64 is still starting — try again in a moment")
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
                _messages.send("SHARE64 is still starting — try again in a moment")
                return@launch
            }
            node.connectManually(input).fold(
                onSuccess = { _messages.send("Added ${it.displayName}") },
                onFailure = { _messages.send(it.message ?: "Could not connect") },
            )
        }
    }

    fun postMessage(text: String) { _messages.trySend(text) }

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
        )
    }
}
