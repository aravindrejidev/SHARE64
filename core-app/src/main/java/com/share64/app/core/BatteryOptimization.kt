package com.share64.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation can pause a background app that has been idle with the screen off
 * (Doze), even one with a foreground service. Exempting SHARE64 keeps large transfers and the
 * receiver reliable when the phone is locked.
 */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    /** The system's one-tap "Allow SHARE64 to always run in the background?" dialog. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Fallback when the dialog is unavailable: the system list of battery-optimised apps. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
