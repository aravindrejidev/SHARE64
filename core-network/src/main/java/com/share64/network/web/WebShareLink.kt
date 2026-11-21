package com.share64.network.web

import com.share64.network.QrPayload
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The address of a "share with anyone" session: `http://<private ip>:<port>/s/<token>/`.
 * It is what goes into the QR code, so any scanner can open it in a browser, and the SHARE64
 * scanner recognises it and downloads through the app instead.
 */
data class WebShareLink(val host: String, val port: Int, val token: String) {
    /** The full URL, with the trailing slash the page's relative links rely on. */
    val url: String get() = url(host, port, token)

    /** `share64://w?u=<url>`: opens the app from the "Open in SHARE64" button on the web page. */
    val appLink: String get() = "share64://w?u=" + URLEncoder.encode(url, "UTF-8")

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9]{8,32}")

        fun url(host: String, port: Int, token: String) = "http://$host:$port/s/$token/"

        /** Null unless [text] is a web-share URL (or app link) pointing at a private LAN address. */
        fun parse(text: String): WebShareLink? {
            var raw = text.trim()
            if (raw.startsWith("share64://w?", ignoreCase = true)) {
                val u = raw.substring("share64://w?".length).split('&')
                    .firstOrNull { it.startsWith("u=") }?.substring(2) ?: return null
                raw = try {
                    URLDecoder.decode(u, "UTF-8")
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            if (!raw.startsWith("http://", ignoreCase = true)) return null
            val rest = raw.substring("http://".length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val authority = rest.substring(0, slash)
            val path = rest.substring(slash)
            val colon = authority.lastIndexOf(':')
            val host = if (colon > 0) authority.substring(0, colon) else authority
            val port = if (colon > 0) authority.substring(colon + 1).toIntOrNull() ?: return null else 80
            if (port !in 1..65535 || !QrPayload.isPrivateIpv4(host)) return null
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] != "s") return null
            // anything after the token (index.html, a query) is ignored
            val token = parts[1].substringBefore('?').substringBefore('#')
            if (!TOKEN.matches(token)) return null
            return WebShareLink(host, port, token)
        }
    }
}
