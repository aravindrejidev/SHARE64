package com.share64.app.core

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File
import java.io.IOException

/**
 * What the in-app file picker (used on TVs, which often have no system picker) needs from Android:
 * the permission to read shared storage, and the list of storages to start from.
 */
object StorageAccess {
    /** Android 11+ needs "All files access" (a Settings switch); older versions use a normal runtime permission. */
    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun granted(context: Context): Boolean =
        if (needsSettingsScreen) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Opens the "All files access" screen for this app (Android 11+). False if this TV has no such screen. */
    fun openSettings(context: Context): Boolean {
        if (!needsSettingsScreen) return false
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next screen
            } catch (_: SecurityException) {
                // some TV builds block the intent
            }
        }
        return false
    }

    /**
     * Storages to browse: internal storage first, then USB drives and SD cards, then a few shortcuts
     * (Downloads, Movies, ...). Only places that exist and can be listed are returned.
     */
    fun roots(context: Context): List<BrowserRoot> {
        val volumes = ArrayList<BrowserRoot>()
        val seen = HashSet<String>()

        fun add(label: String, dir: File?, removable: Boolean, shortcut: Boolean = false, into: MutableList<BrowserRoot> = volumes) {
            if (dir == null || !dir.isDirectory || !dir.canRead()) return
            val key = try {
                dir.canonicalPath
            } catch (_: IOException) {
                dir.absolutePath
            }
            if (seen.add(key)) into += BrowserRoot(label, dir, removable, shortcut)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            for (v in sm?.storageVolumes.orEmpty()) {
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val label = if (v.isPrimary) "Internal storage" else (v.getDescription(context) ?: "USB drive")
                add(label, v.directory, removable = !v.isPrimary && v.isRemovable)
            }
        }
        @Suppress("DEPRECATION")
        add("Internal storage", Environment.getExternalStorageDirectory(), removable = false)
        // Older Android versions: every other directory under /storage is a USB drive or SD card.
        File("/storage").listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.name != "self" && f.name != "emulated") add("USB / SD card (${f.name})", f, removable = true)
        }

        val shortcuts = ArrayList<BrowserRoot>()
        for ((label, type) in SHORTCUTS) {
            @Suppress("DEPRECATION")
            add(label, Environment.getExternalStoragePublicDirectory(type), removable = false, shortcut = true, into = shortcuts)
        }
        return volumes + shortcuts
    }

    private val SHORTCUTS = listOf(
        "Downloads" to Environment.DIRECTORY_DOWNLOADS,
        "Movies" to Environment.DIRECTORY_MOVIES,
        "Pictures" to Environment.DIRECTORY_PICTURES,
        "Music" to Environment.DIRECTORY_MUSIC,
        "Camera (DCIM)" to Environment.DIRECTORY_DCIM,
    )
}
