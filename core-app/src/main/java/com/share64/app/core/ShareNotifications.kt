package com.share64.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

internal class ShareNotifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Sharing status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this device can receive files"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_OFFERS, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks before accepting a file from another device"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
        else @Suppress("DEPRECATION") Notification.Builder(context)

    private fun openApp(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(action: String, transferId: Long, requestCode: Int): PendingIntent {
        val i = Intent(context, ShareService::class.java).setAction(action).putExtra(ShareService.EXTRA_ID, transferId)
        return PendingIntent.getService(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_share), title, pi).build()

    /** progressPercent null = no running transfer. */
    fun foreground(deviceName: String, text: String, progressPercent: Int?): Notification {
        val b = builder(CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("SHARE64 · $deviceName")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action("Stop", serviceAction(ShareService.ACTION_STOP, 0L, 1)))
        if (progressPercent != null) b.setProgress(100, progressPercent, false)
        return b.build()
    }

    fun offer(transferId: Long, fileName: String, sender: String, sizeText: String): Notification =
        builder(CH_OFFERS)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Incoming file from $sender")
            .setContentText("$fileName · $sizeText")
            .setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .addAction(action("Accept", serviceAction(ShareService.ACTION_ACCEPT, transferId, notificationId(transferId, 1))))
            .addAction(action("Decline", serviceAction(ShareService.ACTION_DECLINE, transferId, notificationId(transferId, 2))))
            .build()

    fun received(fileName: String, uri: Uri?, mime: String, savedPath: String?): Notification {
        val b = builder(CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Received $fileName")
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentText("Saved in Downloads/SHARE64 · tap to open")
            b.setContentIntent(PendingIntent.getActivity(context, 0, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            b.setContentText(savedPath?.let { "Saved in app storage" } ?: "Saved")
            b.setContentIntent(openApp())
        }
        return b.build()
    }

    fun error(title: String, text: String): Notification =
        builder(CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    fun notify(id: Int, n: Notification) {
        try { nm.notify(id, n) } catch (_: SecurityException) { /* notification permission revoked */ }
    }

    fun cancel(id: Int) = nm.cancel(id)

    companion object {
        const val CH_SERVICE = "share_service"
        const val CH_OFFERS = "share_offers"
        const val CH_DONE = "share_done"
        const val ID_FOREGROUND = 1
        fun offerId(transferId: Long) = notificationId(transferId, 1000)
        fun doneId(transferId: Long) = notificationId(transferId, 2000)
        private fun notificationId(transferId: Long, base: Int) = base + (transferId.hashCode() and 0x7FFF)
    }
}
