package com.share64.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What happened after a QR code was scanned. */
sealed interface QrConnectResult {
    data class Connected(val peer: PeerDevice) : QrConnectResult
    data class Unreachable(val info: ConnectInfo) : QrConnectResult
    data class Incompatible(val info: ConnectInfo, val remoteVersion: Int) : QrConnectResult
    data class Invalid(val reason: QrParse.Reason) : QrConnectResult

    /** The person scanned this device's own code. */
    data object OwnCode : QrConnectResult
}

data class Reached(val host: String, val reply: HandshakeProbe.Result)

object QrConnector {
    /**
     * Knocks on every address at once and returns the first that answers (null when none does).
     * A code lists all of the other device's addresses (home Wi-Fi, hotspot, ...); only the ones
     * both devices can reach will answer. Returns as soon as there is a winner: probes still
     * waiting on their own timeouts are simply left to finish.
     */
    suspend fun firstReachable(addresses: List<String>, probe: (String) -> HandshakeProbe.Result?): Reached? {
        if (addresses.isEmpty()) return null
        val results = Channel<Reached?>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (host in addresses) {
                scope.launch {
                    val reply = try {
                        probe(host)
                    } catch (_: Exception) {
                        null
                    }
                    results.trySend(reply?.let { Reached(host, it) })
                }
            }
            var remaining = addresses.size
            while (remaining > 0) {
                val r = results.receive()
                remaining--
                if (r != null) return r
            }
            return null
        } finally {
            scope.cancel()
        }
    }
}
