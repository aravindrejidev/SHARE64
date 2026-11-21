package com.share64.app.core

import com.share64.network.web.MiniHttp
import com.share64.network.web.WebShareLink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder

data class WebFileInfo(val id: String, val name: String, val size: Long, val mime: String)

data class WebManifest(val sender: String, val files: List<WebFileInfo>)

enum class WebAccess { APPROVED, PENDING, DENIED, FULL, NONE }

/** The sender revoked this device (or the session ended) while we were talking to it. */
class WebShareRefused(message: String) : IOException(message)

/**
 * The app's side of a "share with anyone" link: asks the host for access, reads the file list and
 * downloads files with resume. Speaks plain HTTP over a raw socket (see [MiniHttp]).
 */
class WebShareClient(private val link: WebShareLink, private val deviceLabel: String) {
    @Volatile
    var clientId: String = ""
        private set

    @Volatile
    var sender: String = ""
        private set

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun path(p: String): String {
        val c = if (clientId.isEmpty()) "" else (if (p.contains('?')) "&" else "?") + "c=" + enc(clientId)
        return "/s/${link.token}/$p$c"
    }

    /** Knocks on the door: the host's phone shows an "Allow?" prompt unless it auto-allows. */
    fun requestAccess(): WebAccess = access(post = true)

    fun pollAccess(): WebAccess = access(post = false)

    private fun access(post: Boolean): WebAccess {
        val body = if (post) ("label=" + enc(deviceLabel)).toByteArray() else null
        val headers = if (post) mapOf("Content-Type" to "application/x-www-form-urlencoded") else emptyMap()
        MiniHttp.request(if (post) "POST" else "GET", link.host, link.port, path("access"), headers, body).use { res ->
            if (res.status != 200) throw IOException("The share answered with HTTP ${res.status}")
            val json = parseObject(res.readText())
            if (json["app"] != "share64") throw IOException("That is not a SHARE64 share")
            (json["sender"] as? String)?.let { sender = it }
            (json["client"] as? String)?.let { clientId = it }
            return when (json["state"]) {
                "approved" -> WebAccess.APPROVED
                "pending" -> WebAccess.PENDING
                "denied" -> WebAccess.DENIED
                "full" -> WebAccess.FULL
                else -> WebAccess.NONE
            }
        }
    }

    fun manifest(): WebManifest {
        MiniHttp.request("GET", link.host, link.port, path("manifest.json")).use { res ->
            if (res.status == 403) throw WebShareRefused("Not allowed (yet)")
            if (res.status != 200) throw IOException("The share answered with HTTP ${res.status}")
            val json = parseObject(res.readText())
            val files = (json["files"] as? List<*>)?.mapNotNull { f ->
                val m = f as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val name = m["name"] as? String ?: return@mapNotNull null
                WebFileInfo(id, name, (m["size"] as? Number)?.toLong() ?: -1L, (m["mime"] as? String).orEmpty())
            } ?: throw IOException("Unreadable file list")
            (json["sender"] as? String)?.let { sender = it }
            return WebManifest(sender, files)
        }
    }

    /**
     * Downloads [file] into [dest]. If [dest] already holds the first part (an earlier attempt), the
     * download continues from there with a Range request. [onBytes] gets the total bytes now in [dest].
     */
    fun download(file: WebFileInfo, dest: File, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        if (file.size >= 0 && have > file.size) {
            dest.delete()
            have = 0
        }
        if (file.size >= 0 && have == file.size && have > 0) {
            onBytes(have)
            return
        }
        val headers = if (have > 0 && file.size >= 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        MiniHttp.request("GET", link.host, link.port, path("f/${enc(file.id)}/${enc(file.name)}"), headers, readTimeoutMs = 30_000).use { res ->
            val append = when (res.status) {
                200 -> false
                206 -> true
                403 -> throw WebShareRefused("The sender no longer allows this device")
                404 -> throw IOException("The sender removed this file")
                416 -> {
                    dest.delete()
                    throw IOException("Resume point rejected; starting over")
                }
                else -> throw IOException("The share answered with HTTP ${res.status}")
            }
            if (!append) have = 0
            FileOutputStream(dest, append).use { out ->
                val buf = ByteArray(64 * 1024)
                onBytes(have)
                while (true) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    val n = res.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    have += n
                    onBytes(have)
                }
            }
        }
        if (file.size >= 0 && have != file.size) throw IOException("The download stopped early (${have} of ${file.size} bytes)")
    }

    private fun parseObject(text: String): Map<*, *> = try {
        MiniJson.parse(text) as? Map<*, *> ?: throw IOException("Unexpected reply")
    } catch (e: IllegalArgumentException) {
        throw IOException("Unreadable reply", e)
    }
}
