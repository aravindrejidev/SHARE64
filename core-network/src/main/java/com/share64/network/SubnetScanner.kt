package com.share64.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ScanHit(val host: String, val port: Int, val name: String, val protocolVersion: Int)

/**
 * Fallback discovery for hotspots, where DNS-SD is often blocked: knock on every address of the
 * subnet with the SHARE64 handshake and see who answers.
 */
object SubnetScanner {
    /** Host addresses of the subnet around [ip] (never more than one /24), without [ip] itself. */
    fun candidates(ip: String, prefix: Int, maxHosts: Int = 254): List<String> {
        val self = parse(ip) ?: return emptyList()
        if (prefix >= 31) return emptyList()
        val p = prefix.coerceIn(24, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val network = self and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val out = ArrayList<String>()
        var h = network + 1
        while (h < broadcast && out.size < maxHosts) {
            if (h != self) out += format(h)
            h++
        }
        return out
    }

    /** Runs [probe] for every host, at most [parallelism] at a time. */
    suspend fun scan(
        hosts: List<String>,
        port: Int,
        parallelism: Int = 48,
        probe: (host: String) -> HandshakeProbe.Result?,
    ): List<ScanHit> = coroutineScope {
        val gate = Semaphore(parallelism)
        hosts
            .map { host ->
                async(Dispatchers.IO) {
                    gate.withPermit { probe(host)?.let { ScanHit(host, port, it.deviceName, it.protocolVersion) } }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun parse(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun format(v: Long): String =
        "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
