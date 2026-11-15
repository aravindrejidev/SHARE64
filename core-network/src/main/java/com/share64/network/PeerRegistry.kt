package com.share64.network

/** Merges discovery results into one list. Not thread-safe: callers synchronize. Pure Kotlin. */
class PeerRegistry(private val ownId: String) {
    private val byId = LinkedHashMap<String, PeerDevice>()

    /**
     * Adds or updates a peer. Returns false if it is this device itself, or a subnet-sweep find
     * (id "scan:...") of a device that is already known at that address under its real identity.
     */
    fun upsert(peer: PeerDevice): Boolean {
        if (peer.id == ownId) return false
        if (isScanned(peer)) {
            if (byId.values.any { !isScanned(it) && it.host == peer.host && it.port == peer.port }) return false
        } else {
            // DNS-SD (or the user) just told us who a device the sweep found first really is.
            byId.values.removeAll { isScanned(it) && it.host == peer.host && it.port == peer.port }
        }
        byId[peer.id] = peer
        return true
    }

    /**
     * Removes the peer registered under this NSD instance name. A peer that has since re-registered
     * under a different instance name (same device id) is kept.
     */
    fun removeByServiceName(serviceName: String): PeerDevice? {
        val hit = byId.values.firstOrNull { !it.manual && it.serviceName == serviceName } ?: return null
        byId.remove(hit.id)
        return hit
    }

    fun remove(id: String): PeerDevice? = byId.remove(id)

    fun markReachable(id: String, reachable: Boolean, nowMs: Long) {
        val p = byId[id] ?: return
        byId[id] = p.copy(reachable = reachable, lastSeenMs = if (reachable) nowMs else p.lastSeenMs)
    }

    /** Drops everything found via NSD (network changed); manually added peers stay. */
    fun clearDiscovered() {
        byId.values.removeAll { !it.manual }
    }

    /** Peers found by DNS-SD or the subnet sweep (not typed in by hand). */
    fun discoveredCount(): Int = byId.values.count { !it.manual }

    /** Sweep finds have no stable identity: forget them as soon as they stop answering. */
    fun dropUnreachableScanned(): Boolean = byId.values.removeAll { isScanned(it) && !it.reachable }

    fun snapshot(): List<PeerDevice> =
        byId.values.sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))

    companion object {
        /** Id prefix of devices found by sweeping the subnet. */
        const val SCAN_PREFIX = "scan:"

        fun isScanned(peer: PeerDevice): Boolean = peer.id.startsWith(SCAN_PREFIX)
    }
}
