package com.share64.network

import android.content.Context
import android.os.ParcelFileDescriptor
import com.share64.core.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One SHARE64 endpoint: native engine (receiver + senders) + DNS-SD discovery.
 *
 * Typical use from a foreground service / ViewModel:
 * ```
 * val node = Share64Node(context, saveDir = File(getExternalFilesDir(null), "Received"))
 * node.start()
 * node.peers.collect { ... }          // who is around
 * node.transfers.collect { ... }      // progress, offers, results
 * node.send(peer, file)
 * ```
 */
class Share64Node(
    context: Context,
    private val saveDir: File,
    autoAccept: Boolean = false,
    val identity: DeviceIdentity = DeviceIdentity(context),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)

    val engine = TransferEngine(deviceName = identity.displayName, autoAccept = autoAccept)
    private val discovery = DiscoveryManager(context, scope, identity.local())

    val peers get() = discovery.peers
    val localAddresses get() = discovery.localAddresses
    val discoveryStatus get() = discovery.status
    val transfers get() = engine.transfers
    val events get() = engine.events
    val listeningPort get() = engine.listeningPort

    /** Starts the receiver and begins advertising/discovering. Returns the listening port. */
    @Synchronized
    fun start(): Int {
        check(!closed.get()) { "Share64Node is closed" }
        val port = try {
            engine.startReceiver(saveDir, TransferEngine.DEFAULT_PORT)
        } catch (_: IOException) {
            engine.startReceiver(saveDir, 0) // default port busy: any free port, advertised via DNS-SD
        }
        discovery.start(port)
        return port
    }

    @Synchronized
    fun stop() {
        discovery.stop()
        engine.stopReceiver()
    }

    /** Peer ids are stable, so an interrupted transfer resumes even after the peer's IP changed. */
    fun send(peer: PeerDevice, file: File, displayName: String = file.name): Long =
        engine.sendFile(peer.host, peer.port, file, peerKey = peer.id, displayName = displayName)

    /** For content:// sources; the engine takes ownership of [descriptor]. */
    fun send(peer: PeerDevice, descriptor: ParcelFileDescriptor, displayName: String): Long =
        engine.sendDescriptor(peer.host, peer.port, descriptor, displayName, peerKey = peer.id)

    /** Fallback when mDNS is blocked (AP isolation, some routers): verifies a typed address by handshake. */
    suspend fun connectManually(input: String): Result<PeerDevice> = withContext(Dispatchers.IO) {
        val addr = AddressParser.parse(input)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid address"))
        val port = addr.port ?: TransferEngine.DEFAULT_PORT
        val reply = HandshakeProbe.query(addr.host, port, ownName = identity.displayName)
            ?: return@withContext Result.failure(IOException("No SHARE64 device at ${addr.host}:$port"))
        if (reply.protocolVersion != PeerTxt.PROTOCOL_VERSION) {
            return@withContext Result.failure(IOException("Incompatible protocol version ${reply.protocolVersion}"))
        }
        val peer = PeerDevice(
            id = "manual:${addr.host}:$port",
            serviceName = "",
            displayName = reply.deviceName.ifBlank { addr.host },
            type = DeviceType.OTHER,
            host = addr.host,
            port = port,
            protocolVersion = reply.protocolVersion,
            reachable = true,
            lastSeenMs = System.currentTimeMillis(),
            manual = true,
        )
        discovery.addManualPeer(peer)
        Result.success(peer)
    }

    fun removePeer(id: String) = discovery.removePeer(id)

    fun accept(transferId: Long) = engine.accept(transferId)
    fun decline(transferId: Long) = engine.decline(transferId)
    fun cancel(transferId: Long) = engine.cancel(transferId)
    fun dismiss(transferId: Long) = engine.dismiss(transferId)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            discovery.stop()
            engine.close() // stops the receiver, cancels and joins all native threads
            scope.cancel()
        }
    }
}
