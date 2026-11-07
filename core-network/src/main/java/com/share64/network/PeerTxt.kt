package com.share64.network

/** DNS-SD service type and TXT record codec ("id", "n", "t", "v"). Pure Kotlin. */
object PeerTxt {
    const val SERVICE_TYPE = "_share64._tcp"
    const val PROTOCOL_VERSION = 1
    const val MAX_NAME_CHARS = 48
    private const val MAX_NAME_BYTES = 96

    private const val K_ID = "id"
    private const val K_NAME = "n"
    private const val K_TYPE = "t"
    private const val K_VER = "v"

    data class Decoded(val id: String, val name: String, val type: DeviceType, val version: Int)

    fun encode(d: LocalDevice): Map<String, String> = linkedMapOf(
        K_ID to d.id,
        K_NAME to truncateUtf8(sanitizeName(d.displayName), MAX_NAME_BYTES),
        K_TYPE to d.type.wire,
        K_VER to PROTOCOL_VERSION.toString(),
    )

    /** Returns null for anything that is not a well-formed SHARE64 record. */
    fun decode(attributes: Map<String, ByteArray?>?): Decoded? {
        if (attributes == null) return null
        val attrs = HashMap<String, String>()
        for ((k, v) in attributes) if (v != null) attrs[k.lowercase()] = v.toString(Charsets.UTF_8)
        val id = attrs[K_ID]?.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
            ?: return null
        val version = attrs[K_VER]?.toIntOrNull() ?: return null
        return Decoded(
            id = id,
            name = sanitizeName(attrs[K_NAME].orEmpty()),
            type = DeviceType.fromWire(attrs[K_TYPE]),
            version = version,
        )
    }

    fun sanitizeName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) if (!ch.isISOControl()) sb.append(ch)
        var s = sb.toString().trim()
        if (s.length > MAX_NAME_CHARS) {
            s = s.substring(0, MAX_NAME_CHARS)
            if (s.last().isHighSurrogate()) s = s.dropLast(1)
            s = s.trimEnd()
        }
        return s.ifEmpty { "Device" }
    }

    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + len > maxBytes) break
            bytes += len
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}
