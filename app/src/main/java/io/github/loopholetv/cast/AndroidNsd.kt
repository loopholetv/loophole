package io.github.loopholetv.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import io.github.loopholetv.cast.net.lanAddress
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor

/** A multicast lock held while discovery runs; some Wi-Fi chipsets drop broadcasts without one. */
fun multicastHold(context: Context): () -> AutoCloseable = {
    val lock = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("loophole-cast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: SecurityException) {
        null
    }
    AutoCloseable { runCatching { lock?.release() } }
}

/** The addresses to beacon to: the limited broadcast plus the LAN's subnet broadcast. */
fun lanBroadcastTargets(context: Context, port: Int): () -> List<InetSocketAddress> = {
    val limited = InetSocketAddress("255.255.255.255", port)
    val subnet = subnetBroadcast(context)?.let { InetSocketAddress(it, port) }
    listOfNotNull(limited, subnet)
}

/** Sends beacons out of the LAN network, so a VPN can't carry them. */
fun lanSenderBinding(context: Context): (DatagramSocket) -> Unit = { socket ->
    try {
        lanNetwork(context)?.first?.bindSocket(socket)
    } catch (_: IOException) {
        // Falls back to the default route.
    }
}

@Suppress("DEPRECATION")
private fun lanNetwork(context: Context): Pair<Network, Inet4Address>? {
    val own = lanAddress(context) ?: return null
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = cm.allNetworks.firstOrNull { n ->
        cm.getLinkProperties(n)?.linkAddresses?.any { it.address == own } == true
    } ?: return null
    return network to own
}

private fun subnetBroadcast(context: Context): InetAddress? {
    val (network, own) = lanNetwork(context) ?: return null
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val prefix = cm.getLinkProperties(network)?.linkAddresses
        ?.firstOrNull { it.address == own }?.prefixLength ?: return null
    return subnetBroadcast(own, prefix)
}

/** The broadcast address of [own]'s subnet; null where there is none (a prefix outside 1..30). */
internal fun subnetBroadcast(own: Inet4Address, prefix: Int): InetAddress? {
    if (prefix !in 1..30) return null
    val mask = -1 shl (32 - prefix)
    val ip = own.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    val broadcast = ip or mask.inv()
    return InetAddress.getByAddress(ByteArray(4) { (broadcast ushr (24 - 8 * it)).toByte() })
}

/**
 * [NsdPort] over the platform's NsdManager. Every call degrades quietly: discovery is best-effort.
 * Known services are re-resolved (below API 34) or re-delivered (34 and up) about every 6 s, so a
 * TV that only service discovery finds stays fresh in the peer table. A "service lost" only stops
 * that refresh; nothing is reported as lost, because it is unauthenticated.
 */
class AndroidNsd(context: Context) : NsdPort {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var registration: NsdManager.RegistrationListener? = null
    private var ownName: String? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private val known = LinkedHashMap<String, NsdServiceInfo>()
    private val lastDelivery = LinkedHashMap<String, Pair<String, Map<String, String>>>()
    private val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val queue = ResolveQueue<NsdServiceInfo>(MAX_KNOWN)
    private var found: ((String, Map<String, String>) -> Unit)? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            if (synchronized(lock) { discovery } == null) return
            refreshKnown()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun register(serviceName: String, port: Int, txt: Map<String, String>) {
        val nsd = manager ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = CastDiscovery.SERVICE_TYPE
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        // A listener is used for one registration only.
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                synchronized(lock) { if (registration === this) ownName = info.serviceName }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(lock) {
            registration = listener
            ownName = serviceName
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            synchronized(lock) { registration = null }
        }
    }

    override fun unregister() {
        val nsd = manager ?: return
        val listener = synchronized(lock) {
            registration.also {
                registration = null
                ownName = null
            }
        } ?: return
        try {
            nsd.unregisterService(listener)
        } catch (_: RuntimeException) {
            // Already gone.
        }
    }

    override fun browse(onFound: (String, Map<String, String>) -> Unit, onLost: (String) -> Unit) {
        val nsd = manager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = serviceFound(info)
            override fun onServiceLost(info: NsdServiceInfo) = serviceLost(info.serviceName)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        synchronized(lock) {
            found = onFound
            discovery = listener
        }
        try {
            nsd.discoverServices(CastDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            main.postDelayed(refreshTick, REFRESH_MS)
        } catch (_: RuntimeException) {
            synchronized(lock) { discovery = null }
        }
    }

    override fun stopBrowse() {
        val nsd = manager
        val (listener, callbacks) = synchronized(lock) {
            val pair = discovery to infoCallbacks.values.toList()
            discovery = null
            found = null
            infoCallbacks.clear()
            known.clear()
            lastDelivery.clear()
            pair
        }
        queue.clear()
        main.removeCallbacksAndMessages(null)
        if (nsd == null) return
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        if (listener != null) runCatching { nsd.stopServiceDiscovery(listener) }
    }

    private fun serviceFound(info: NsdServiceInfo) {
        if (!info.serviceType.orEmpty().contains(CastDiscovery.SERVICE_TYPE)) return
        val name = info.serviceName ?: return
        val accepted = synchronized(lock) {
            when {
                name == ownName -> false
                name in known -> true
                known.size >= MAX_KNOWN -> false
                else -> {
                    known[name] = info
                    true
                }
            }
        }
        if (!accepted) return
        if (Build.VERSION.SDK_INT >= 34) watch(info) else resolve(name, info)
    }

    private fun serviceLost(name: String) {
        val callback = synchronized(lock) {
            known.remove(name)
            lastDelivery.remove(name)
            infoCallbacks.remove(name)
        }
        if (callback != null && Build.VERSION.SDK_INT >= 34) {
            runCatching { manager?.unregisterServiceInfoCallback(callback) }
        }
    }

    private fun refreshKnown() {
        if (Build.VERSION.SDK_INT >= 34) {
            val (notify, cached) = synchronized(lock) { found to lastDelivery.values.toList() }
            cached.forEach { (host, txt) -> notify?.invoke(host, txt) }
        } else {
            val services = synchronized(lock) { known.toMap() }
            services.forEach { (name, info) -> queue.offer(name, info) }
            pump()
        }
    }

    private fun deliver(name: String, host: String?, attributes: Map<String, ByteArray?>) {
        if (host == null) return
        val txt = attributes.mapNotNull { (k, v) -> v?.let { k to String(it, Charsets.UTF_8) } }.toMap()
        val notify = synchronized(lock) {
            if (name !in known) return
            lastDelivery[name] = host to txt
            found
        }
        notify?.invoke(host, txt)
    }

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        val nsd = manager ?: return
        val name = info.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val host = updated.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                deliver(name, host, updated.attributes)
            }

            override fun onServiceLost() = serviceLost(name)
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(lock) {
            if (name in infoCallbacks) return
            infoCallbacks[name] = callback
        }
        try {
            nsd.registerServiceInfoCallback(info, Executor { it.run() }, callback)
        } catch (_: RuntimeException) {
            synchronized(lock) { infoCallbacks.remove(name) }
        }
    }

    private fun resolve(name: String, info: NsdServiceInfo) {
        queue.offer(name, info)
        pump()
    }

    /** One resolve at a time below API 34; a busy answer retries shortly, a silent one times out. */
    @Suppress("DEPRECATION")
    private fun pump() {
        val nsd = manager ?: return
        val started = queue.start() ?: return
        val token = started.token
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (!queue.finish(token)) return
                deliver(started.key, (resolved.host as? Inet4Address)?.hostAddress, resolved.attributes)
                pump()
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    if (queue.retry(token)) main.postDelayed({ pump() }, RESOLVE_RETRY_MS)
                } else if (queue.finish(token)) {
                    pump()
                }
            }
        }
        main.postDelayed({ if (queue.finish(token)) pump() }, RESOLVE_TIMEOUT_MS)
        try {
            nsd.resolveService(started.item, listener)
        } catch (_: RuntimeException) {
            if (queue.finish(token)) main.post { pump() }
        }
    }

    private companion object {
        const val RESOLVE_RETRY_MS = 500L
        const val RESOLVE_TIMEOUT_MS = 4_000L
        const val REFRESH_MS = 6_000L
        const val MAX_KNOWN = 64
    }
}
