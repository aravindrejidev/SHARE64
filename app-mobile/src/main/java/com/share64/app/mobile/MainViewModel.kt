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
import com.share64.app.core.SavedFile
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
import com.share64.app.core.ThemeMode
import com.share64.app.core.TransferItem
import com.share64.app.core.TransferPresenter
import com.share64.app.core.WebDownloadException
import com.share64.app.core.WebDownloader
import com.share64.app.core.WebDownloads
import com.share64.app.core.WebFileInfo
import com.share64.app.core.WebShareHost
import com.share64.core.TransferSnapshot
import com.share64.core.TransferState
import com.share64.network.ConnectInfo
import com.share64.network.DeviceIdentity
import com.share64.network.DiscoveryStatus
import com.share64.network.PeerDevice
import com.share64.network.QrConnectResult
import com.share64.network.QrParse
import com.share64.network.QrPayload
import com.share64.network.web.WebClient
import com.share64.network.web.WebShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    /** Every device that is waiting for this one to accept its file, newest first. */
    val offers: List<IncomingOffer> = emptyList(),
    /** The files ticked on the Send screen, waiting for a device (or a QR code) to be chosen. */
    val pendingFiles: List<OutgoingFile> = emptyList(),
    /** Master switch: false means no engine, no listener, no background service, no wake locks. */
    val sharingEnabled: Boolean = true,
    /** Transfers that are moving (or reconnecting) right now. */
    val activeTransfers: Int = 0,
    /** The text of this device's QR code; null while it cannot be reached (no network, sharing off). */
    val qrPayload: String? = null,
)

/** A running "share with anyone" session, as the screen shows it. */
data class WebShareUi(
    /** One link per local address (home Wi-Fi, hotspot, ...), best first. */
    val urls: List<String>,
    val sender: String,
    val files: List<WebFileInfo>,
    val totalBytes: Long,
    val clients: List<WebClient>,
    val autoAllow: Boolean,
)

/** Receiving a "share with anyone" session inside the app (after scanning its QR code). */
sealed interface WebDownloadUi {
    data object Idle : WebDownloadUi
    data class Connecting(val host: String) : WebDownloadUi
    data class Waiting(val sender: String) : WebDownloadUi
    data class Ready(val sender: String, val files: List<WebFileInfo>) : WebDownloadUi
    data class Downloading(val sender: String, val files: List<WebFileInfo>, val index: Int, val doneBytes: Long, val totalBytes: Long) : WebDownloadUi
    data class Done(val sender: String, val saved: List<SavedFile>) : WebDownloadUi
    data class Failed(val title: String, val detail: String) : WebDownloadUi
}

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
        val waiting = ordered.filter { it.state == TransferState.OFFERED }
            .map { IncomingOffer(it.id, it.name, it.peerName ?: "another device", Formatting.bytes(it.totalBytes)) }
        val offer = waiting.firstOrNull()
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
            offer = offer,
            offers = waiting,
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
            if (target != null) sendTo(target, files) else pending.value = (pending.value + files).distinctBy { it.uri.toString() }
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

    /** Takes one file out of the selection on the Send screen. */
    fun removePending(file: OutgoingFile) {
        pending.value = pending.value.filterNot { it.uri == file.uri }
    }

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
        if (openWebLink(text)) return // a "share with anyone" link: download through the app instead of pairing
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

    // ---------------------------------------------------------------- share with anyone: hosting

    @OptIn(ExperimentalCoroutinesApi::class)
    private val addressesFlow: Flow<List<String>> = ShareRuntime.node.flatMapLatest { it?.localAddresses ?: flowOf(emptyList()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val webShare: StateFlow<WebShareUi?> = WebShareHost.session.flatMapLatest { s ->
        if (s == null) {
            flowOf(null)
        } else {
            combine(s.server.clients, s.server.autoAllow, addressesFlow) { clients, auto, addresses ->
                WebShareUi(addresses.map { s.server.url(it) }, s.sender, s.files, s.totalBytes, clients, auto)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startWebShare(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { startWebShareFiles(uris.map { OutgoingFiles.describe(appContext, it) }) }
    }

    /** Starts a "share with anyone" session with the files selected on the Send screen (the selection stays). */
    fun startWebShareFromSelected() {
        val files = pending.value
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { startWebShareFiles(files) }
    }

    private suspend fun startWebShareFiles(files: List<OutgoingFile>) {
        if (ShareRuntime.node.value == null) {
            _messages.send(
                if (settings.sharingEnabled) "SHARE64 is still starting — try again in a moment"
                else "Sharing is off. Turn it on first.",
            )
            return
        }
        if (WebShareHost.start(appContext, files, identity.displayName, settings.webAutoAllow) == null) {
            _messages.send("Couldn't start web sharing")
        }
    }

    fun stopWebShare() = WebShareHost.stop()

    fun setWebAutoAllow(on: Boolean) {
        settings.webAutoAllow = on
        WebShareHost.session.value?.server?.setAutoAllow(on)
    }

    fun approveWebClient(id: String) { WebShareHost.session.value?.server?.approve(id) }
    fun denyWebClient(id: String) { WebShareHost.session.value?.server?.deny(id) }
    fun removeWebClient(id: String) { WebShareHost.session.value?.server?.remove(id) }

    // ---------------------------------------------------------------- share with anyone: receiving in the app

    private val _web = MutableStateFlow<WebDownloadUi>(WebDownloadUi.Idle)
    val webDownload: StateFlow<WebDownloadUi> = _web.asStateFlow()
    private var webJob: Job? = null
    private var webDownloader: WebDownloader? = null
    private var webLink: WebShareLink? = null
    private var closedLink: WebShareLink? = null
    private var closedAtMs = 0L

    private fun setWeb(state: WebDownloadUi) {
        _web.value = state
        ShareRuntime.setWebBusy(state is WebDownloadUi.Connecting || state is WebDownloadUi.Waiting || state is WebDownloadUi.Downloading)
    }

    /**
     * A scanned QR code or opened link that points at a "share with anyone" session. Returns true when it
     * was one (the app then connects and shows the file list); false for anything else.
     */
    fun openWebLink(text: String): Boolean {
        val link = WebShareLink.parse(text) ?: return false
        if (_web.value != WebDownloadUi.Idle) return true
        // the camera may still be pointing at the code we just closed
        if (link == closedLink && System.currentTimeMillis() - closedAtMs < 6_000) return true
        startWebSession(link)
        return true
    }

    private fun startWebSession(link: WebShareLink) {
        webLink = link
        setWeb(WebDownloadUi.Connecting(link.host))
        val downloader = WebDownloads.create(appContext, link, identity.displayName, settings.downloadTreeUri)
        webDownloader = downloader
        webJob = viewModelScope.launch {
            try {
                val manifest = downloader.connect { sender -> setWeb(WebDownloadUi.Waiting(sender)) }
                setWeb(WebDownloadUi.Ready(manifest.sender, manifest.files))
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebDownloadException) {
                setWeb(webFailure(e))
            } catch (e: Exception) {
                setWeb(WebDownloadUi.Failed("Something went wrong", e.message.orEmpty()))
            }
        }
    }

    /** The person looked at the file list and chose to download everything. */
    fun startWebDownload() {
        val ready = _web.value as? WebDownloadUi.Ready ?: return
        val downloader = webDownloader ?: return
        val total = ready.files.sumOf { maxOf(it.size, 0L) }
        setWeb(WebDownloadUi.Downloading(ready.sender, ready.files, 0, 0, total))
        webJob = viewModelScope.launch {
            var lastUi = 0L
            try {
                val saved = downloader.downloadAll(ready.files) { index, _, done ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 120) { // ~8 updates a second is plenty for a progress bar
                        lastUi = now
                        setWeb(WebDownloadUi.Downloading(ready.sender, ready.files, index, done, total))
                    }
                }
                setWeb(WebDownloadUi.Done(ready.sender, saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebDownloadException) {
                setWeb(webFailure(e))
            } catch (e: Exception) {
                setWeb(WebDownloadUi.Failed("Download failed", e.message.orEmpty()))
            }
        }
    }

    /** Cancel, or close the result: back to normal. A partly downloaded file is kept and resumes next time. */
    fun closeWeb() {
        webDownloader?.cancel()
        webJob?.cancel()
        webJob = null
        webDownloader = null
        closedLink = webLink
        closedAtMs = System.currentTimeMillis()
        setWeb(WebDownloadUi.Idle)
    }

    fun retryWeb() {
        val link = webLink ?: return closeWeb()
        webDownloader?.cancel()
        webJob?.cancel()
        startWebSession(link)
    }

    private fun webFailure(e: WebDownloadException): WebDownloadUi.Failed = when (e.kind) {
        WebDownloadException.Kind.UNREACHABLE -> WebDownloadUi.Failed("Couldn't reach the sender", "Be on the same Wi-Fi, or on the sender's hotspot, then try again.")
        WebDownloadException.Kind.DENIED -> WebDownloadUi.Failed("The sender declined", "They didn't allow this device.")
        WebDownloadException.Kind.FULL -> WebDownloadUi.Failed("This share is full", "Ask the sender to remove a device, then try again.")
        WebDownloadException.Kind.TIMED_OUT -> WebDownloadUi.Failed("No answer", "The sender didn't tap Allow in time. Ask them and try again.")
        WebDownloadException.Kind.REVOKED -> WebDownloadUi.Failed("Access was removed", "The sender stopped allowing this device or ended the share.")
        WebDownloadException.Kind.DISK -> WebDownloadUi.Failed("Not enough space", "Free up some storage and try again; the part already downloaded is kept.")
        WebDownloadException.Kind.FAILED -> WebDownloadUi.Failed("Download failed", e.message.orEmpty().ifBlank { "Check the connection and try again." })
    }

    override fun onCleared() {
        ShareRuntime.setWebBusy(false)
        super.onCleared()
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
            WebShareHost.stop()
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
