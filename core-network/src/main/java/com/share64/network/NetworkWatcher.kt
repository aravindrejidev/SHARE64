package com.share64.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Emits the device's LAN IPv4 addresses whenever they change (empty list = no usable network).
 * Connectivity callbacks make it react instantly; a light poll also catches hotspot / tether
 * interfaces, which have no Network callback.
 */
class NetworkWatcher(context: Context, private val pollMs: Long = 3_000L) {
    private val appContext = context.applicationContext

    fun changes(): Flow<List<String>> = callbackFlow {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(LocalAddresses.ipv4()) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(LocalAddresses.ipv4())
            }
            override fun onLost(network: Network) { trySend(LocalAddresses.ipv4()) }
        }
        var registered = false
        try {
            // No INTERNET requirement: offline routers and hotspots are valid share networks.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (_: Exception) {
            // polling below still works
        }
        trySend(LocalAddresses.ipv4())
        val poller = launch {
            while (isActive) {
                delay(pollMs)
                trySend(LocalAddresses.ipv4())
            }
        }
        awaitClose {
            poller.cancel()
            if (registered) {
                try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            }
        }
    }.distinctUntilChanged()
}
