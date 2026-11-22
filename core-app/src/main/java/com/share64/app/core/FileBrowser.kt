package com.share64.app.core

import java.io.File
import java.io.IOException

/**
 * A place the in-app file picker can start from: internal storage, a USB drive or SD card, or a
 * shortcut such as Downloads. [shortcut] entries are only doorways: going "up" from inside one
 * continues through the storage volume that contains it.
 */
data class BrowserRoot(
    val label: String,
    val dir: File,
    val removable: Boolean = false,
    val shortcut: Boolean = false,
)

/** One row of a folder listing. */
data class BrowserEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    /** Bytes for files; 0 for folders. */
    val size: Long,
    /** False when the file exists but this app is not allowed to read it. */
    val readable: Boolean = true,
) {
    val path: String get() = file.absolutePath
}

sealed interface FolderListing {
    data class Ok(val entries: List<BrowserEntry>) : FolderListing

    /** The folder cannot be listed (no permission, unmounted drive, removed meanwhile). */
    data object Unreadable : FolderListing
}

/**
 * Folder listing, sorting and "where am I" logic for the TV file picker. Pure JVM (java.io only), so
 * it is unit-tested without a device.
 */
object FileBrowser {
    private val ORDER: Comparator<BrowserEntry> =
        compareBy<BrowserEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }.thenBy { it.name }

    /** Folders first, then files, each A to Z ignoring case. Dot-files are hidden unless [showHidden]. */
    fun list(dir: File, showHidden: Boolean = false): FolderListing {
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return FolderListing.Unreadable
        val entries = ArrayList<BrowserEntry>(children.size)
        for (f in children) {
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            if (!isDir && !f.isFile) continue // sockets, broken links
            entries += BrowserEntry(
                file = f,
                name = f.name,
                isDirectory = isDir,
                size = if (isDir) 0L else f.length(),
                readable = f.canRead(),
            )
        }
        entries.sortWith(ORDER)
        return FolderListing.Ok(entries)
    }

    /**
     * The folder one level up from [dir], or null when [dir] is a storage root (the caller then shows
     * the list of storages) or is not inside any known storage. Never climbs out of a storage.
     */
    fun parent(dir: File, roots: List<BrowserRoot>): File? {
        val volumes = roots.filter { !it.shortcut }
        val here = canonical(dir)
        if (volumes.any { canonical(it.dir) == here }) return null
        val up = here.parentFile ?: return null
        return if (volumes.any { isInside(up, canonical(it.dir)) }) up else null
    }

    /** "Internal storage › Movies › Trips": where [dir] is, in words. */
    fun label(dir: File, roots: List<BrowserRoot>): String {
        val here = canonical(dir)
        val owner = roots.filter { !it.shortcut }
            .filter { isInside(here, canonical(it.dir)) }
            .maxByOrNull { canonical(it.dir).path.length }
            ?: return dir.name.ifEmpty { dir.path }
        val base = canonical(owner.dir)
        val relative = here.path.removePrefix(base.path).trim(File.separatorChar)
        if (relative.isEmpty()) return owner.label
        return owner.label + " \u203A " + relative.split(File.separatorChar).filter { it.isNotEmpty() }.joinToString(" \u203A ")
    }

    /** True if [child] is [root] itself or lies somewhere below it. Both should already be canonical. */
    fun isInside(child: File, root: File): Boolean {
        val c = child.path
        val r = root.path
        return c == r || c.startsWith(if (r.endsWith(File.separator)) r else r + File.separator)
    }

    private fun canonical(f: File): File = try {
        f.canonicalFile
    } catch (_: IOException) {
        f.absoluteFile
    }
}
