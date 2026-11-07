package com.share64.app.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.net.Uri
import java.io.File
import java.io.IOException

/** Moves a received file from app storage into the public Downloads/SHARE64 folder (Android 10+). */
object FileExporter {
    const val RELATIVE_DIR = "Download/SHARE64"

    /** Returns the content Uri, or null if exporting is unsupported (Android 9-) or failed; the file then stays put. */
    fun export(context: Context, source: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !source.isFile) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { o -> source.inputStream().use { it.copyTo(o, 1 shl 20) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        source.delete()
        return uri
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
