package com.share64.app.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Human-readable names for where received files end up. */
object DownloadLocations {
    const val DEFAULT_LABEL = "Downloads/SHARE64"

    /** "primary:Download/Share" -> "Internal storage/Download/Share". Pure, unit-tested. */
    fun labelForDocumentId(id: String): String {
        if (!id.contains(':')) return id.ifEmpty { "Selected folder" }
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':')
        if (volume.equals("raw", ignoreCase = true)) return path.ifEmpty { "Selected folder" }
        val root = if (volume.equals("primary", ignoreCase = true)) "Internal storage" else "SD card ($volume)"
        return if (path.isEmpty()) root else "$root/$path"
    }

    /** Label for the chosen folder (a SAF tree Uri), or the default Downloads label when null. */
    fun label(treeUri: String?): String {
        if (treeUri.isNullOrEmpty()) return DEFAULT_LABEL
        return try {
            labelForDocumentId(DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
        } catch (_: Exception) {
            "Selected folder"
        }
    }

    /** True if we still hold write permission for [tree] and its provider lets us create files. */
    fun isUsable(context: Context, tree: Uri): Boolean {
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) return false
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
