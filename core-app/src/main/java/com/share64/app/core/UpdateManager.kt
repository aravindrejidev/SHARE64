package com.share64.app.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/**
 * In-app updater shared by the phone and TV apps.
 *
 * Flow: check GitHub's latest release -> offer it (with the notes from Relece_notice.md) ->
 * download the APK that matches this device -> verify size and SHA-256 -> hand it to the system
 * installer. Android additionally refuses any update that is not signed with our key.
 */
object UpdateManager {
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val UPDATES_DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _sheet = MutableStateFlow(false)
    /** The update sheet/dialog is open. */
    val sheetVisible: StateFlow<Boolean> = _sheet.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var settings: AppSettings? = null
    @Volatile private var flavor = AppFlavor.PHONE
    @Volatile private var client = UpdateClient(AppInfo.LATEST_RELEASE_API_URL, AppInfo.RAW_BASE_URL, AppInfo.RELEASE_NOTICE_FILES)
    @Volatile private var pending: Pair<UpdateInfo, File>? = null
    private var job: Job? = null

    @Synchronized
    fun init(context: Context, flavor: AppFlavor, client: UpdateClient? = null) {
        if (appContext != null) return
        appContext = context.applicationContext
        settings = AppSettings(context.applicationContext)
        this.flavor = flavor
        if (client != null) this.client = client
        cleanOldDownloads()
    }

    val lastCheckMs: Long get() = settings?.lastUpdateCheckMs ?: 0L

    // ---------------------------------------------------------------- checking

    /** Called when the app opens: respects the on/off setting and a minimum interval. */
    fun checkOnLaunch() {
        val s = settings ?: return
        if (!s.autoUpdateCheck || isBusy()) return
        if (System.currentTimeMillis() - s.lastUpdateCheckMs < CHECK_INTERVAL_MS) return
        runCheck(manual = false)
    }

    /** "Check for updates": always asks the server and reports the result. */
    fun checkNow() {
        if (!isBusy()) runCheck(manual = true)
    }

    private fun isBusy(): Boolean {
        val st = _state.value
        return job?.isActive == true || st is UpdateState.Downloading || st is UpdateState.Installing
    }

    private fun runCheck(manual: Boolean) {
        val ctx = appContext ?: return
        val s = settings ?: return
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                val release = client.fetchLatest()
                val now = System.currentTimeMillis()
                s.lastUpdateCheckMs = now
                val build = AppInfo.read(ctx)
                if (!Version.isNewer(release.tag, build.versionName)) {
                    _state.value = UpdateState.UpToDate(now)
                    return@launch
                }
                val pick = AssetPicker.pick(release.assets, flavor, Build.SUPPORTED_ABIS.toList(), Process.is64Bit())
                if (pick == null) {
                    _state.value = if (manual) UpdateState.Failed("Release ${release.tag} has no download for this device") else UpdateState.UpToDate(now)
                    return@launch
                }
                val version = Version.parse(release.tag)?.toString() ?: release.tag.removePrefix("v")
                if (!manual && s.skippedVersion == version) {
                    _state.value = UpdateState.UpToDate(now)
                    return@launch
                }
                val notes = client.fetchNotes(release.tag)
                _state.value = UpdateState.Available(
                    UpdateInfo(
                        tag = release.tag,
                        version = version,
                        title = release.title.ifBlank { "SHARE64 $version" },
                        notes = notes,
                        publishedAt = release.publishedAt,
                        assetName = pick.apk.name,
                        assetUrl = pick.apk.url,
                        assetSize = pick.apk.size,
                        checksumsUrl = AssetPicker.checksumsAsset(release.assets)?.url,
                        abiLabel = pick.abiLabel,
                        pageUrl = release.pageUrl,
                    ),
                )
                _sheet.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Automatic checks stay silent; a manual one explains what went wrong.
                _state.value = if (manual) UpdateState.Failed(friendly(e)) else if (_state.value == UpdateState.Checking) UpdateState.Idle else _state.value
            }
        }
    }

    // ---------------------------------------------------------------- download + install

    /** "Update now": downloads (unless a verified copy exists) and starts the installer. */
    fun startUpdate() {
        val st = _state.value
        val info = st.updateInfo ?: return
        when (st) {
            is UpdateState.Downloading, is UpdateState.Installing -> return
            is UpdateState.Ready -> { launchInstall(st.file, info); return }
            is UpdateState.NeedsPermission -> { launchInstall(st.file, info); return }
            else -> Unit
        }
        job?.cancel()
        job = scope.launch { downloadAndInstall(info) }
    }

    fun cancelDownload() {
        val info = _state.value.updateInfo ?: return
        job?.cancel()
        _state.value = UpdateState.Available(info)
    }

    private fun launchInstall(file: File, info: UpdateInfo) {
        scope.launch { install(file, info) }
    }

    private suspend fun downloadAndInstall(info: UpdateInfo) {
        val ctx = appContext ?: return
        val dest = File(File(ctx.cacheDir, UPDATES_DIR), info.assetName)
        _state.value = UpdateState.Downloading(info, 0, info.assetSize)
        try {
            val expected = info.checksumsUrl?.let { runCatching { client.fetchChecksums(it)[info.assetName] }.getOrNull() }
            val reusable = dest.isFile && (info.assetSize < 0 || dest.length() == info.assetSize) &&
                (expected == null || client.sha256(dest) == expected)
            if (!reusable) {
                val coroutine = currentCoroutineContext()
                var lastEmit = 0L
                client.download(info.assetUrl, dest) { done, total ->
                    coroutine.ensureActive() // lets "Cancel" stop the transfer
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= 100 || done == total) {
                        lastEmit = now
                        _state.value = UpdateState.Downloading(info, done, if (total > 0) total else info.assetSize)
                    }
                }
                if (info.assetSize >= 0 && dest.length() != info.assetSize) {
                    dest.delete()
                    throw IOException("The downloaded file has the wrong size")
                }
                if (expected != null && client.sha256(dest) != expected) {
                    dest.delete()
                    throw IOException("The download is corrupted (checksum mismatch)")
                }
            }
            _state.value = UpdateState.Ready(info, dest)
            install(dest, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(friendly(e), info)
        }
    }

    private fun install(file: File, info: UpdateInfo) {
        val ctx = appContext ?: return
        pending = info to file
        if (!UpdateInstaller.canInstall(ctx)) {
            _state.value = UpdateState.NeedsPermission(info, file)
            return
        }
        _state.value = UpdateState.Installing(info)
        try {
            UpdateInstaller.commit(ctx, file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't start the installer: ${e.message ?: e.javaClass.simpleName}", info)
        }
    }

    /** Opens Android's "install unknown apps" screen for this app. */
    fun openInstallSettings(context: Context) {
        try {
            context.startActivity(UpdateInstaller.settingsIntent(context))
        } catch (_: Exception) {
            // no such screen on this device
        }
    }

    /** Call when the app returns to the foreground: continues if the permission was just granted. */
    fun onForeground() {
        val st = _state.value
        val ctx = appContext ?: return
        if (st is UpdateState.NeedsPermission && UpdateInstaller.canInstall(ctx)) launchInstall(st.file, st.update)
    }

    // ---------------------------------------------------------------- installer callbacks

    fun onInstallSucceeded() {
        pending = null
        _state.value = UpdateState.Idle
    }

    fun onInstallFailed(message: String, aborted: Boolean) {
        val p = pending
        _state.value = when {
            p == null -> UpdateState.Failed(message)
            aborted -> UpdateState.Ready(p.first, p.second, note = message)
            else -> UpdateState.Failed(message, p.first)
        }
    }

    // ---------------------------------------------------------------- sheet / skipping

    fun showSheet() { if (_state.value.isActionable) _sheet.value = true }
    fun dismissSheet() { _sheet.value = false }

    fun skipThisVersion() {
        val info = _state.value.updateInfo ?: return
        settings?.skippedVersion = info.version
        job?.cancel()
        _sheet.value = false
        _state.value = UpdateState.Idle
    }

    // ---------------------------------------------------------------- helpers

    private fun cleanOldDownloads() {
        val dir = File(appContext?.cacheDir ?: return, UPDATES_DIR)
        val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun friendly(e: Exception): String = when {
        e is UpdateClient.HttpFailure && e.code == 404 -> "No release has been published yet"
        e is UpdateClient.HttpFailure && (e.code == 403 || e.code == 429) -> "GitHub is limiting requests. Try again later."
        e is UpdateClient.HttpFailure -> "The update server answered with an error (${e.code})"
        e is UnknownHostException -> "No internet connection"
        e is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Network problem"
        else -> e.message ?: "Update failed"
    }
}
