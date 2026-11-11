package com.share64.app.core

import android.content.Context

/** User preferences shared by the phone and TV apps. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("share64_settings", Context.MODE_PRIVATE)

    /** Keep the receiver (and its foreground notification) alive while the UI is closed. */
    var receiveInBackground: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) { prefs.edit().putBoolean(KEY_BACKGROUND, value).apply() }

    /** Accept every incoming file without asking. Off by default. */
    var autoAccept: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ACCEPT, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_ACCEPT, value).apply() }

    /** Port the receiver tries first (it falls back to a free one if busy). */
    var port: Int
        get() = prefs.getInt(KEY_PORT, PortRules.DEFAULT).takeIf { it in PortRules.MIN..PortRules.MAX } ?: PortRules.DEFAULT
        set(value) { prefs.edit().putInt(KEY_PORT, value.coerceIn(PortRules.MIN, PortRules.MAX)).apply() }

    /** SAF tree Uri of a custom download folder, or null for Downloads/SHARE64. */
    var downloadTreeUri: String?
        get() = prefs.getString(KEY_TREE, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().apply { if (value.isNullOrEmpty()) remove(KEY_TREE) else putString(KEY_TREE, value) }.apply()
        }

    var themeMode: ThemeMode
        get() = ThemeMode.fromName(prefs.getString(KEY_THEME, null))
        set(value) { prefs.edit().putString(KEY_THEME, value.name).apply() }

    /** Use the wallpaper-derived Material You colors on Android 12+. */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC, true)
        set(value) { prefs.edit().putBoolean(KEY_DYNAMIC, value).apply() }

    /** Pure-black backgrounds in dark theme (saves power on OLED screens). */
    var amoled: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AMOLED, value).apply() }

    /** Look for a new release whenever the app opens (at most every few hours). */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply() }

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHECK, value).apply() }

    /** A version the person chose "Skip this version" for; automatic checks stay quiet about it. */
    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_SKIPPED) else putString(KEY_SKIPPED, value) }.apply() }

    private companion object {
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_CHECK = "last_update_check"
        const val KEY_SKIPPED = "skipped_version"
        const val KEY_BACKGROUND = "receive_in_background"
        const val KEY_AUTO_ACCEPT = "auto_accept"
        const val KEY_PORT = "port"
        const val KEY_TREE = "download_tree_uri"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_AMOLED = "amoled"
    }
}
