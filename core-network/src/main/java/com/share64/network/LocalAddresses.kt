package com.share64.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** Private IPv4 addresses on LAN-type interfaces (Wi-Fi, Ethernet, hotspot, USB tether). Pure JVM. */
object LocalAddresses {
    private val lanPrefixes = listOf("wlan", "eth", "ap", "swlan", "softap", "p2p", "br", "en", "rndis", "usb")

    fun ipv4(): List<String> = lan().map { it.ip }

    /** Same as [ipv4] but with the subnet prefix length and the interface each address lives on. */
    fun lan(): List<LanAddress> {
        val out = ArrayList<LanAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return out
        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (lanPrefixes.none { name.startsWith(it) }) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        out += LanAddress(ip, ia.networkPrefixLength.toInt(), nif.name)
                    }
                }
            } catch (_: Exception) {
                // interface vanished while enumerating
            }
        }
        return out.distinctBy { it.ip }.sortedBy { it.ip }
    }
}
