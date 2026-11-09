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

    private companion object {
        const val KEY_BACKGROUND = "receive_in_background"
        const val KEY_AUTO_ACCEPT = "auto_accept"
        const val KEY_PORT = "port"
        const val KEY_TREE = "download_tree_uri"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_AMOLED = "amoled"
    }
}
