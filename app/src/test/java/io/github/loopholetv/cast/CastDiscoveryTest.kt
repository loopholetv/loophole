package io.github.loopholetv.cast

import io.github.loopholetv.cast.CastDiscovery.Companion.toTxt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CastDiscoveryTest {
    private class FakeNsd : NsdPort {
        var registered = emptyList<Triple<String, Int, Map<String, String>>>()
        var unregisterCalls = 0
        var browseCalls = 0
        var browsing = false
        var stopBrowseCalls = 0
        private var found: ((String, Map<String, String>) -> Unit)? = null
        private var lost: ((String) -> Unit)? = null

        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {
            registered = registered + Triple(serviceName, port, txt)
        }

        override fun unregister() {
            unregisterCalls++
        }

        override fun browse(onFound: (String, Map<String, String>) -> Unit, onLost: (String) -> Unit) {
            browsing = true
            browseCalls++
            found = onFound
            lost = onLost
        }

        override fun stopBrowse() {
            browsing = false
            stopBrowseCalls++
        }

        fun found(host: String, txt: Map<String, String>) = found!!(host, txt)
        fun lost(id: String) = lost!!(id)
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e })
    private val sockets = mutableListOf<DatagramSocket>()
    private val running = mutableListOf<CastDiscovery>()

    @After
    fun tearDown() {
        running.forEach { it.stop() }
        sockets.forEach { it.close() }
        scope.cancel()
        assertEquals("coroutines failed", emptyList<Throwable>(), uncaught.toList())
    }

    private fun ann(id: String, seq: Long = 1, status: CastStatus = CastStatus.IDLE) = CastAnnounce(
        id, "TV $id", 8000, status, true,
        castId = if (status == CastStatus.CASTING) "c1" else null,
        token = if (status == CastStatus.CASTING) "tok" else null,
        castStartedAtMs = if (status == CastStatus.CASTING) 1_700_000_000_000 else null,
        seq = seq,
    )

    private class Node(val discovery: CastDiscovery, val table: PeerTable)

    private fun discovery(
        id: String,
        table: PeerTable,
        nsd: NsdPort = FakeNsd(),
        allowLoopback: Boolean = true,
        bindSender: (DatagramSocket) -> Unit = {},
        holdMulticast: () -> AutoCloseable = { AutoCloseable {} },
        targets: () -> List<InetSocketAddress>,
    ) = CastDiscovery(
        scope, nsd, table, clock = System::currentTimeMillis, listenPort = 0, targets = targets,
        bindSender = bindSender, holdMulticast = holdMulticast, allowLoopback = allowLoopback,
    ).also { running += it }

    private fun node(
        id: String,
        peerPort: () -> Int,
        nsd: NsdPort = FakeNsd(),
        self: () -> CastAnnounce = { ann(id) },
    ): Node {
        val table = PeerTable(id, System::currentTimeMillis)
        val d = discovery(id, table, nsd) { listOf(InetSocketAddress(loopback, peerPort())) }
        d.start(self)
        return Node(d, table)
    }

    private fun sink(): DatagramSocket = DatagramSocket(0, loopback).also {
        it.soTimeout = 5_000
        sockets += it
    }

    private fun sendTo(port: Int, text: String) {
        val bytes = text.toByteArray()
        val s = DatagramSocket().also { sockets += it }
        s.send(DatagramPacket(bytes, bytes.size, loopback, port))
    }

    private fun awaitPeers(node: Node, ids: List<String>) = runBlocking {
        try {
            withTimeout(5_000) { node.table.peers.first { p -> p.map { it.id } == ids } }
        } catch (_: TimeoutCancellationException) {
            fail("expected peers $ids, table has ${node.table.peers.value}")
            emptyList()
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        } catch (_: TimeoutCancellationException) {
            fail("timed out waiting for $what")
        }
    }

    private fun bytes(a: CastAnnounce) = CastJson.announce(a).toByteArray()

    private fun productionDiscovery(table: PeerTable) =
        discovery("me", table, allowLoopback = false) { emptyList() }.also { it.start { ann("me") } }

    @Test
    fun beaconSeenByListener() {
        var a: Node? = null
        var b: Node? = null
        a = node("a", { b?.discovery?.listeningPort ?: 9 })
        b = node("b", { a?.discovery?.listeningPort ?: 9 })
        a.discovery.refresh()
        b.discovery.refresh()
        assertEquals("127.0.0.1", awaitPeers(a, listOf("b")).single().host)
        assertEquals(listOf("a"), awaitPeers(b, listOf("a")).map { it.id })
    }

    @Test
    fun ownBeaconIgnored() {
        var me: Node? = null
        me = node("me", { me?.discovery?.listeningPort ?: 9 })
        me.discovery.refresh()
        // Datagrams to one socket arrive in order, so once the later one is in, the own one was handled.
        sendTo(me.discovery.listeningPort, CastJson.announce(ann("me")))
        sendTo(me.discovery.listeningPort, CastJson.announce(ann("other")))
        assertEquals(listOf("other"), awaitPeers(me, listOf("other")).map { it.id })
    }

    @Test
    fun garbageDatagramIgnored() {
        val me = node("me", { 9 })
        val port = me.discovery.listeningPort
        sendTo(port, "not json at all")
        sendTo(port, "{\"v\":2}")
        sendTo(port, "x".repeat(4_000))
        sendTo(port, CastJson.announce(ann("big")).replace("TV big", "y".repeat(3_000)))
        sendTo(port, CastJson.announce(ann("good")))
        assertEquals(listOf("good"), awaitPeers(me, listOf("good")).map { it.id })
    }

    @Test
    fun refreshSendsImmediately() {
        val target = sink()
        var seq = 1L
        node("me", { target.localPort }, self = { ann("me", seq) })
        val buffer = ByteArray(2048)
        fun receive(): CastAnnounce {
            val p = DatagramPacket(buffer, buffer.size)
            target.receive(p)
            return CastJson.announce(String(buffer, 0, p.length))!!
        }
        assertEquals(1, receive().seq)
        seq = 2
        target.soTimeout = 1_000 // shorter than the 2 s beacon interval
        running.single().refresh()
        assertEquals(2, receive().seq)
    }

    @Test
    fun socketWorkRunsOffTheCallersThread() {
        val caller = Thread.currentThread()
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val target = sink()
        val table = PeerTable("me", System::currentTimeMillis)
        val d = discovery(
            "me", table,
            bindSender = { threads += Thread.currentThread() },
            holdMulticast = {
                threads += Thread.currentThread()
                AutoCloseable {}
            },
        ) {
            threads += Thread.currentThread()
            listOf(InetSocketAddress(loopback, target.localPort))
        }
        d.start { ann("me") }
        target.receive(DatagramPacket(ByteArray(2048), 2048))
        assertTrue(threads.isNotEmpty())
        assertFalse(caller in threads)
    }

    @Test
    fun aBadTickDoesNotEndTheBeacon() {
        val target = sink()
        val calls = AtomicInteger()
        val table = PeerTable("me", System::currentTimeMillis)
        val d = discovery("me", table) {
            check(calls.incrementAndGet() > 1) { "first tick fails" }
            listOf(InetSocketAddress(loopback, target.localPort))
        }
        d.start { ann("me") }
        awaitTrue("first tick") { calls.get() >= 1 }
        d.refresh()
        target.soTimeout = 1_000 // the next scheduled tick is 2 s away
        target.receive(DatagramPacket(ByteArray(2048), 2048))
    }

    @Test
    fun senderIsReboundWhenTargetsChange() {
        val target = sink()
        val binds = AtomicInteger()
        val calls = AtomicInteger()
        val table = PeerTable("me", System::currentTimeMillis)
        val d = discovery("me", table, bindSender = { binds.incrementAndGet() }) {
            val port = if (calls.incrementAndGet() == 1) target.localPort else target.localPort + 1
            listOf(InetSocketAddress(loopback, port))
        }
        d.start { ann("me") }
        awaitTrue("first bind") { binds.get() == 1 }
        repeat(3) { d.refresh() }
        awaitTrue("a second bind") { binds.get() >= 2 }
    }

    @Test
    fun rebindDecision() {
        val a = listOf(InetSocketAddress(loopback, 1))
        val b = listOf(InetSocketAddress(loopback, 2))
        assertFalse(CastDiscovery.shouldRebind(false, null, a))
        assertFalse(CastDiscovery.shouldRebind(false, a, a))
        assertTrue(CastDiscovery.shouldRebind(false, a, b))
        assertTrue(CastDiscovery.shouldRebind(true, a, a))
    }

    @Test
    fun loopbackAndOutsideBeaconsDroppedInProduction() {
        val table = PeerTable("me", System::currentTimeMillis)
        val d = productionDiscovery(table)
        val data = bytes(ann("x"))
        d.handle(loopback, data, data.size)
        d.handle(InetAddress.getByName("8.8.8.8"), data, data.size)
        d.handle(InetAddress.getByName("::1"), data, data.size)
        d.handle(InetAddress.getByName("fe80::1"), data, data.size)
        assertTrue(table.peers.value.isEmpty())
        d.handle(InetAddress.getByName("192.168.1.5"), data, data.size)
        assertEquals(listOf("x"), table.peers.value.map { it.id })
    }

    @Test
    fun nsdFoundFeedsTable() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        nsd.found("192.168.1.20", ann("n1", status = CastStatus.CASTING).toTxt())
        nsd.found("192.168.1.21", ann("n2").toTxt() - "seq") // incomplete record
        nsd.found("192.168.1.22", ann("n3").toTxt() + ("v" to "2"))
        assertEquals(listOf("n1"), me.table.peers.value.map { it.id })
        assertEquals(CastStatus.CASTING, me.table.peers.value.single().status)
        assertEquals("tok", me.table.peers.value.single().token)
    }

    @Test
    fun nsdRecordCarriesTheSetupVersion() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        val txt = ann("n1").copy(setup = 1).toTxt()
        assertEquals("1", txt["setup"])
        assertEquals("1", txt["v"])
        nsd.found("192.168.1.20", txt)
        nsd.found("192.168.1.21", ann("n2").toTxt() - "setup") // an older TV
        assertEquals(mapOf("n1" to 1, "n2" to 0), me.table.peers.value.associate { it.id to it.setup })
    }

    @Test
    fun nsdLossDoesNotRemoveAPeer() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        nsd.found("192.168.1.20", ann("n1").toTxt())
        nsd.lost("n1")
        assertEquals(listOf("n1"), me.table.peers.value.map { it.id })
    }

    @Test
    fun ipv6OnlyNsdSightingNotFedToTable() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        nsd.found("fd00::7", ann("v6").toTxt())
        nsd.found("fe80::1", ann("ll").toTxt())
        nsd.found("::ffff:192.168.1.9", ann("mapped").toTxt())
        assertTrue(me.table.peers.value.isEmpty())
        nsd.found("192.168.1.9", ann("v4").toTxt())
        assertEquals(listOf("v4"), me.table.peers.value.map { it.id })
    }

    @Test
    fun nsdSightingsOutsideTheHomeNetworkRejected() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        for (host in listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "203.0.113.9", "999.1.1.1", "example.com")) {
            nsd.found(host, ann("bad").toTxt())
        }
        assertTrue(me.table.peers.value.isEmpty())
    }

    @Test
    fun txtRoundTripsAnnounce() {
        val a = ann("abc", seq = 7, status = CastStatus.CASTING)
        assertEquals(a, CastDiscovery.announceFromTxt(a.toTxt()))
        assertNull(CastDiscovery.announceFromTxt(a.toTxt() - "token"))
    }

    @Test
    fun registersUnderIdDerivedName() {
        val nsd = FakeNsd()
        node("0123456789abcdef", { 9 }, nsd)
        val (name, port, txt) = nsd.registered.single()
        assertEquals("Loophole-01234567", name)
        assertEquals(8000, port)
        assertEquals("TV 0123456789abcdef", txt["name"])
        assertTrue(nsd.browsing)
    }

    @Test
    fun stopClosesSocketsAndUnregisters() {
        val nsd = FakeNsd()
        val me = node("me", { 9 }, nsd)
        val port = me.discovery.listeningPort
        assertTrue(port > 0)
        me.discovery.stop()
        assertEquals(0, me.discovery.listeningPort)
        assertFalse(nsd.browsing)
        assertEquals(1, nsd.stopBrowseCalls)
        assertEquals(1, nsd.unregisterCalls)
        // The listener's port is free again.
        DatagramSocket(port).close()
        me.discovery.stop() // a second stop is harmless
        assertEquals(1, nsd.unregisterCalls)
    }

    @Test
    fun startStopStartWorksAgain() {
        val nsd = FakeNsd()
        val open = AtomicInteger()
        val table = PeerTable("me", System::currentTimeMillis)
        val d = discovery(
            "me", table, nsd,
            holdMulticast = {
                open.incrementAndGet()
                AutoCloseable { open.decrementAndGet() }
            },
        ) { emptyList() }
        d.start { ann("me") }
        awaitTrue("lock") { open.get() == 1 }
        d.stop()
        awaitTrue("lock released") { open.get() == 0 }
        d.start { ann("me") }
        awaitTrue("lock again") { open.get() == 1 }
        assertTrue(d.listeningPort > 0)
        assertEquals(2, nsd.registered.size)
        assertEquals(2, nsd.browseCalls)
        assertTrue(nsd.browsing)
        d.stop()
        awaitTrue("lock released again") { open.get() == 0 }
    }

    @Test
    fun registrationDelayKeepsAGapOfFiveSeconds() {
        assertEquals(5_000, CastDiscovery.registerDelay(0))
        assertEquals(2_000, CastDiscovery.registerDelay(3_000))
        assertEquals(1_000, CastDiscovery.registerDelay(4_500))
        assertEquals(1_000, CastDiscovery.registerDelay(60_000))
    }

    @Test
    fun nsdReregistersOnlyOnIdentityChangeAndDebounced() = runTest {
        val nsd = FakeNsd()
        var current = ann("me")
        val d = CastDiscovery(
            backgroundScope, nsd, PeerTable("me", { 0L }), clock = { currentTime },
            listenPort = 0, targets = { emptyList() },
        )
        d.start { current }
        assertEquals(1, nsd.registered.size)

        current = current.copy(seq = 2) // heartbeat-style change: not worth a new registration
        d.refresh()
        advanceTimeBy(10_000)
        assertEquals(1, nsd.registered.size)

        current = ann("me", seq = 3, status = CastStatus.WATCHING)
        d.refresh()
        advanceTimeBy(900)
        assertEquals(1, nsd.registered.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, nsd.registered.size)
        assertEquals(1, nsd.unregisterCalls)
        assertEquals("WATCHING", nsd.registered.last().third["status"])
        d.stop()
    }

    @Test
    fun nsdReregistrationWaitsForTheGapAfterTheLastOne() = runTest {
        val nsd = FakeNsd()
        var current = ann("me")
        val d = CastDiscovery(
            backgroundScope, nsd, PeerTable("me", { 0L }), clock = { currentTime },
            listenPort = 0, targets = { emptyList() },
        )
        d.start { current }
        advanceTimeBy(1_000)
        current = ann("me", seq = 2, status = CastStatus.CASTING)
        d.refresh()
        advanceTimeBy(3_900) // 4.9 s after the last registration
        assertEquals(1, nsd.registered.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, nsd.registered.size)
        d.stop()
    }

    @Test
    fun subnetBroadcastAddresses() {
        fun b(ip: String, prefix: Int) = subnetBroadcast(InetAddress.getByName(ip) as Inet4Address, prefix)?.hostAddress
        assertEquals("192.168.1.255", b("192.168.1.20", 24))
        assertEquals("10.1.255.255", b("10.1.2.3", 16))
        assertEquals("192.168.3.255", b("192.168.2.77", 23))
        assertEquals("172.16.0.255", b("172.16.0.200", 24))
        assertEquals("192.168.1.131", b("192.168.1.130", 30))
        assertEquals("10.255.255.255", b("10.0.0.5", 8))
        assertNull(b("192.168.1.1", 31))
        assertNull(b("192.168.1.1", 32))
        assertNull(b("192.168.1.1", 0))
    }
}
