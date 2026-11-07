package com.share64.app.core

import com.share64.core.Direction
import com.share64.core.ErrorCode
import com.share64.core.TransferSnapshot
import com.share64.core.TransferState
import java.util.Locale

/** What a transfer row shows. Pure data, shared by the phone and TV UIs. */
data class TransferItem(
    val id: Long,
    val title: String,
    val isSend: Boolean,
    val state: TransferState,
    val progress: Float,
    val indeterminate: Boolean,
    val status: String,
    val detail: String,
    val canCancel: Boolean,
    val canDismiss: Boolean,
    val canOpen: Boolean,
)

object TransferPresenter {

    /** Running work first (newest first), then finished entries (newest first). */
    fun order(all: Collection<TransferSnapshot>): List<TransferSnapshot> {
        val newestFirst = all.reversed() // the engine keeps first-seen order
        val (running, finished) = newestFirst.partition { !it.state.isTerminal }
        return running + finished
    }

    fun present(s: TransferSnapshot, openable: Boolean, locale: Locale = Locale.getDefault()): TransferItem {
        val send = s.direction == Direction.SEND
        val verb = if (send) "Sending" else "Receiving"
        val size = Formatting.bytes(s.totalBytes, locale)
        val progressText = "${Formatting.bytes(s.doneBytes, locale)} of $size"

        val status: String
        var detail = ""
        when (s.state) {
            TransferState.OFFERED -> {
                status = "Waiting for your answer"
                detail = size
            }
            TransferState.ACTIVE -> {
                if (s.doneBytes == 0L && s.bytesPerSecond == 0L) {
                    status = if (send) "Connecting…" else "Starting…"
                } else {
                    val eta = Formatting.eta(s.totalBytes - s.doneBytes, s.bytesPerSecond)
                    status = buildString {
                        append(verb)
                        if (s.bytesPerSecond > 0) append(" · ").append(Formatting.speed(s.bytesPerSecond, locale))
                        if (eta != null) append(" · ").append(eta).append(" left")
                    }
                }
                detail = progressText
            }
            TransferState.RETRYING -> {
                status = "Connection lost — reconnecting"
                detail = if (s.verifiedBytes > 0) "Resuming from ${Formatting.bytes(s.verifiedBytes, locale)}" else "Attempt ${maxOf(s.attempt, 1)}"
            }
            TransferState.INTERRUPTED -> {
                status = "Connection lost — waiting for the sender"
                detail = "Kept ${Formatting.bytes(s.verifiedBytes, locale)} so far"
            }
            TransferState.COMPLETED -> {
                status = if (send) "Sent and verified" else "Received and verified"
                detail = size
            }
            TransferState.FAILED -> {
                status = errorText(s.error, s.message)
                detail = if (s.verifiedBytes > 0) "Transferred ${Formatting.bytes(s.verifiedBytes, locale)} before stopping" else size
            }
            TransferState.CANCELLED -> {
                status = "Cancelled"
                detail = s.message.takeIf { it.isNotBlank() && !it.equals("cancelled", ignoreCase = true) }
                    ?.replaceFirstChar { it.uppercase() }.orEmpty()
            }
        }

        val determinate = s.state == TransferState.ACTIVE || s.state == TransferState.RETRYING ||
            s.state == TransferState.INTERRUPTED
        return TransferItem(
            id = s.id,
            title = s.name.ifBlank { "File" },
            isSend = send,
            state = s.state,
            progress = if (s.state == TransferState.COMPLETED) 1f else s.fraction,
            indeterminate = (s.state == TransferState.ACTIVE && s.doneBytes == 0L && s.bytesPerSecond == 0L) ||
                s.state == TransferState.RETRYING,
            status = status,
            detail = detail,
            canCancel = !s.state.isTerminal && s.state != TransferState.OFFERED,
            canDismiss = s.state.isTerminal,
            canOpen = !send && s.state == TransferState.COMPLETED && openable,
        )
    }

    fun errorText(error: ErrorCode, message: String): String = when (error) {
        ErrorCode.REJECTED -> "The other device declined the file"
        ErrorCode.DISK_FULL -> "Not enough free storage"
        ErrorCode.TIMEOUT -> "The other device stopped responding"
        ErrorCode.HASH_MISMATCH -> "Data kept arriving corrupted"
        ErrorCode.BUSY -> "The other device is busy"
        ErrorCode.BAD_NAME -> "Invalid file name"
        ErrorCode.VERSION -> "Incompatible SHARE64 versions"
        ErrorCode.NOT_FOUND -> "This file can't be read"
        ErrorCode.CANCELLED -> "Cancelled"
        ErrorCode.PROTOCOL, ErrorCode.INTERNAL -> message.ifBlank { "Unexpected error" }
        ErrorCode.IO, ErrorCode.OK -> message.ifBlank { "Network or file error" }
    }
}
