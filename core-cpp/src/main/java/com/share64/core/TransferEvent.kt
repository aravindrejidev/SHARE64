package com.share64.core

enum class Direction { SEND, RECEIVE }

enum class EventKind {
    STARTED, PROGRESS, RETRYING, COMPLETED, FAILED, CANCELLED, INCOMING_OFFER, LISTENING, INTERRUPTED
}

enum class ErrorCode(val code: Int) {
    OK(0), IO(1), PROTOCOL(2), HASH_MISMATCH(3), REJECTED(4), CANCELLED(5), DISK_FULL(6),
    TIMEOUT(7), BUSY(8), BAD_NAME(9), VERSION(10), NOT_FOUND(11), INTERNAL(12);

    companion object {
        fun from(code: Int): ErrorCode = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

/** Raw event from the native engine. Best-effort stream: use [TransferEngine.transfers] for UI state. */
data class TransferEvent(
    val kind: EventKind,
    val transferId: Long,
    val direction: Direction,
    val error: ErrorCode,
    val attempt: Int,
    val totalBytes: Long,
    /** Bytes on the wire (smooth progress). */
    val doneBytes: Long,
    /** Bytes confirmed by per-chunk SHA-256 + receiver ACK. */
    val verifiedBytes: Long,
    val bytesPerSecond: Long,
    /** File name; for a receiver COMPLETED event this is the saved absolute path. */
    val name: String,
    /** INCOMING_OFFER: sender device. COMPLETED: root SHA-256 hex. FAILED/INTERRUPTED: detail. */
    val message: String,
)

enum class TransferState {
    OFFERED, ACTIVE, RETRYING, INTERRUPTED, COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class TransferSnapshot(
    val id: Long,
    val direction: Direction,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val verifiedBytes: Long,
    val bytesPerSecond: Long,
    val state: TransferState,
    val error: ErrorCode,
    val message: String,
    val attempt: Int,
    /** Receiver only, once COMPLETED: absolute path of the finished file. */
    val savedPath: String?,
    /** Sender device name for an incoming offer. */
    val peerName: String?,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}
