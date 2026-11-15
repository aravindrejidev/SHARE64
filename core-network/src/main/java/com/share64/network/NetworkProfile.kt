package com.share64.network

/** What kind of network this device is on, as far as local file sharing is concerned. */
enum class NetworkKind(val label: String) {
    NONE("No network"),
    WIFI("Wi-Fi"),
    ETHERNET("Ethernet"),

    /** This device is connected to another phone's mobile hotspot. */
    HOTSPOT_CLIENT("Phone hotspot"),

    /** This device shares its own hotspot (or Wi-Fi Direct group) and the other device joins it. */
    HOTSPOT_HOST("Your hotspot"),
    USB_TETHER("USB tethering"),
    OTHER("Local network");

    /** Networks where DNS-SD is often blocked or flaky, so SHARE64 also sweeps the subnet. */
    val isHotspotLike: Boolean get() = this == HOTSPOT_CLIENT || this == HOTSPOT_HOST || this == USB_TETHER
}

/** One private IPv4 address on a LAN-type interface. */
data class LanAddress(val ip: String, val prefix: Int, val iface: String)

enum class Transport { WIFI, ETHERNET, OTHER }

/**
 * What ConnectivityManager knows about one *connected* network (the client side). Interfaces that
 * carry a LAN address but are not in this list are hosted by this device (hotspot, USB tether).
 */
data class ClientNetwork(
    val iface: String,
    val transport: Transport,
    val gateway: String?,
    /** True when Android treats the network as metered (how it flags another phone's hotspot). */
    val metered: Boolean = false,
)

data class ClassifiedAddress(val address: LanAddress, val kind: NetworkKind)

/** The device's LAN situation. [networks] is ordered best first; [kind] is the best one's kind. */
data class NetworkProfile(
    val kind: NetworkKind,
    val networks: List<ClassifiedAddress>,
    val gateway: String?,
) {
    val addresses: List<String> get() = networks.map { it.address.ip }

    /** Hotspot-like subnets that should be swept for peers when DNS-SD finds nobody. */
    val sweepTargets: List<LanAddress> get() = networks.filter { it.kind.isHotspotLike }.map { it.address }

    companion object {
        val NONE = NetworkProfile(NetworkKind.NONE, emptyList(), null)
    }
}

/** Decides what kind of network a set of interfaces is. Pure logic, no Android. */
object NetworkClassifier {
    private val HOTSPOT_GATEWAYS = setOf("192.168.43.1", "172.20.10.1", "192.168.49.1", "192.168.137.1")
    private val AP_PREFIXES = listOf("ap", "swlan", "softap", "p2p")
    private val USB_PREFIXES = listOf("rndis", "usb", "ncm")

    /**
     * [clients] = null means "ConnectivityManager could not be asked" (no permission, odd ROM):
     * names and addresses are then all we have, and a plain wlan interface is assumed to be Wi-Fi.
     */
    fun classify(lan: List<LanAddress>, clients: List<ClientNetwork>?): NetworkProfile {
        if (lan.isEmpty()) return NetworkProfile.NONE
        val networks = lan
            .map { ClassifiedAddress(it, kindOf(it, clients)) }
            .sortedWith(compareBy({ rank(it.kind) }, { it.address.ip }))
        val best = networks.first()
        val gateway = clients?.firstOrNull { it.iface == best.address.iface }?.gateway
        return NetworkProfile(best.kind, networks, gateway)
    }

    fun isHotspotGateway(gateway: String?): Boolean = gateway != null && gateway in HOTSPOT_GATEWAYS

    private fun kindOf(a: LanAddress, clients: List<ClientNetwork>?): NetworkKind {
        val name = a.iface.lowercase()
        val client = clients?.firstOrNull { it.iface == a.iface }
        if (client != null) {
            return when (client.transport) {
                Transport.ETHERNET -> NetworkKind.ETHERNET
                Transport.WIFI ->
                    if (isHotspotGateway(client.gateway) || (client.metered && client.gateway != null)) NetworkKind.HOTSPOT_CLIENT
                    else NetworkKind.WIFI
                Transport.OTHER -> if (USB_PREFIXES.any { name.startsWith(it) }) NetworkKind.USB_TETHER else NetworkKind.OTHER
            }
        }
        if (USB_PREFIXES.any { name.startsWith(it) }) return NetworkKind.USB_TETHER
        if (AP_PREFIXES.any { name.startsWith(it) }) return NetworkKind.HOTSPOT_HOST
        if (name.startsWith("wlan")) {
            // A wlan interface with an address that is not a connected Wi-Fi network is the access
            // point side of our own hotspot (some phones name it wlan0 when Wi-Fi is off, or wlan1).
            return if (clients != null) NetworkKind.HOTSPOT_HOST else NetworkKind.WIFI
        }
        if (name.startsWith("eth") || name.startsWith("en")) return NetworkKind.ETHERNET
        return NetworkKind.OTHER
    }

    private fun rank(kind: NetworkKind): Int = when (kind) {
        NetworkKind.ETHERNET -> 0
        NetworkKind.WIFI -> 1
        NetworkKind.HOTSPOT_CLIENT -> 2
        NetworkKind.HOTSPOT_HOST -> 3
        NetworkKind.USB_TETHER -> 4
        NetworkKind.OTHER -> 5
        NetworkKind.NONE -> 6
    }
}

/** What the transfer engine should be told after the address list changed. */
data class NetworkAction(val up: Boolean, val dropConnections: Boolean)

object NetworkPolicy {
    /**
     * [previous] = null on the very first reading. Returns null when nothing changed.
     *  - every address gone          -> network down, kill live connections now
     *  - back from nothing           -> network up, reconnect waiting transfers immediately
     *  - an address vanished         -> live connections are probably dead: drop them, resume
     *  - only new addresses appeared -> just wake waiting transfers (nothing was lost)
     */
    fun decide(previous: List<String>?, next: List<String>): NetworkAction? {
        if (previous == null) return NetworkAction(up = next.isNotEmpty(), dropConnections = false)
        if (next.isEmpty()) return if (previous.isEmpty()) null else NetworkAction(up = false, dropConnections = true)
        if (previous.isEmpty()) return NetworkAction(up = true, dropConnections = false)
        val lost = previous.any { it !in next }
        val gained = next.any { it !in previous }
        return when {
            lost -> NetworkAction(up = true, dropConnections = true)
            gained -> NetworkAction(up = true, dropConnections = false)
            else -> null
        }
    }
}
