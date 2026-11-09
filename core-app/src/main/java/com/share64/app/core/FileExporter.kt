package com.share64.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** A finished file's public location. */
data class Exported(val uri: Uri, val location: String)

/**
 * Moves a received file out of app storage:
 *  1. into the folder the person chose in Settings (SAF tree), if any, else
 *  2. into Downloads/SHARE64 via MediaStore (Android 10+).
 * If both fail (or Android 9-), the file stays in app storage and null is returned.
 */
object FileExporter {
    const val RELATIVE_DIR = "Download/SHARE64"

    fun export(context: Context, source: File, displayName: String, treeUri: String?): Exported? {
        if (!source.isFile) return null
        if (!treeUri.isNullOrEmpty()) {
            val tree = Uri.parse(treeUri)
            if (DownloadLocations.isUsable(context, tree)) {
                exportToTree(context, source, displayName, tree)?.let { return it }
            }
        }
        return exportToDownloads(context, source, displayName)
    }

    private fun exportToTree(context: Context, source: File, displayName: String, tree: Uri): Exported? {
        val resolver = context.contentResolver
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, mimeFor(displayName), displayName) ?: return null
            try {
                val out = resolver.openOutputStream(doc) ?: throw IOException("no output stream")
                out.use { o -> source.inputStream().use { it.copyTo(o, 1 shl 20) } }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                return null
            }
            source.delete()
            Exported(doc, DownloadLocations.label(tree.toString()))
        } catch (_: Exception) {
            null
        }
    }

    private fun exportToDownloads(context: Context, source: File, displayName: String): Exported? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
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
        return Exported(uri, DownloadLocations.DEFAULT_LABEL)
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
