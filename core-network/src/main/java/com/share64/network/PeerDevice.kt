package com.share64.network

enum class DeviceType(val wire: String) {
    PHONE("p"), TV("t"), OTHER("o");

    companion object {
        fun fromWire(s: String?): DeviceType = entries.firstOrNull { it.wire == s } ?: OTHER
    }
}

/** This device, as advertised to the network. [id] is stable across restarts and IP changes. */
data class LocalDevice(val id: String, val displayName: String, val type: DeviceType)

/** A discovered (or manually added) SHARE64 device. */
data class PeerDevice(
    /** Stable device id (TXT "id"), or "manual:host:port" for manually added peers. */
    val id: String,
    /** NSD instance name; empty for manual peers. */
    val serviceName: String,
    val displayName: String,
    val type: DeviceType,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    /** False when the last TCP liveness probe failed. The peer stays listed (it may come back). */
    val reachable: Boolean = true,
    val lastSeenMs: Long = 0L,
    val manual: Boolean = false,
)
