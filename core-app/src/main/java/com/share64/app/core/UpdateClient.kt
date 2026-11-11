package com.share64.app.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Talks to GitHub (or any server with the same layout): latest release JSON, the release-notice
 * file from the repo, SHA256SUMS.txt and the APK itself. JVM only (no Android classes), so it is
 * tested against a local HTTP server.
 */
class UpdateClient(
    private val apiUrl: String,
    private val rawBase: String,
    private val noticeFiles: List<String>,
    private val userAgent: String = "SHARE64-updater",
) {
    class HttpFailure(val code: Int) : IOException("HTTP $code")

    fun fetchLatest(): ReleaseInfo {
        val text = getText(apiUrl, mapOf("Accept" to "application/vnd.github+json"))
        return ReleaseParser.parse(text) ?: throw IOException("Unexpected response from the update server")
    }

    /**
     * Release notes: the first non-empty notice file found at the release tag, then on the main
     * branch. Returns "" when none exists (the app then shows no notes).
     */
    fun fetchNotes(tag: String, branches: List<String> = listOf("main")): String {
        for (ref in (listOf(tag) + branches).distinct()) {
            for (file in noticeFiles) {
                try {
                    val t = getText("$rawBase/${URLEncoder.encode(ref, "UTF-8")}/$file", emptyMap(), 200_000).trim()
                    if (t.isNotEmpty()) return t
                } catch (_: IOException) {
                    // try the next candidate
                }
            }
        }
        return ""
    }

    fun fetchChecksums(url: String): Map<String, String> = Checksums.parse(getText(url, emptyMap(), 200_000))

    /** Downloads to [dest] (via a .part file). [onProgress] may throw to cancel. */
    fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var ok = false
        try {
            val c = open(url, emptyMap())
            if (c.responseCode != 200) throw HttpFailure(c.responseCode)
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total >= 0 && part.length() != total) throw IOException("The download was cut short")
            if (dest.exists() && !dest.delete()) throw IOException("Can't replace the old file")
            if (!part.renameTo(dest)) throw IOException("Can't save the download")
            ok = true
        } finally {
            if (!ok) part.delete()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun getText(url: String, headers: Map<String, String>, maxBytes: Int = 2_000_000): String {
        val c = open(url, headers)
        try {
            if (c.responseCode != 200) throw HttpFailure(c.responseCode)
            val bytes = c.inputStream.use { it.readNBytesCompat(maxBytes) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
