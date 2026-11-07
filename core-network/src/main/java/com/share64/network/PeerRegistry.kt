package com.share64.network

/** Merges discovery results into one list. Not thread-safe: callers synchronize. Pure Kotlin. */
class PeerRegistry(private val ownId: String) {
    private val byId = LinkedHashMap<String, PeerDevice>()

    /** Adds or updates a peer. Returns false if it is this device itself. */
    fun upsert(peer: PeerDevice): Boolean {
        if (peer.id == ownId) return false
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

    fun snapshot(): List<PeerDevice> =
        byId.values.sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))
}
