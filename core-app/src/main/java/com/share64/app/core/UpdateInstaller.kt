package com.share64.app.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/** Installs a downloaded APK through the system PackageInstaller (no FileProvider needed). */
object UpdateInstaller {

    /** Android 8+: the person must allow this app to "install unknown apps" once. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Streams [apk] into an install session and commits it. The result arrives at [UpdateInstallReceiver]. */
    fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("share64-update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                // The system adds the status extras, so the PendingIntent must be mutable on Android 12+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}
