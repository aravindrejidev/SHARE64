package com.share64.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller results for [UpdateInstaller]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the person to confirm: open its installer screen.
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (_: Exception) {
                        UpdateManager.onInstallFailed("Couldn't open the system installer", aborted = false)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstallSucceeded()
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.onInstallFailed("Installation cancelled", aborted = true)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateManager.onInstallFailed(
                    if (message.isNullOrBlank()) "Installation failed (code $status)" else "Installation failed: $message",
                    aborted = false,
                )
            }
        }
    }

    companion object {
        const val ACTION = "com.share64.action.UPDATE_INSTALL_RESULT"
    }
}
