package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClassifierTest {
    private fun lan(ip: String, iface: String, prefix: Int = 24) = LanAddress(ip, prefix, iface)

    @Test fun noAddressesMeansNoNetwork() {
        val p = NetworkClassifier.classify(emptyList(), emptyList())
        assertEquals(NetworkKind.NONE, p.kind)
        assertTrue(p.addresses.isEmpty())
    }

    @Test fun homeRouterWifiIsPlainWifi() {
        val p = NetworkClassifier.classify(
            listOf(lan("192.168.1.23", "wlan0")),
            listOf(ClientNetwork("wlan0", Transport.WIFI, "192.168.1.1")),
        )
        assertEquals(NetworkKind.WIFI, p.kind)
        assertEquals("192.168.1.1", p.gateway)
        assertTrue(p.sweepTargets.isEmpty())
    }

    @Test fun connectedToAnotherPhonesHotspotByGateway() {
        for (gw in listOf("192.168.43.1", "172.20.10.1", "192.168.49.1")) {
            val p = NetworkClassifier.classify(
                listOf(lan("192.168.43.77", "wlan0")),
                listOf(ClientNetwork("wlan0", Transport.WIFI, gw)),
            )
            assertEquals(gw, NetworkKind.HOTSPOT_CLIENT, p.kind)
            assertEquals(1, p.sweepTargets.size)
        }
    }

    @Test fun meteredWifiWithGatewayLooksLikeAHotspot() {
        val p = NetworkClassifier.classify(
            listOf(lan("10.69.4.55", "wlan0")),
            listOf(ClientNetwork("wlan0", Transport.WIFI, "10.69.4.1", metered = true)),
        )
        assertEquals(NetworkKind.HOTSPOT_CLIENT, p.kind)
    }

    @Test fun ourOwnHotspotIsAnInterfaceNoClientNetworkOwns() {
        for (iface in listOf("ap0", "swlan0", "softap0", "wlan1", "wlan0")) {
            val p = NetworkClassifier.classify(listOf(lan("192.168.43.1", iface)), emptyList())
            assertEquals(iface, NetworkKind.HOTSPOT_HOST, p.kind)
        }
    }

    @Test fun withoutConnectivityInfoPlainWlanIsAssumedWifi() {
        val p = NetworkClassifier.classify(listOf(lan("192.168.1.9", "wlan0")), null)
        assertEquals(NetworkKind.WIFI, p.kind)
        val ap = NetworkClassifier.classify(listOf(lan("192.168.43.1", "ap0")), null)
        assertEquals(NetworkKind.HOTSPOT_HOST, ap.kind)
    }

    @Test fun wifiAndOwnHotspotAtOnceKeepsWifiFirstAndSweepsOnlyTheHotspot() {
        val p = NetworkClassifier.classify(
            listOf(lan("192.168.43.1", "ap0"), lan("192.168.1.23", "wlan0")),
            listOf(ClientNetwork("wlan0", Transport.WIFI, "192.168.1.1")),
        )
        assertEquals(NetworkKind.WIFI, p.kind)
        assertEquals(listOf("192.168.1.23", "192.168.43.1"), p.addresses)
        assertEquals(listOf("192.168.43.1"), p.sweepTargets.map { it.ip })
    }

    @Test fun ethernetBeatsWifi() {
        val p = NetworkClassifier.classify(
            listOf(lan("192.168.1.30", "wlan0"), lan("192.168.1.31", "eth0")),
            listOf(
                ClientNetwork("wlan0", Transport.WIFI, "192.168.1.1"),
                ClientNetwork("eth0", Transport.ETHERNET, "192.168.1.1"),
            ),
        )
        assertEquals(NetworkKind.ETHERNET, p.kind)
        assertEquals("192.168.1.31", p.addresses.first())
    }

    @Test fun usbTetheringIsRecognised() {
        val p = NetworkClassifier.classify(listOf(lan("192.168.42.129", "rndis0")), emptyList())
        assertEquals(NetworkKind.USB_TETHER, p.kind)
        assertTrue(p.kind.isHotspotLike)
    }
}
