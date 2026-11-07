package com.share64.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/** Stable device id (generated once) and user-editable display name. */
class DeviceIdentity(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("share64_identity", Context.MODE_PRIVATE)

    val id: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("id", it).apply() }

    /** Takes effect for a new [Share64Node]; the running engine keeps the name it started with. */
    var displayName: String
        get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() }
            ?: PeerTxt.sanitizeName(Build.MODEL ?: "Android")
        set(value) {
            prefs.edit().putString("name", PeerTxt.sanitizeName(value)).apply()
        }

    val type: DeviceType =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) DeviceType.TV
        else DeviceType.PHONE

    fun local(): LocalDevice = LocalDevice(id, displayName, type)
}
