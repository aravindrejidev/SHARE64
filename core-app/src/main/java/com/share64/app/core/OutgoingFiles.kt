package com.share64.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.share64.network.PeerDevice
import com.share64.network.Share64Node
import java.io.File
import java.util.UUID

data class OutgoingFile(val uri: Uri, val name: String, val size: Long)

/** Turns picker / share-sheet Uris into transfers. */
object OutgoingFiles {

    fun describe(context: Context, uri: Uri): OutgoingFile {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (_: Exception) {
            // provider without OpenableColumns: fall back to the path
        }
        return OutgoingFile(uri, name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file", size)
    }

    /**
     * Sends [file] to [peer]. Seekable descriptors go straight to the native engine; sources that
     * are not regular files (some cloud providers hand out pipes) are copied to the cache first.
     * Returns the transfer id or 0 if the file could not be opened.
     */
    fun send(context: Context, node: Share64Node, peer: PeerDevice, file: OutgoingFile): Long {
        val resolver = context.contentResolver
        val pfd = try {
            resolver.openFileDescriptor(file.uri, "r")
        } catch (_: Exception) {
            null
        } ?: return 0L
        val regular = try {
            OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
        } catch (_: Exception) {
            false
        }
        if (regular) return node.send(peer, pfd, file.name)
        pfd.close()

        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val tmp = File(dir, UUID.randomUUID().toString())
        return try {
            resolver.openInputStream(file.uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: return 0L
            node.send(peer, tmp, displayName = file.name)
        } catch (_: Exception) {
            tmp.delete()
            0L
        }
    }

    /** Deletes cached copies made by [send]; call when no transfer is running. */
    fun clearCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    private const val CACHE_DIR = "outgoing"
}
