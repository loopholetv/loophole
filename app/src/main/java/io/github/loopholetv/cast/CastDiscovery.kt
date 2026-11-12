package io.github.loopholetv.cast

import android.content.Context
import io.github.loopholetv.cast.net.isLocalPeer
import io.github.loopholetv.cast.net.localIpv4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Service discovery on the local network (mDNS / DNS-SD) as the platform offers it. */
interface NsdPort {
    fun register(serviceName: String, port: Int, txt: Map<String, String>)
    fun unregister()

    /**
     * [onFound] gets a resolved host address and the service's TXT record, again whenever the
     * service is still there. [onLost] is best-effort and may name any id.
     */
    fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit)
    fun stopBrowse()
}

/**
 * Finds other TVs and makes this one findable, two ways at once: service discovery through [nsd]
 * and a small UDP beacon every 2 s. Both feed [table]; the beacon also gives real liveness, which
 * service discovery lacks, so a "service lost" from [nsd] is ignored and entries simply expire.
 *
 * All socket and network-binding work runs on [Dispatchers.IO], so any [scope] works.
 *
 * The beacon listener binds the wildcard address on [listenPort] (broadcasts can't be received any
 * other way) and drops anything that is oversized, not an IPv4 local-network address (loopback too,
 * unless [allowLoopback]), not an announce, or from this TV itself. Datagrams go to every address
 * [targets] returns, through a socket that [bindSender] has bound; that socket is reopened when
 * every send fails or the targets change.
 *
 * [holdMulticast] is called when discovery starts and the result closed when it stops. Use
 * [forDevice] on a device.
 */
class CastDiscovery(
    private val scope: CoroutineScope,
    private val nsd: NsdPort,
    private val table: PeerTable,
    private val beaconPort: Int = DEFAULT_BEACON_PORT,
    private val clock: () -> Long,
    private val listenPort: Int = beaconPort,
    private val targets: () -> List<InetSocketAddress> = { listOf(InetSocketAddress(BROADCAST, beaconPort)) },
    private val bindSender: (DatagramSocket) -> Unit = {},
    private val holdMulticast: () -> AutoCloseable = { AutoCloseable {} },
    private val allowLoopback: Boolean = false,
) {
    private val lock = Any()
    private var self: (() -> CastAnnounce)? = null
    private var selfId = ""
    private var generation = 0
    private var listener: DatagramSocket? = null
    private var sender: DatagramSocket? = null
    private var multicast: AutoCloseable? = null
    private var jobs = emptyList<Job>()
    private var registeredKey: List<Any?> = emptyList()
    private var registeredAt = 0L
    private var pendingRegister: Job? = null

    @Volatile
    private var lastTargets: List<InetSocketAddress>? = null

    /** The port the beacon listener is bound to, or 0 while discovery isn't running. */
    val listeningPort: Int get() = synchronized(lock) { listener?.localPort ?: 0 }

    fun start(self: () -> CastAnnounce) {
        val first = self()
        val gen: Int
        synchronized(lock) {
            if (this.self != null) return
            this.self = self
            selfId = first.id
            gen = ++generation
            lastTargets = null
            listener = openListener()
            registeredKey = registrationKey(first)
            registeredAt = clock()
            registerNsd(first)
            try {
                nsd.browse(::onNsdFound) { /* see class comment */ }
            } catch (_: RuntimeException) {
                // Discovery degrades to the beacon alone.
            }
            jobs = buildList {
                listener?.let { socket -> add(scope.launch(Dispatchers.IO) { receiveLoop(socket) }) }
                add(scope.launch(Dispatchers.IO) { beaconLoop(gen) })
                add(scope.launch { pruneLoop() })
            }
        }
    }

    fun stop() {
        val toClose: List<AutoCloseable?>
        synchronized(lock) {
            if (self == null) return
            self = null
            generation++
            pendingRegister?.cancel()
            pendingRegister = null
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            toClose = listOf(listener, sender, multicast)
            listener = null
            sender = null
            multicast = null
        }
        toClose.forEach { runCatching { it?.close() } }
        runCatching { nsd.stopBrowse() }
        runCatching { nsd.unregister() }
    }

    /** Announces now. Call on any change to what [start]'s lambda returns. */
    fun refresh() {
        val provider = synchronized(lock) { self } ?: return
        scope.launch(Dispatchers.IO) { sendBeacon() }
        val key = try {
            registrationKey(provider())
        } catch (_: RuntimeException) {
            return
        }
        synchronized(lock) {
            if (self == null) return
            if (key == registeredKey) {
                pendingRegister?.cancel()
                pendingRegister = null
            } else if (pendingRegister?.isActive != true) {
                val wait = registerDelay(clock() - registeredAt)
                pendingRegister = scope.launch {
                    delay(wait)
                    reregister()
                }
            }
        }
    }

    private fun reregister() {
        val provider = synchronized(lock) { self } ?: return
        val now = try {
            provider()
        } catch (_: RuntimeException) {
            return
        }
        synchronized(lock) {
            if (self == null) return
            pendingRegister = null
            val key = registrationKey(now)
            if (key == registeredKey) return
            registeredKey = key
            registeredAt = clock()
            runCatching { nsd.unregister() }
            registerNsd(now)
        }
    }

    private fun registerNsd(a: CastAnnounce) {
        try {
            nsd.register(serviceName(a.id), a.port, a.toTxt())
        } catch (_: RuntimeException) {
            // Discovery degrades to the beacon alone.
        }
    }

    private fun onNsdFound(host: String, txt: Map<String, String>) {
        // The server listens on IPv4 only, and only a TV on the home network is of any use.
        val address = localIpv4(host) ?: return
        if (address.isLoopbackAddress) return
        val a = announceFromTxt(txt) ?: return
        table.seen(a, host)
    }

    private fun openListener(): DatagramSocket? = try {
        DatagramSocket(listenPort)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The sender socket, opened (and bound to the LAN) on first use. Null when stopped. */
    private fun ensureSender(): DatagramSocket? = synchronized(lock) {
        if (self == null) return null
        sender ?: openSender()?.also { sender = it }
    }

    private fun openSender(): DatagramSocket? {
        val socket = try {
            DatagramSocket()
        } catch (_: IOException) {
            return null
        }
        return try {
            socket.broadcast = true
            bindSender(socket)
            socket
        } catch (_: IOException) {
            socket.close()
            null
        } catch (_: RuntimeException) {
            socket.close()
            null
        }
    }

    private fun resetSender(socket: DatagramSocket) {
        synchronized(lock) { if (sender === socket) sender = null }
        runCatching { socket.close() }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!socket.isClosed) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
            } catch (_: IOException) {
                return
            }
            handle(packet.address, buffer, packet.length)
        }
    }

    internal fun handle(source: InetAddress?, buffer: ByteArray, length: Int) {
        if (source !is Inet4Address || length > MAX_DATAGRAM || !isLocalPeer(source)) return
        if (!allowLoopback && (source.isLoopbackAddress || source.isAnyLocalAddress)) return
        val a = CastJson.announce(String(buffer, 0, length, Charsets.UTF_8)) ?: return
        if (a.id == selfId) return
        table.seen(a, source.hostAddress ?: return)
    }

    private suspend fun beaconLoop(gen: Int) {
        val hold = try {
            holdMulticast()
        } catch (_: RuntimeException) {
            null
        }
        val kept = synchronized(lock) {
            if (generation == gen) multicast = hold
            generation == gen
        }
        if (!kept) runCatching { hold?.close() }
        while (scope.isActive) {
            sendBeacon()
            delay(BEACON_INTERVAL_MS)
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            delay(BEACON_INTERVAL_MS)
            table.prune()
        }
    }

    private fun sendBeacon() {
        try {
            val provider = synchronized(lock) { self } ?: return
            val bytes = CastJson.announce(provider()).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_DATAGRAM) return
            val list = targets()
            val socket = ensureSender() ?: return
            var delivered = false
            for (target in list) {
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, target))
                    delivered = true
                } catch (_: IOException) {
                    // One unreachable target doesn't stop the others.
                }
            }
            val previous = lastTargets
            lastTargets = list
            if (shouldRebind(list.isNotEmpty() && !delivered, previous, list)) resetSender(socket)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // One bad tick must not end discovery.
        }
    }

    companion object {
        const val DEFAULT_BEACON_PORT = 41_550
        const val SERVICE_TYPE = "_loophole-cast._tcp"
        private const val BROADCAST = "255.255.255.255"
        private const val MAX_DATAGRAM = 2048
        private const val BEACON_INTERVAL_MS = 2_000L
        private const val REGISTER_DEBOUNCE_MS = 1_000L
        private const val MIN_REGISTER_GAP_MS = 5_000L

        /**
         * Discovery wired for a device: NSD, the multicast lock, subnet broadcast and LAN binding.
         * [beaconPort] 0 listens on a free port (tests), and beacons then go nowhere.
         */
        fun forDevice(
            context: Context,
            scope: CoroutineScope,
            table: PeerTable,
            clock: () -> Long,
            nsd: NsdPort = AndroidNsd(context),
            beaconPort: Int = DEFAULT_BEACON_PORT,
        ) = CastDiscovery(
            scope, nsd, table, beaconPort = beaconPort, clock = clock,
            targets = if (beaconPort == 0) ({ emptyList() }) else lanBroadcastTargets(context, beaconPort),
            bindSender = lanSenderBinding(context),
            holdMulticast = multicastHold(context),
        )

        /** Never the TV's name: that is user text and can collide. */
        internal fun serviceName(id: String) = "Loophole-" + id.take(8)

        /** The fields that make a new service registration worthwhile when they change. */
        private fun registrationKey(a: CastAnnounce) = listOf(a.name, a.status, a.castId, a.port)

        /** How long to wait before re-registering: at least 1 s, and 5 s after the last one. */
        internal fun registerDelay(sinceLastMs: Long) =
            maxOf(REGISTER_DEBOUNCE_MS, MIN_REGISTER_GAP_MS - sinceLastMs)

        /** The sender socket is rebound when nothing got through or the network's targets moved. */
        internal fun shouldRebind(
            allFailed: Boolean,
            previous: List<InetSocketAddress>?,
            now: List<InetSocketAddress>,
        ) = allFailed || (previous != null && previous != now)

        internal fun CastAnnounce.toTxt(): Map<String, String> = buildMap {
            put("v", v.toString())
            put("id", id)
            put("name", name)
            put("port", port.toString())
            put("status", status.name)
            put("accepts", accepts.toString())
            put("seq", seq.toString())
            castId?.let { put("castId", it) }
            token?.let { put("token", it) }
            castStartedAtMs?.let { put("startedAt", it.toString()) }
        }

        /** Null unless the record is a complete, current-version announce. */
        internal fun announceFromTxt(txt: Map<String, String>): CastAnnounce? {
            if (txt["v"]?.toIntOrNull() != 1) return null
            val a = CastAnnounce(
                id = txt["id"]?.takeIf { it.isNotEmpty() } ?: return null,
                name = txt["name"] ?: return null,
                port = txt["port"]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null,
                status = CastStatus.entries.firstOrNull { it.name == txt["status"] } ?: return null,
                accepts = txt["accepts"]?.toBooleanStrictOrNull() ?: return null,
                castId = txt["castId"],
                token = txt["token"],
                castStartedAtMs = txt["startedAt"]?.toLongOrNull(),
                seq = txt["seq"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null,
            )
            val castComplete = a.castId != null && a.token != null && a.castStartedAtMs != null
            return if (a.status == CastStatus.CASTING && !castComplete) null else a
        }
    }
}
