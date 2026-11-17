package com.share64.network

import java.net.URLDecoder
import java.net.URLEncoder

/** Everything another device needs to find and recognise this one. */
data class ConnectInfo(
    /** Stable device id (same as the DNS-SD id), so resume keeps working across address changes. */
    val id: String,
    val name: String,
    val type: DeviceType,
    val port: Int,
    /** Private IPv4 addresses, best first: the scanner tries them all and keeps the one that answers. */
    val addresses: List<String>,
)

sealed interface QrParse {
    data class Ok(val info: ConnectInfo) : QrParse
    data class Invalid(val reason: Reason) : QrParse

    enum class Reason { NOT_SHARE64, NEWER_VERSION, MISSING_FIELD, BAD_ID, BAD_PORT, BAD_ADDRESS }
}

/**
 * The text inside the QR code:
 *
 * `share64://c?v=1&i=<device id>&n=<name>&t=<p|t|o>&p=<port>&a=<ip>,<ip>`
 *
 * Short on purpose (about 100 bytes: a version 6 code with big, easy-to-scan modules). Only private
 * LAN addresses are accepted, so a code can never point the app at a host on the internet.
 * Unknown fields are ignored, so later versions can add to it.
 */
object QrPayload {
    const val VERSION = 1
    const val MAX_ADDRESSES = 4
    const val MAX_NAME = 32
    private const val PREFIX = "share64://c?"
    private val ID = Regex("[A-Za-z0-9_-]{8,64}")

    fun encode(info: ConnectInfo): String {
        val name = URLEncoder.encode(info.name.filter { !it.isISOControl() }.trim().take(MAX_NAME), "UTF-8")
        val addresses = info.addresses.filter(::isPrivateIpv4).take(MAX_ADDRESSES).joinToString(",")
        return "${PREFIX}v=$VERSION&i=${info.id}&n=$name&t=${info.type.wire}&p=${info.port}&a=$addresses"
    }

    fun parse(text: String): QrParse {
        val raw = text.trim()
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return QrParse.Invalid(QrParse.Reason.NOT_SHARE64)
        val params = HashMap<String, String>()
        for (part in raw.substring(PREFIX.length).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            params.putIfAbsent(part.substring(0, eq), part.substring(eq + 1))
        }
        val version = params["v"]?.toIntOrNull() ?: return QrParse.Invalid(QrParse.Reason.NOT_SHARE64)
        if (version < 1) return QrParse.Invalid(QrParse.Reason.NOT_SHARE64)
        if (version > VERSION) return QrParse.Invalid(QrParse.Reason.NEWER_VERSION)

        val id = params["i"] ?: return QrParse.Invalid(QrParse.Reason.MISSING_FIELD)
        if (!ID.matches(id)) return QrParse.Invalid(QrParse.Reason.BAD_ID)
        val port = params["p"]?.toIntOrNull() ?: return QrParse.Invalid(QrParse.Reason.MISSING_FIELD)
        if (port !in 1..65535) return QrParse.Invalid(QrParse.Reason.BAD_PORT)
        val addressField = params["a"] ?: return QrParse.Invalid(QrParse.Reason.MISSING_FIELD)
        val addresses = addressField.split(',').map { it.trim() }.filter(::isPrivateIpv4).distinct().take(MAX_ADDRESSES)
        if (addresses.isEmpty()) return QrParse.Invalid(QrParse.Reason.BAD_ADDRESS)

        val name = try {
            URLDecoder.decode(params["n"].orEmpty(), "UTF-8")
        } catch (_: IllegalArgumentException) {
            ""
        }.filter { !it.isISOControl() }.trim().take(64).ifBlank { "Unknown device" }
        return QrParse.Ok(ConnectInfo(id, name, DeviceType.fromWire(params["t"]), port, addresses))
    }

    /** 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16, written as a plain dotted quad. */
    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val n = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false
            n[i] = p.toInt()
            if (n[i] > 255) return false
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
}
