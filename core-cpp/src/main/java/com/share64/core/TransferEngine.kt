package com.share64.core

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Kotlin face of the native SHARE64 engine (one listener + any number of outgoing transfers).
 *
 * - [transfers] is the authoritative UI state; terminal states can never be lost.
 * - [events] is a best-effort stream (drops the oldest on overflow) for toasts / notifications.
 */
class TransferEngine(
    deviceName: String,
    autoAccept: Boolean = false,
    chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
    slots: Int = 4,
    workers: Int = 2,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    // Every call into native code holds the read lock; close() takes the write lock. So a call can
    // never reach native memory that close() already freed (the UI may race the service here).
    private val lifecycle = ReentrantReadWriteLock()

    /** Runs [block] only while the engine is open. Never blocks: a closing engine counts as closed. */
    private inline fun <T> ifOpen(fallback: T, block: () -> T): T {
        val read = lifecycle.readLock()
        if (!read.tryLock()) return fallback
        try {
            return if (closed.get()) fallback else block()
        } finally {
            read.unlock()
        }
    }

    /** Like [ifOpen] but for start/stop calls from background threads: throws once closed. */
    private inline fun <T> openOrThrow(block: () -> T): T {
        val read = lifecycle.readLock()
        read.lock()
        try {
            checkOpen()
            return block()
        } finally {
            read.unlock()
        }
    }

    private val _events = MutableSharedFlow<TransferEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val _peerSeen = MutableSharedFlow<SeenPeer>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Devices that contact our receiver: used to add (and reverse-probe) devices we have not met yet. */
    val peerSeen: SharedFlow<SeenPeer> = _peerSeen.asSharedFlow()

    private val _transfers = MutableStateFlow<Map<Long, TransferSnapshot>>(emptyMap())
    val transfers: StateFlow<Map<Long, TransferSnapshot>> = _transfers.asStateFlow()

    private val _listeningPort = MutableStateFlow(0)
    /** Port the receiver is listening on, or 0 when stopped. */
    val listeningPort: StateFlow<Int> = _listeningPort.asStateFlow()

    private val handle: Long = nativeCreate(deviceName, chunkSizeBytes, slots, workers, autoAccept)

    init {
        check(handle != 0L) { "SHARE64 native engine failed to initialise" }
    }

    /** Starts listening. Returns the bound port (pass 0 for an ephemeral one). */
    fun startReceiver(saveDir: File, port: Int = DEFAULT_PORT): Int = openOrThrow {
        saveDir.mkdirs()
        val r = nativeStartReceiver(handle, saveDir.absolutePath, port)
        if (r <= 0) throw IOException("Receiver failed to start: ${ErrorCode.from(-r)}")
        r
    }

    fun stopReceiver() {
        openOrThrow { nativeStopReceiver(handle) }
        _listeningPort.value = 0
    }

    /**
     * Sends [file] to host:port. [peerKey] must be a stable id of the target device (e.g. its NSD
     * service name) so an interrupted transfer resumes even if the peer's IP changes.
     * Returns the transfer id (deterministic per file+peer) or 0 if it could not even start.
     */
    fun sendFile(
        host: String,
        port: Int,
        file: File,
        peerKey: String = host,
        displayName: String = file.name,
    ): Long = ifOpen(0L) { nativeSendFile(handle, host, port, file.absolutePath, displayName, peerKey) }

    /** For content:// sources. The engine takes ownership of the descriptor and closes it. */
    fun sendDescriptor(
        host: String,
        port: Int,
        descriptor: ParcelFileDescriptor,
        displayName: String,
        peerKey: String = host,
    ): Long {
        val id = ifOpen(0L) { nativeSendFd(handle, host, port, descriptor.detachFd(), displayName, peerKey) }
        if (id == 0L && closed.get()) runCatching { descriptor.close() } // engine gone: do not leak the fd
        return id
    }

    // The UI can race a shutting-down service: once the engine is closed these are harmless no-ops.
    fun accept(transferId: Long) { ifOpen(Unit) { nativeRespond(handle, transferId, true) } }
    fun decline(transferId: Long) { ifOpen(Unit) { nativeRespond(handle, transferId, false) } }
    fun cancel(transferId: Long) { ifOpen(Unit) { nativeCancel(handle, transferId) } }

    /**
     * Tell the engine what Android says about connectivity (see Engine::network_changed).
     * [up] false: the network is gone, senders wait for it without using up their retries.
     * [dropConnections] true: live connections are dead (their address vanished): close them now
     * instead of waiting for the keep-alive; transfers then resume from the last verified chunk.
     */
    fun networkChanged(up: Boolean, dropConnections: Boolean) {
        ifOpen(Unit) { nativeNetworkChanged(handle, up, dropConnections) }
    }

    /** A peer showed up under a new address: running sends to [peerKey] use it from their next attempt. */
    fun updatePeerAddress(peerKey: String, host: String, port: Int): Int =
        ifOpen(0) { nativeUpdatePeer(handle, peerKey, host, port) }

    /** Removes a finished transfer from [transfers]. */
    fun dismiss(transferId: Long) {
        _transfers.update { m ->
            val t = m[transferId]
            if (t != null && t.state.isTerminal) m - transferId else m
        }
    }

    override fun close() {
        val write = lifecycle.writeLock()
        write.lock()
        try {
            if (closed.compareAndSet(false, true)) {
                nativeDestroy(handle) // cancels and joins every native thread
            }
        } finally {
            write.unlock()
        }
    }

    private fun checkOpen() = check(!closed.get()) { "TransferEngine is closed" }

    // ---- called from native worker threads via JNI (see jni_bridge.cpp) ----
    @Suppress("unused")
    private fun onNativeEvent(
        kind: Int, id: Long, dir: Int, err: Int, attempt: Int,
        total: Long, done: Long, verified: Long, bps: Long,
        name: String?, message: String?,
    ) {
        val ev = TransferEvent(
            kind = EventKind.entries.getOrElse(kind) { EventKind.PROGRESS },
            transferId = id,
            direction = if (dir == 0) Direction.SEND else Direction.RECEIVE,
            error = ErrorCode.from(err),
            attempt = attempt,
            totalBytes = total,
            doneBytes = done,
            verifiedBytes = verified,
            bytesPerSecond = bps,
            name = name.orEmpty(),
            message = message.orEmpty(),
        )
        reduce(ev)
        _events.tryEmit(ev)
    }

    private fun reduce(ev: TransferEvent) {
        if (ev.kind == EventKind.PEER_SEEN) {
            if (ev.name.isNotEmpty()) _peerSeen.tryEmit(SeenPeer(ev.name, ev.message))
            return
        }
        if (ev.kind == EventKind.LISTENING) {
            _listeningPort.value = ev.doneBytes.toInt()
            return
        }
        if (ev.transferId == 0L) return // immediate failures have no transfer; they only go to [events]
        _transfers.update { map ->
            val old = map[ev.transferId]
            if (old != null && old.state.isTerminal && ev.kind == EventKind.PROGRESS) return@update map
            val state = when (ev.kind) {
                EventKind.INCOMING_OFFER -> TransferState.OFFERED
                EventKind.STARTED, EventKind.PROGRESS -> TransferState.ACTIVE
                EventKind.RETRYING -> TransferState.RETRYING
                EventKind.INTERRUPTED -> TransferState.INTERRUPTED
                EventKind.COMPLETED -> TransferState.COMPLETED
                EventKind.FAILED -> TransferState.FAILED
                EventKind.CANCELLED -> TransferState.CANCELLED
                EventKind.LISTENING, EventKind.PEER_SEEN -> old?.state ?: TransferState.ACTIVE // both return before this point
            }
            val receivedPath = if (ev.kind == EventKind.COMPLETED && ev.direction == Direction.RECEIVE) ev.name else null
            val displayName = when {
                receivedPath != null -> File(receivedPath).name
                ev.name.isNotEmpty() -> ev.name
                else -> old?.name.orEmpty()
            }
            val snap = TransferSnapshot(
                id = ev.transferId,
                direction = ev.direction,
                name = displayName,
                totalBytes = if (ev.totalBytes > 0 || ev.kind == EventKind.COMPLETED) ev.totalBytes else old?.totalBytes ?: 0L,
                doneBytes = if (ev.kind == EventKind.CANCELLED || ev.kind == EventKind.FAILED) old?.doneBytes ?: ev.doneBytes else ev.doneBytes,
                verifiedBytes = maxOf(ev.verifiedBytes, if (ev.kind == EventKind.RETRYING || ev.kind == EventKind.INTERRUPTED) 0L else old?.verifiedBytes ?: 0L),
                bytesPerSecond = if (state == TransferState.ACTIVE) ev.bytesPerSecond else 0L,
                state = state,
                error = ev.error,
                message = if (ev.kind == EventKind.INCOMING_OFFER) "" else ev.message,
                attempt = if (ev.attempt > 0) ev.attempt else old?.attempt ?: 0,
                savedPath = receivedPath ?: old?.savedPath,
                peerName = if (ev.kind == EventKind.INCOMING_OFFER) ev.message else old?.peerName,
            )
            map + (ev.transferId to snap)
        }
    }

    private external fun nativeCreate(device: String, chunk: Int, slots: Int, workers: Int, autoAccept: Boolean): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStartReceiver(handle: Long, dir: String, port: Int): Int
    private external fun nativeStopReceiver(handle: Long)
    private external fun nativeSendFile(handle: Long, host: String, port: Int, path: String, name: String, peer: String): Long
    private external fun nativeSendFd(handle: Long, host: String, port: Int, fd: Int, name: String, peer: String): Long
    private external fun nativeRespond(handle: Long, id: Long, accept: Boolean)
    private external fun nativeCancel(handle: Long, id: Long)
    private external fun nativeNetworkChanged(handle: Long, up: Boolean, dropConnections: Boolean)
    private external fun nativeUpdatePeer(handle: Long, peer: String, host: String, port: Int): Int

    companion object {
        const val DEFAULT_PORT = 52364
        const val DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024

        init {
            System.loadLibrary("share64_core")
        }
    }
}
