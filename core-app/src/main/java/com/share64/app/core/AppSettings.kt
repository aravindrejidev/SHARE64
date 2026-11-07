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

    private companion object {
        const val KEY_BACKGROUND = "receive_in_background"
        const val KEY_AUTO_ACCEPT = "auto_accept"
    }
}
