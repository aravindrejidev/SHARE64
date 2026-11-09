package com.share64.app.tv

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.share64.app.core.AppSettings
import com.share64.app.core.Formatting
import com.share64.app.core.OutgoingFile
import com.share64.app.core.OutgoingFiles
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
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

data class TvOffer(val id: Long, val fileName: String, val sender: String, val size: String)

data class TvUiState(
    val ready: Boolean = false,
    val deviceName: String = "",
    val status: DiscoveryStatus = DiscoveryStatus.Stopped,
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val peers: List<PeerDevice> = emptyList(),
    val incoming: List<TransferItem> = emptyList(),
    val outgoing: List<TransferItem> = emptyList(),
    val offer: TvOffer? = null,
)

data class TvSettings(val deviceName: String, val receiveInBackground: Boolean, val autoAccept: Boolean)

private data class NodeSnapshot(
    val ready: Boolean = false,
    val deviceName: String = "",
    val status: DiscoveryStatus = DiscoveryStatus.Stopped,
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val peers: List<PeerDevice> = emptyList(),
    val transfers: Map<Long, TransferSnapshot> = emptyMap(),
)

class TvViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Application = app
    private val settings = AppSettings(app)
    private val identity = DeviceIdentity(app)

    private val _settings = MutableStateFlow(readSettings())
    val settingsState: StateFlow<TvSettings> = _settings.asStateFlow()

    /** Where finished files end up, for the settings screen. */
    val saveLocation: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/SHARE64"
        else "Android/data/${app.packageName}/files/Received"

    private var targetPeer: PeerDevice? = null

    private val _messages = Channel<String>(Channel.BUFFERED)
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

    val state: StateFlow<TvUiState> = combine(nodeState, ShareRuntime.receivedUris, ShareRuntime.receivedLocations) { node, received, locations ->
        val ordered = TransferPresenter.order(node.transfers.values)
        val items = ordered.map { TransferPresenter.present(it, received.containsKey(it.id), savedTo = locations[it.id]) }
        val offer = ordered.firstOrNull { it.state == TransferState.OFFERED }
        TvUiState(
            ready = node.ready,
            deviceName = node.deviceName.ifEmpty { identity.displayName },
            status = node.status,
            addresses = node.addresses,
            port = node.port,
            peers = node.peers,
            incoming = items.filter { !it.isSend },
            outgoing = items.filter { it.isSend },
            offer = offer?.let { TvOffer(it.id, it.name, it.peerName ?: "another device", Formatting.bytes(it.totalBytes)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvUiState(deviceName = identity.displayName))

    // ---------------------------------------------------------------- sending

    /** Remember which device was selected; the file picker result is then sent straight to it. */
    fun chooseTarget(peer: PeerDevice) { targetPeer = peer }

    fun onFilesPicked(uris: List<Uri>) {
        val target = targetPeer
        targetPeer = null
        if (uris.isEmpty() || target == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.map { OutgoingFiles.describe(appContext, it) }
            sendTo(target, files)
        }
    }

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
        ShareService.restart(appContext)
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

    private fun readSettings() = TvSettings(identity.displayName, settings.receiveInBackground, settings.autoAccept)
}
