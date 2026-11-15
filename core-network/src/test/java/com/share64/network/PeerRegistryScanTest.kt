package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRegistryScanTest {
    private fun scanned(host: String, reachable: Boolean = true) = PeerDevice(
        id = "${PeerRegistry.SCAN_PREFIX}$host:52364", serviceName = "", displayName = "Found at $host",
        type = DeviceType.OTHER, host = host, port = 52364, protocolVersion = 1, reachable = reachable,
    )

    private fun real(id: String, host: String) =
        PeerDevice(id, "svc-$id", "Phone $id", DeviceType.PHONE, host, 52364, 1)

    @Test fun sweepFindIsReplacedWhenDnsSdLearnsTheRealIdentity() {
        val r = PeerRegistry("me")
        assertTrue(r.upsert(scanned("192.168.43.5")))
        assertTrue(r.upsert(real("abc", "192.168.43.5")))
        assertEquals(listOf("abc"), r.snapshot().map { it.id })
    }

    @Test fun sweepFindOfAKnownDeviceIsIgnored() {
        val r = PeerRegistry("me")
        r.upsert(real("abc", "192.168.43.5"))
        assertFalse(r.upsert(scanned("192.168.43.5")))
        assertEquals(1, r.snapshot().size)
    }

    @Test fun sweepFindsOnOtherAddressesStay() {
        val r = PeerRegistry("me")
        r.upsert(real("abc", "192.168.43.5"))
        assertTrue(r.upsert(scanned("192.168.43.9")))
        assertEquals(2, r.snapshot().size)
    }

    @Test fun unreachableSweepFindsAreForgottenButRealPeersStay() {
        val r = PeerRegistry("me")
        r.upsert(scanned("192.168.43.5", reachable = false))
        r.upsert(scanned("192.168.43.6", reachable = true))
        r.upsert(real("abc", "192.168.43.7").copy(reachable = false))
        assertTrue(r.dropUnreachableScanned())
        assertEquals(setOf("scan:192.168.43.6:52364", "abc"), r.snapshot().map { it.id }.toSet())
        assertFalse(r.dropUnreachableScanned())
    }

    @Test fun discoveredCountIgnoresManualPeers() {
        val r = PeerRegistry("me")
        r.upsert(real("abc", "10.0.0.2").copy(manual = true))
        assertEquals(0, r.discoveredCount())
        r.upsert(scanned("10.0.0.3"))
        assertEquals(1, r.discoveredCount())
    }
}
