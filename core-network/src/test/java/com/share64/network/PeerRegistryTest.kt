package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRegistryTest {
    private fun peer(id: String, name: String = id, service: String = "svc-$id", manual: Boolean = false, host: String = "10.0.0.1") =
        PeerDevice(id, service, name, DeviceType.PHONE, host, 52364, 1, manual = manual)

    @Test fun ignoresSelf() {
        val r = PeerRegistry("me")
        assertFalse(r.upsert(peer("me")))
        assertTrue(r.snapshot().isEmpty())
    }

    @Test fun upsertReplacesSameDevice() {
        val r = PeerRegistry("me")
        r.upsert(peer("a", host = "10.0.0.1"))
        r.upsert(peer("a", host = "10.0.0.9"))
        assertEquals(1, r.snapshot().size)
        assertEquals("10.0.0.9", r.snapshot().single().host)
    }

    @Test fun lossOfOldServiceNameKeepsReRegisteredPeer() {
        val r = PeerRegistry("me")
        r.upsert(peer("a", service = "SHARE64-a"))
        r.upsert(peer("a", service = "SHARE64-a (2)")) // same device, renamed after a conflict
        assertNull(r.removeByServiceName("SHARE64-a"))
        assertEquals(1, r.snapshot().size)
        assertNotNull(r.removeByServiceName("SHARE64-a (2)"))
        assertTrue(r.snapshot().isEmpty())
    }

    @Test fun clearDiscoveredKeepsManualPeers() {
        val r = PeerRegistry("me")
        r.upsert(peer("a"))
        r.upsert(peer("manual:1.2.3.4:52364", manual = true, service = ""))
        r.clearDiscovered()
        assertEquals(listOf("manual:1.2.3.4:52364"), r.snapshot().map { it.id })
        assertNull(r.removeByServiceName("")) // manual peers are never matched by service name
    }

    @Test fun snapshotIsSortedCaseInsensitively() {
        val r = PeerRegistry("me")
        r.upsert(peer("1", name = "zebra"))
        r.upsert(peer("2", name = "Apple"))
        r.upsert(peer("3", name = "banana"))
        assertEquals(listOf("Apple", "banana", "zebra"), r.snapshot().map { it.displayName })
    }

    @Test fun reachabilityTracksLastSeen() {
        val r = PeerRegistry("me")
        r.upsert(peer("a").copy(lastSeenMs = 5))
        r.markReachable("a", false, 100)
        assertFalse(r.snapshot().single().reachable)
        assertEquals(5L, r.snapshot().single().lastSeenMs)
        r.markReachable("a", true, 200)
        assertTrue(r.snapshot().single().reachable)
        assertEquals(200L, r.snapshot().single().lastSeenMs)
        r.markReachable("unknown", true, 1) // no-op
    }
}
