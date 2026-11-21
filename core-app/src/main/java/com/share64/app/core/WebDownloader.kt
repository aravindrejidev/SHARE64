package com.share64.app.core

import com.share64.network.web.WebShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A downloaded file after it was saved to its final place. [uri] is null when it stayed in app storage. */
data class SavedFile(val name: String, val uri: String?, val location: String)

/** Why a web download could not start or finish, in terms the UI can explain. */
class WebDownloadException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { UNREACHABLE, DENIED, FULL, TIMED_OUT, REVOKED, DISK, FAILED }
}

/**
 * Receives a "share with anyone" session inside the app: connect (and wait to be allowed), show the list,
 * then download file by file into [workDir] and hand each finished file to [save] (which moves it to the
 * public Downloads folder). Interrupted downloads resume from where they stopped.
 */
class WebDownloader(
    link: WebShareLink,
    deviceLabel: String,
    private val workDir: File,
    private val save: (file: File, displayName: String, mime: String) -> SavedFile?,
    private val approvalTimeoutMs: Long = 3 * 60_000L,
    private val pollEveryMs: Long = 1_000L,
) {
    private val client = WebShareClient(link, deviceLabel)
    private val key = link.token.take(10)

    /** Asks for access, waits until the host decides, and returns the file list. */
    suspend fun connect(onWaiting: (sender: String) -> Unit): WebManifest = withContext(Dispatchers.IO) {
        var state = try {
            client.requestAccess()
        } catch (e: IOException) {
            throw WebDownloadException(WebDownloadException.Kind.UNREACHABLE, e.message ?: "unreachable")
        }
        val deadline = System.currentTimeMillis() + approvalTimeoutMs
        var announced = false
        while (true) {
            ensureActive()
            when (state) {
                WebAccess.APPROVED -> break
                WebAccess.DENIED -> throw WebDownloadException(WebDownloadException.Kind.DENIED, "declined")
                WebAccess.FULL -> throw WebDownloadException(WebDownloadException.Kind.FULL, "full")
                WebAccess.PENDING, WebAccess.NONE -> {
                    if (!announced) {
                        announced = true
                        onWaiting(client.sender)
                    }
                    if (System.currentTimeMillis() > deadline) throw WebDownloadException(WebDownloadException.Kind.TIMED_OUT, "no answer")
                    delay(pollEveryMs)
                    state = try {
                        if (state == WebAccess.NONE) client.requestAccess() else client.pollAccess()
                    } catch (e: IOException) {
                        throw WebDownloadException(WebDownloadException.Kind.UNREACHABLE, e.message ?: "unreachable")
                    }
                }
            }
        }
        try {
            client.manifest()
        } catch (e: WebShareRefused) {
            throw WebDownloadException(WebDownloadException.Kind.REVOKED, e.message ?: "revoked")
        } catch (e: IOException) {
            throw WebDownloadException(WebDownloadException.Kind.FAILED, e.message ?: "failed")
        }
    }

    /**
     * Downloads every file in order. [onProgress] gets (index of the current file, bytes of it so far,
     * bytes of all files so far). Returns what was saved; throws [WebDownloadException] on failure.
     */
    suspend fun downloadAll(
        files: List<WebFileInfo>,
        onProgress: (index: Int, fileBytes: Long, doneBytes: Long) -> Unit,
    ): List<SavedFile> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val job = coroutineContext[Job]
        val saved = ArrayList<SavedFile>()
        var finishedBytes = 0L
        files.forEachIndexed { index, f ->
            ensureActive()
            if (f.size > 0 && workDir.usableSpace < f.size + MIN_FREE_BYTES) {
                throw WebDownloadException(WebDownloadException.Kind.DISK, "not enough free space for ${f.name}")
            }
            val part = File(workDir, "web-$key-${f.id}.part")
            var attempt = 0
            while (true) {
                try {
                    client.download(f, part, isCancelled = { cancelled || job?.isActive == false }) { bytes -> onProgress(index, bytes, finishedBytes + bytes) }
                    break
                } catch (e: WebShareRefused) {
                    throw WebDownloadException(WebDownloadException.Kind.REVOKED, e.message ?: "revoked")
                } catch (e: InterruptedException) {
                    throw CancellationException("cancelled")
                } catch (e: IOException) {
                    ensureActive()
                    if (++attempt >= MAX_ATTEMPTS) throw WebDownloadException(WebDownloadException.Kind.FAILED, e.message ?: "failed")
                    delay(600L * attempt) // the part file stays, so the next attempt resumes
                }
            }
            val name = safeName(f.name)
            val result = save(part, name, f.mime) ?: SavedFile(name, null, "app storage")
            part.delete()
            saved += result
            finishedBytes += maxOf(f.size, 0L)
            onProgress(index, maxOf(f.size, 0L), finishedBytes)
        }
        saved
    }

    // The blocking read loop watches this flag and the coroutine's own cancellation.
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    companion object {
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val MAX_ATTEMPTS = 4

        /** A name that is safe as a display name in Downloads, whatever the sender called the file. */
        fun safeName(raw: String): String {
            val cleaned = raw.filter { !it.isISOControl() && it != '/' && it != '\\' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
                .trim().trimStart('.').take(120)
            return cleaned.ifBlank { "file" }
        }
    }
}
