package com.share64.core

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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

    private val _events = MutableSharedFlow<TransferEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

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
    fun startReceiver(saveDir: File, port: Int = DEFAULT_PORT): Int {
        checkOpen()
        saveDir.mkdirs()
        val r = nativeStartReceiver(handle, saveDir.absolutePath, port)
        if (r <= 0) throw IOException("Receiver failed to start: ${ErrorCode.from(-r)}")
        return r
    }

    fun stopReceiver() {
        checkOpen()
        nativeStopReceiver(handle)
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
    ): Long {
        checkOpen()
        return nativeSendFile(handle, host, port, file.absolutePath, displayName, peerKey)
    }

    /** For content:// sources. The engine takes ownership of the descriptor and closes it. */
    fun sendDescriptor(
        host: String,
        port: Int,
        descriptor: ParcelFileDescriptor,
        displayName: String,
        peerKey: String = host,
    ): Long {
        checkOpen()
        return nativeSendFd(handle, host, port, descriptor.detachFd(), displayName, peerKey)
    }

    fun accept(transferId: Long) { checkOpen(); nativeRespond(handle, transferId, true) }
    fun decline(transferId: Long) { checkOpen(); nativeRespond(handle, transferId, false) }
    fun cancel(transferId: Long) { checkOpen(); nativeCancel(handle, transferId) }

    /** Removes a finished transfer from [transfers]. */
    fun dismiss(transferId: Long) {
        _transfers.update { m ->
            val t = m[transferId]
            if (t != null && t.state.isTerminal) m - transferId else m
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeDestroy(handle) // cancels and joins every native thread
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
                EventKind.LISTENING -> old?.state ?: TransferState.ACTIVE
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

    companion object {
        const val DEFAULT_PORT = 52364
        const val DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024

        init {
            System.loadLibrary("share64_core")
        }
    }
}
