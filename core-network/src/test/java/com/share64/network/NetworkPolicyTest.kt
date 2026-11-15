package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkPolicyTest {
    private val wifi = listOf("192.168.1.23")

    @Test fun firstReadingOnlyReportsWhetherWeAreOnline() {
        assertEquals(NetworkAction(up = true, dropConnections = false), NetworkPolicy.decide(null, wifi))
        assertEquals(NetworkAction(up = false, dropConnections = false), NetworkPolicy.decide(null, emptyList()))
    }

    @Test fun losingEverythingDropsConnectionsAtOnce() {
        assertEquals(NetworkAction(up = false, dropConnections = true), NetworkPolicy.decide(wifi, emptyList()))
        assertNull(NetworkPolicy.decide(emptyList(), emptyList()))
    }

    @Test fun comingBackJustWakesWaitingTransfers() {
        assertEquals(NetworkAction(up = true, dropConnections = false), NetworkPolicy.decide(emptyList(), wifi))
    }

    @Test fun anAddressThatVanishedMeansLiveConnectionsAreDead() {
        // hopped to another access point: new IP, old one gone
        assertEquals(NetworkAction(up = true, dropConnections = true), NetworkPolicy.decide(wifi, listOf("192.168.1.99")))
        // hotspot switched off while still on Wi-Fi
        assertEquals(NetworkAction(up = true, dropConnections = true), NetworkPolicy.decide(wifi + "192.168.43.1", wifi))
    }

    @Test fun anExtraAddressLosesNothing() {
        assertEquals(NetworkAction(up = true, dropConnections = false), NetworkPolicy.decide(wifi, wifi + "192.168.43.1"))
    }

    @Test fun sameAddressesMeansNothingToDo() {
        assertNull(NetworkPolicy.decide(wifi, wifi))
    }
}
