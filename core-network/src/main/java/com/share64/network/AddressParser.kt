package com.share64.network

/** Parses what a person types for a manual connection: "192.168.1.5", "192.168.1.5:52364", "[fe80::1]:52364". */
object AddressParser {
    data class Address(val host: String, val port: Int?)

    fun parse(input: String): Address? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 2) return null
            val host = s.substring(1, end)
            val rest = s.substring(end + 1)
            if (rest.isEmpty()) return Address(host, null)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return Address(host, port)
        }
        val colons = s.count { it == ':' }
        return when {
            colons == 0 -> Address(s, null)
            colons == 1 -> {
                val host = s.substringBefore(':')
                val port = s.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                if (host.isEmpty()) null else Address(host, port)
            }
            else -> Address(s, null) // bare IPv6 literal
        }
    }
}
