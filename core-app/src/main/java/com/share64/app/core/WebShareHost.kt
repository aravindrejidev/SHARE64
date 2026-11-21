package com.share64.app.core

import android.content.Context
import android.net.Uri
import com.share64.network.web.WebShareConfig
import com.share64.network.web.WebShareFile
import com.share64.network.web.WebShareServer
import com.share64.network.web.WebShareLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** What a running "share with anyone" session offers (the server holds who is connected). */
class WebShareSession(val server: WebShareServer, val files: List<WebFileInfo>, val sender: String) {
    val totalBytes: Long get() = files.sumOf { maxOf(it.size, 0L) }
}

/**
 * The one "share with anyone" session of this process. It lives here (not in a screen) so it keeps
 * running when the person leaves the screen; the foreground service keeps the process alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object WebShareHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _session = MutableStateFlow<WebShareSession?>(null)
    val session: StateFlow<WebShareSession?> = _session.asStateFlow()

    /** True while a browser is actually downloading (drives wake locks and the notification). */
    val busy: StateFlow<Boolean> = _session
        .flatMapLatest { s -> s?.server?.clients?.map { list -> list.any { it.activeDownloads > 0 } } ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Starts serving [files]; replaces a running session. Null if no port could be opened. */
    @Synchronized
    fun start(context: Context, files: List<OutgoingFile>, sender: String, autoAllow: Boolean): WebShareSession? {
        stop()
        val app = context.applicationContext
        val shareFiles = files.mapIndexed { i, f ->
            val mime = app.contentResolver.getType(f.uri)?.takeIf { it.contains('/') } ?: FileExporter.mimeFor(f.name)
            WebShareFile("${i + 1}", f.name, f.size, mime) { offset -> openAt(app, f.uri, offset) }
        }
        val server = WebShareServer(shareFiles, WebShareConfig(sender, autoAllow = autoAllow))
        return try {
            server.start()
            val session = WebShareSession(server, shareFiles.map { WebFileInfo(it.id, it.name, it.size, it.mime) }, sender)
            server.onExpired = {
                synchronized(this) { if (_session.value?.server === server) _session.value = null }
            }
            _session.value = session
            session
        } catch (_: IOException) {
            server.close()
            null
        }
    }

    @Synchronized
    fun stop() {
        val s = _session.value ?: return
        _session.value = null
        s.server.close()
    }

    /** A stream over a picked file starting at [offset]; seeks when the provider allows it. */
    private fun openAt(context: Context, uri: Uri, offset: Long): InputStream {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        val stream = FileInputStream(pfd.fileDescriptor)
        try {
            if (offset > 0) {
                try {
                    stream.channel.position(offset)
                } catch (_: IOException) {
                    var left = offset
                    while (left > 0) {
                        val skipped = stream.skip(left)
                        if (skipped <= 0) break
                        left -= skipped
                    }
                }
            }
        } catch (e: Exception) {
            try { stream.close() } finally { pfd.close() }
            throw e
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try { super.close() } finally { pfd.close() }
            }
        }
    }
}

/** Builds the in-app downloader for a scanned or tapped web-share link. */
object WebDownloads {
    fun create(context: Context, link: WebShareLink, deviceLabel: String, treeUri: String?): WebDownloader {
        val app = context.applicationContext
        return WebDownloader(
            link = link,
            deviceLabel = deviceLabel,
            workDir = File(app.cacheDir, "web-downloads"),
            save = { file, name, _ ->
                FileExporter.export(app, file, name, treeUri)?.let { SavedFile(name, it.uri.toString(), it.location) }
            },
        )
    }
}
