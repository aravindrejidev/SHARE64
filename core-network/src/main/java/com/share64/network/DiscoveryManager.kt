package com.share64.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface DiscoveryStatus {
    data object Stopped : DiscoveryStatus
    data object NoNetwork : DiscoveryStatus
    data class Running(val address: String) : DiscoveryStatus
    data class Error(val message: String) : DiscoveryStatus
}

/**
 * Advertises this device and finds others over DNS-SD (NsdManager). Restarts itself on network
 * changes and NSD failures, serializes the platform's one-at-a-time resolver, and keeps a TCP
 * liveness flag per peer.
 */
class DiscoveryManager(
    context: Context,
    private val scope: CoroutineScope,
    private val local: LocalDevice,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val watcher = NetworkWatcher(appContext)
    private val registry = PeerRegistry(local.id)

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    /** LAN IPv4 addresses of this device (show them for manual connection). */
    val localAddresses: StateFlow<List<String>> = _addresses.asStateFlow()

    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    private var job: Job? = null
    @Volatile private var ownServiceName: String? = null

    @Synchronized
    fun start(port: Int) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                watcher.changes().collectLatest { addresses ->
                    _addresses.value = addresses
                    clearDiscovered()
                    if (addresses.isEmpty()) {
                        _status.value = DiscoveryStatus.NoNetwork
                        return@collectLatest
                    }
                    while (true) {
                        _status.value = DiscoveryStatus.Running(addresses.first())
                        val reason = runNsd(port)
                        _status.value = DiscoveryStatus.Error(reason)
                        delay(RESTART_DELAY_MS)
                    }
                }
            } finally {
                _status.value = DiscoveryStatus.Stopped
                clearDiscovered()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    fun addManualPeer(peer: PeerDevice) {
        synchronized(registry) { registry.upsert(peer.copy(manual = true)) }
        publish()
    }

    fun removePeer(id: String) {
        synchronized(registry) { registry.remove(id) }
        publish()
    }

    // ---------------------------------------------------------------- one NSD session

    /** Registers + discovers until something fails (returns the reason) or the coroutine is cancelled. */
    private suspend fun runNsd(port: Int): String = coroutineScope {
        val failures = Channel<String>(Channel.CONFLATED)
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        ownServiceName = null

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { ownServiceName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                failures.trySend("service registration failed ($errorCode)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains(PeerTxt.SERVICE_TYPE) != true) return
                if (info.serviceName == ownServiceName) return
                resolveQueue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val removed = synchronized(registry) { registry.removeByServiceName(info.serviceName) }
                if (removed != null) publish()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                failures.trySend("discovery start failed ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        var registered = false
        var discovering = false
        var resolver: Job? = null
        var prober: Job? = null
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "SHARE64-" + local.id.take(8)
                serviceType = PeerTxt.SERVICE_TYPE
                this.port = port
                PeerTxt.encode(local).forEach { (k, v) -> setAttribute(k, v) }
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registered = true
            nsd.discoverServices(PeerTxt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            discovering = true
            resolver = launch { resolveLoop(resolveQueue) }
            prober = launch { probeLoop() }
            failures.receive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            resolver?.cancel()
            prober?.cancel()
            withContext(NonCancellable) {
                resolveQueue.close()
                if (discovering) runCatching { nsd.stopServiceDiscovery(discovery) }
                if (registered) runCatching { nsd.unregisterService(registration) }
            }
        }
    }

    // ---------------------------------------------------------------- resolving

    private sealed interface Resolve {
        data class Ok(val info: NsdServiceInfo) : Resolve
        data object Busy : Resolve
        data class Failed(val code: Int) : Resolve
    }

    private suspend fun resolveLoop(queue: ReceiveChannel<NsdServiceInfo>) {
        for (found in queue) {
            var attempt = 0
            while (attempt < 4) {
                when (val r = resolveOnce(found)) {
                    is Resolve.Ok -> { handleResolved(r.info); break }
                    Resolve.Busy -> delay(300)
                    is Resolve.Failed -> delay(500)
                }
                attempt++
            }
        }
    }

    // NsdManager.resolveService allows one active resolve at a time, hence the queue above.
    private suspend fun resolveOnce(found: NsdServiceInfo): Resolve =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Resolve> { cont ->
                try {
                    nsd.resolveService(found, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            cont.resumeOnce(
                                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) Resolve.Busy
                                else Resolve.Failed(errorCode),
                            )
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) { cont.resumeOnce(Resolve.Ok(info)) }
                    })
                } catch (e: IllegalArgumentException) {
                    cont.resumeOnce(Resolve.Failed(-1))
                }
            }
        } ?: Resolve.Failed(-2)

    private fun CancellableContinuation<Resolve>.resumeOnce(value: Resolve) {
        if (isActive) {
            try { resume(value) } catch (_: IllegalStateException) { /* resolved twice */ }
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val txt = PeerTxt.decode(info.attributes) ?: return
        if (txt.id == local.id) return
        val host = info.host?.hostAddress ?: return
        if (info.port !in 1..65535) return
        val added = synchronized(registry) {
            registry.upsert(
                PeerDevice(
                    id = txt.id,
                    serviceName = info.serviceName,
                    displayName = txt.name,
                    type = txt.type,
                    host = host,
                    port = info.port,
                    protocolVersion = txt.version,
                    reachable = true,
                    lastSeenMs = System.currentTimeMillis(),
                ),
            )
        }
        if (added) publish()
    }

    // ---------------------------------------------------------------- liveness

    private suspend fun probeLoop() {
        while (true) {
            delay(PROBE_INTERVAL_MS)
            val peers = synchronized(registry) { registry.snapshot() }
            if (peers.isEmpty()) continue
            val results = coroutineScope {
                peers.map { p -> async(Dispatchers.IO) { p.id to HandshakeProbe.isReachable(p.host, p.port) } }.awaitAll()
            }
            val now = System.currentTimeMillis()
            synchronized(registry) { results.forEach { (id, ok) -> registry.markReachable(id, ok, now) } }
            publish()
        }
    }

    private fun clearDiscovered() {
        synchronized(registry) { registry.clearDiscovered() }
        publish()
    }

    private fun publish() {
        _peers.value = synchronized(registry) { registry.snapshot() }
    }

    private companion object {
        const val RESTART_DELAY_MS = 3_000L
        const val RESOLVE_TIMEOUT_MS = 6_000L
        const val PROBE_INTERVAL_MS = 6_000L
    }
}
