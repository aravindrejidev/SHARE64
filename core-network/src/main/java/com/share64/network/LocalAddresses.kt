package com.share64.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** Private IPv4 addresses on LAN-type interfaces (Wi-Fi, Ethernet, hotspot, USB tether). Pure JVM. */
object LocalAddresses {
    private val lanPrefixes = listOf("wlan", "eth", "ap", "swlan", "softap", "p2p", "br", "en", "rndis", "usb")

    fun ipv4(): List<String> {
        val out = ArrayList<String>()
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
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) addr.hostAddress?.let { out += it }
                }
            } catch (_: Exception) {
                // interface vanished while enumerating
            }
        }
        return out.distinct().sorted()
    }
}
