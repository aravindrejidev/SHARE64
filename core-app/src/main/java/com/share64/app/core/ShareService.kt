package com.share64.app.core

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.share64.core.Direction
import com.share64.core.TransferSnapshot
import com.share64.core.TransferState
import com.share64.network.DiscoveryStatus
import com.share64.network.Share64Node
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the [Share64Node]: keeps the receiver reachable, shows progress,
 * asks about incoming files, and exports finished files to Downloads/SHARE64.
 */
class ShareService : Service() {
    // A failing background task is logged, never allowed to take the whole app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "service task failed", t) },
    )
    /** Bumped by every start command; lets a running shutdown notice that it was overtaken. */
    private val generation = AtomicInteger()
    /** When each receive-side transfer was interrupted (to stop holding the CPU for ghosts). */
    private val interruptedAt = ConcurrentHashMap<Long, Long>()
    private lateinit var notifier: ShareNotifications
    private lateinit var settings: AppSettings
    private lateinit var locks: TransferLocks

    @Volatile private var node: Share64Node? = null
    @Volatile private var starting = false
    private val observers = mutableListOf<Job>()
    private val offerShown = ConcurrentHashMap.newKeySet<Long>()
    private val exported = ConcurrentHashMap.newKeySet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        notifier = ShareNotifications(this).also { it.ensureChannels() }
        settings = AppSettings(this)
        locks = TransferLocks(this)
        scope.launch { ShareRuntime.uiVisible.collect { onUiVisibilityChanged(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Must happen right away whenever we were started with startForegroundService().
        if (!goForeground(notifier.foreground(deviceNameOrDefault(), "Starting…", null))) {
            // Android refused (for example a restart from the background on Android 12+): leave cleanly.
            closeNode()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != ACTION_STOP) generation.incrementAndGet()
        when (action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_ACCEPT -> intent.transferId()?.let { node?.accept(it); notifier.cancel(ShareNotifications.offerId(it)) }
            ACTION_DECLINE -> intent.transferId()?.let { node?.decline(it); notifier.cancel(ShareNotifications.offerId(it)) }
            ACTION_RESTART -> {
                if (!settings.sharingEnabled) { shutdown(); return START_NOT_STICKY }
                restartNode()
                return stickiness()
            }
        }
        if (!settings.sharingEnabled) {
            // Sharing was switched off: nothing may keep running.
            shutdown()
            return START_NOT_STICKY
        }
        ensureNode()
        return stickiness()
    }

    /**
     * When the person wants to keep receiving in the background, the system may bring the service
     * back after it killed the process to free memory (an intent-less restart, handled above).
     */
    private fun stickiness(): Int =
        if (settings.sharingEnabled && settings.receiveInBackground) START_STICKY else START_NOT_STICKY

    private fun Intent.transferId(): Long? = getLongExtra(EXTRA_ID, 0L).takeIf { it != 0L }

    private fun deviceNameOrDefault(): String = node?.identity?.displayName ?: "SHARE64"

    /** False when the system does not allow this service to run in the foreground right now. */
    private fun goForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ShareNotifications.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ShareNotifications.ID_FOREGROUND, n)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not start in the foreground", e)
        false
    }

    // ---------------------------------------------------------------- node lifecycle

    private fun ensureNode() {
        if (node != null || starting) return
        starting = true
        scope.launch(Dispatchers.IO) {
            try {
                val dir = getExternalFilesDir("Received") ?: File(filesDir, "Received")
                val n = Share64Node(applicationContext, dir, autoAccept = settings.autoAccept, preferredPort = settings.port)
                try {
                    n.start()
                } catch (e: Exception) {
                    n.close()
                    throw e
                }
                node = n
                ShareRuntime.setNode(n)
                OutgoingFiles.clearCache(applicationContext)
                observe(n)
            } catch (e: Exception) {
                notifier.notify(
                    ShareNotifications.ID_FOREGROUND + 1,
                    notifier.error("SHARE64 could not start", e.message ?: e.javaClass.simpleName),
                )
                shutdown()
            } finally {
                starting = false
            }
        }
    }

    private fun observe(n: Share64Node) {
        observers += scope.launch {
            n.transfers.collect { onTransfers(n, it) }
        }
        observers += scope.launch {
            // Timed wake locks are renewed, and locks held for a transfer that never resumed are
            // released, even when the transfer list itself stays unchanged for a long time.
            while (true) {
                delay(LOCK_CHECK_MS)
                updateLocks()
                stopIfIdle(n.transfers.value.values)
            }
        }
        observers += scope.launch {
            // Notification refresh is throttled to ~1 Hz; the engine reports progress ~10x per second.
            combine(n.transfers, n.discoveryStatus) { t, s -> t.values to s }.conflate().collect { (t, s) ->
                refreshForeground(n, t, s)
                delay(1_000)
            }
        }
    }

    private fun closeNode() {
        observers.forEach { it.cancel() }
        observers.clear()
        val n = node
        node = null
        ShareRuntime.setNode(null)
        locks.set(false)
        runCatching { n?.close() }
        offerShown.clear()
        interruptedAt.clear()
    }

    private fun restartNode() {
        scope.launch(Dispatchers.IO) {
            closeNode()
            ensureNode()
        }
    }

    private fun shutdown() {
        val gen = generation.get()
        scope.launch(Dispatchers.IO) {
            closeNode()
            if (generation.get() != gen) {
                // Started again while we were closing (the switch was flipped back on): carry on.
                if (settings.sharingEnabled) ensureNode()
                return@launch
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- reacting to transfers

    private fun isBusy(all: Collection<TransferSnapshot>): Boolean {
        val now = System.currentTimeMillis()
        return all.any {
            when (it.state) {
                TransferState.ACTIVE, TransferState.RETRYING -> true
                // Waiting for the sender to come back: stay awake for a few minutes, not forever.
                TransferState.INTERRUPTED -> now - (interruptedAt[it.id] ?: now) < INTERRUPTED_GRACE_MS
                else -> false
            }
        }
    }

    private fun updateLocks() {
        locks.set(isBusy(node?.transfers?.value?.values ?: emptyList()))
    }

    private fun onTransfers(n: Share64Node, map: Map<Long, TransferSnapshot>) {
        val visible = ShareRuntime.uiVisible.value
        for (s in map.values) {
            if (s.state == TransferState.INTERRUPTED) interruptedAt.putIfAbsent(s.id, System.currentTimeMillis())
            else interruptedAt.remove(s.id)
            if (s.state == TransferState.OFFERED) {
                if (!visible && offerShown.add(s.id)) postOffer(s)
            } else if (offerShown.remove(s.id)) {
                notifier.cancel(ShareNotifications.offerId(s.id))
            }
            if (s.direction == Direction.RECEIVE && s.state == TransferState.COMPLETED &&
                s.savedPath != null && exported.add(s.id)
            ) {
                exportReceived(s)
            }
        }
        updateLocks()
        stopIfIdle(map.values)
    }

    private fun postOffer(s: TransferSnapshot) {
        notifier.notify(
            ShareNotifications.offerId(s.id),
            notifier.offer(s.id, s.name, s.peerName ?: "another device", Formatting.bytes(s.totalBytes)),
        )
    }

    private fun exportReceived(s: TransferSnapshot) {
        scope.launch(Dispatchers.IO) {
            val path = s.savedPath ?: return@launch
            val result = FileExporter.export(applicationContext, File(path), s.name, settings.downloadTreeUri)
            // If exporting is impossible (Android 9-, folder permission lost, storage full) the file
            // simply stays in app storage and we say so.
            val location = result?.location ?: "app storage (Android/data/$packageName/files/Received)"
            ShareRuntime.markReceived(s.id, result?.uri, location)
            notifier.notify(
                ShareNotifications.doneId(s.id),
                notifier.received(s.name, result?.uri, FileExporter.mimeFor(s.name), location),
            )
        }
    }

    private fun onUiVisibilityChanged(visible: Boolean) {
        val n = node ?: return
        val map = n.transfers.value
        if (visible) {
            map.values.filter { it.state == TransferState.OFFERED }.forEach {
                if (offerShown.remove(it.id)) notifier.cancel(ShareNotifications.offerId(it.id))
            }
        } else {
            map.values.filter { it.state == TransferState.OFFERED }.forEach { if (offerShown.add(it.id)) postOffer(it) }
            stopIfIdle(map.values)
        }
    }

    private fun stopIfIdle(all: Collection<TransferSnapshot>) {
        if (!ShareRuntime.uiVisible.value && !settings.receiveInBackground && !isBusy(all) &&
            all.none { it.state == TransferState.OFFERED }
        ) {
            shutdown()
        }
    }

    private fun refreshForeground(n: Share64Node, all: Collection<TransferSnapshot>, status: DiscoveryStatus) {
        val active = all.filter { it.state == TransferState.ACTIVE || it.state == TransferState.RETRYING }
        val reconnecting = active.count { it.state == TransferState.RETRYING }
        val text = when {
            active.isNotEmpty() ->
                "${active.size} transfer${if (active.size == 1) "" else "s"} " +
                    if (reconnecting == active.size) "reconnecting" else "running"
            status is DiscoveryStatus.NoNetwork -> "No Wi-Fi or hotspot connection"
            status is DiscoveryStatus.Error -> status.message
            status is DiscoveryStatus.Running -> "Ready to receive · ${status.address} (${status.network.label})"
            else -> "Starting…"
        }
        val total = active.sumOf { it.totalBytes }
        val percent = if (active.isNotEmpty() && total > 0) (active.sumOf { it.doneBytes } * 100 / total).toInt().coerceIn(0, 100) else null
        notifier.notify(ShareNotifications.ID_FOREGROUND, notifier.foreground(n.identity.displayName, text, percent))
    }

    override fun onDestroy() {
        running = false
        closeNode()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- keep Wi-Fi fast while transferring

    /**
     * CPU + Wi-Fi locks held only while a transfer needs them, so a locked screen cannot stall it.
     * Both are timed, so they can never leak; while busy the CPU lock is renewed long before it ends
     * (a ten-hour movie never loses it).
     */
    private class TransferLocks(context: Context) {
        private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "share64:transfer").apply { setReferenceCounted(false) }
        private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Low-latency only applies while the screen is on; the older high-performance mode is what
        // keeps the radio awake with the screen off on the devices that still honour it.
        private val wifiLocks: List<WifiManager.WifiLock> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "share64:transfer-ll"))
            }
            @Suppress("DEPRECATION")
            add(wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "share64:transfer-hp"))
        }.onEach { it.setReferenceCounted(false) }

        private var wakeAcquiredAt = 0L

        @Synchronized
        fun set(active: Boolean) {
            try {
                if (active) {
                    val now = SystemClock.elapsedRealtime()
                    if (!wake.isHeld || now - wakeAcquiredAt > WAKE_RENEW_MS) {
                        wake.acquire(WAKE_TIMEOUT_MS)
                        wakeAcquiredAt = now
                    }
                    wifiLocks.forEach { if (!it.isHeld) it.acquire() }
                } else {
                    if (wake.isHeld) wake.release()
                    wifiLocks.forEach { if (it.isHeld) it.release() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "wake/wifi lock failed", e)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.share64.action.STOP"
        const val ACTION_ACCEPT = "com.share64.action.ACCEPT"
        const val ACTION_DECLINE = "com.share64.action.DECLINE"
        const val ACTION_RESTART = "com.share64.action.RESTART"
        const val EXTRA_ID = "transfer_id"

        private const val TAG = "SHARE64"
        private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKE_RENEW_MS = 10 * 60 * 1000L
        private const val LOCK_CHECK_MS = 60 * 1000L
        private const val INTERRUPTED_GRACE_MS = 5 * 60 * 1000L

        /**
         * Call from a visible Activity (Android 12+ forbids starting foreground services from the
         * background). Does nothing while the person has switched sharing off.
         */
        fun start(context: Context) {
            if (AppSettings(context).sharingEnabled) launch(context, null)
        }

        fun restart(context: Context) {
            if (AppSettings(context).sharingEnabled) launch(context, ACTION_RESTART)
        }

        @Volatile private var running = false

        /** True from onCreate to onDestroy of the service in this process. */
        val isRunning: Boolean get() = running

        /**
         * Closes sockets, releases wake/Wi-Fi locks and removes the notification. A service that is
         * not running is not started just to be stopped: if one is still on its way up it notices
         * the switched-off setting and leaves again by itself.
         */
        fun stop(context: Context) {
            if (running) launch(context, ACTION_STOP)
        }

        private fun launch(context: Context, action: String?) {
            val i = Intent(context, ShareService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            } catch (_: Exception) {
                // app is not in a state that may start a foreground service; the UI shows "not running"
            }
        }
    }
}
