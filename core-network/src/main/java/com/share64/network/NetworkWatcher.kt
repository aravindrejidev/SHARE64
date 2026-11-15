package com.share64.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Tells everyone what network this device is on, and when that changes.
 *
 * Sources, all feeding the same snapshot: connectivity callbacks (Wi-Fi / Ethernet, instant),
 * system broadcasts for our own hotspot and tethering (which have no Network object), and a light
 * poll as the safety net for ROMs that skip those broadcasts.
 */
class NetworkWatcher(context: Context, private val pollMs: Long = 3_000L) {
    private val appContext = context.applicationContext

    /** The classified network situation; emits again only when it really changed. */
    fun profiles(): Flow<NetworkProfile> = callbackFlow {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun publish() { trySend(snapshot(cm)) }
        // Interfaces and routes settle a moment after the system announces a change.
        fun publishSoonAfter() { launch { delay(700); publish() } }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { publish(); publishSoonAfter() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { publish() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { publish() }
            override fun onLost(network: Network) { publish(); publishSoonAfter() }
        }
        var callbackRegistered = false
        try {
            // No INTERNET requirement: offline routers and hotspots are valid share networks.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            callbackRegistered = true
        } catch (_: Exception) {
            // polling below still works
        }

        // Our own hotspot / tethering switching on or off. These are system broadcasts; if a ROM
        // does not send them the poll catches the change a few seconds later.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { publish(); publishSoonAfter() }
        }
        var receiverRegistered = false
        try {
            val filter = IntentFilter().apply {
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                addAction("android.net.conn.TETHER_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // polling below still works
        }

        publish()
        val poller = launch {
            while (isActive) {
                delay(pollMs)
                publish()
            }
        }
        awaitClose {
            poller.cancel()
            if (callbackRegistered) {
                try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            }
            if (receiverRegistered) {
                try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }.distinctUntilChanged()

    /** Just the LAN IPv4 addresses (empty = no usable network). */
    fun changes(): Flow<List<String>> = profiles().map { it.addresses }.distinctUntilChanged()

    private fun snapshot(cm: ConnectivityManager): NetworkProfile =
        try {
            NetworkClassifier.classify(LocalAddresses.lan(), clientNetworks(cm))
        } catch (_: Exception) {
            NetworkProfile.NONE
        }

    /** Connected networks as ConnectivityManager sees them; null when it cannot be asked. */
    @Suppress("DEPRECATION")
    private fun clientNetworks(cm: ConnectivityManager): List<ClientNetwork>? = try {
        cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val props = cm.getLinkProperties(network) ?: return@mapNotNull null
            val iface = props.interfaceName ?: return@mapNotNull null
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                else -> Transport.OTHER
            }
            val gateway = props.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            ClientNetwork(iface, transport, gateway, metered)
        }
    } catch (_: Exception) {
        null
    }
}
