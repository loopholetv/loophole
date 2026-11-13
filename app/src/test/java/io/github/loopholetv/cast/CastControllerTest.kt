package io.github.loopholetv.cast

import androidx.media3.common.Player
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.catchup.PlayMode
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.loopholetv.ui.nav.holdAutoJoin
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.ui.nav.Routes

@OptIn(ExperimentalCoroutinesApi::class)
class CastControllerTest {
    /** Follows the [Playback] contract for the parts the controller uses. */
    private class FakePlayback(private val host: CastHost) : Playback {
        override val player: Player get() = error("not used")
        val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = stateFlow
        val modeFlow = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = modeFlow

        /** For each reopen: whether the relay was active at that moment. */
        val reopens = mutableListOf<Boolean>()
        val casts = mutableListOf<Pair<String, Boolean>>()
        var stops = 0
        val pausedFlow = MutableStateFlow(false)
        override val paused: StateFlow<Boolean> = pausedFlow
        var pauses = 0
        var resumes = 0

        override fun pause() {
            pauses++
            pausedFlow.value = true
        }

        override fun resume() {
            resumes++
            pausedFlow.value = false
        }

        override fun play(channel: io.github.loopholetv.data.db.ChannelEntity) = localPlay(channel.id)

        fun localPlay(id: Long) {
            modeFlow.value = PlayMode.Live
            stateFlow.value = PlaybackState.Buffering(id)
        }

        fun watching(id: Long = 7) {
            modeFlow.value = PlayMode.Live
            stateFlow.value = PlaybackState.Playing(id, StreamTags.NONE)
        }

        override fun stop() {
            stops++
            modeFlow.value = PlayMode.Live
            stateFlow.value = PlaybackState.Idle
        }

        override fun reopen() {
            reopens += host.active.value != null
        }

        override fun playCast(url: String, hls: Boolean) {
            casts += url to hls
            stateFlow.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
            modeFlow.value = PlayMode.Cast
        }
    }

    /** One event feed per join; an item is a [CastEvent] to emit or a [Throwable] to throw. */
    private class FakeClient : CastClient(OkHttpClient()) {
        val joins = mutableListOf<String>()
        val leaves = mutableListOf<String>()
        private val feeds = mutableListOf<Channel<Any>>()
        var failJoin: Throwable? = null

        override fun events(peer: CastPeer, selfId: String): Flow<CastEvent> = flow {
            joins += peer.id
            failJoin?.let { throw it }
            val feed = Channel<Any>(Channel.UNLIMITED)
            feeds += feed
            for (item in feed) {
                if (item is Throwable) throw item
                val event = item as CastEvent
                emit(event)
                if (event is CastEvent.Stopped) return@flow
            }
            throw IOException("ended")
        }

        override suspend fun leave(peer: CastPeer, selfId: String) {
            leaves += peer.id
        }

        fun send(item: Any) {
            feeds.last().trySend(item)
        }
    }

    private class FakeNsd : NsdPort {
        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {}
        override fun unregister() {}
        override fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit) {}
        override fun stopBrowse() {}
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val loopback6 = InetAddress.getByName("::1")
    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LocalHttpServer(realScope)
    private val host = CastHost(realScope, server, OkHttpClient(), clock = { 0L }, heartbeatMs = 3_600_000)
    /** Each announce is a fresh sighting, as on a real network. */
    private var now = 0L
    private val table = PeerTable("self", clock = { now })
    private val discovery = CastDiscovery(realScope, FakeNsd(), table, clock = { 0L }, listenPort = 0, targets = { emptyList() })
    private val playback = FakePlayback(host)
    private val client = FakeClient()
    private val settings = MutableStateFlow(AppSettings())
    private var autoJoin = true

    /** What the controller asks before joining on its own; [autoJoin] unless a test wires a hold. */
    private var canAutoJoin: () -> Boolean = { autoJoin }
    private var homeNetwork = true
    private var position: Long? = null
    private val seeks = mutableListOf<Long>()
    private val messages = mutableListOf<String>()
    private var announceSeq = 1L
    private var addresses = listOf(loopback)
    /** Wi-Fi locks taken and released. */
    private var wifiTaken = 0
    private var wifiReleased = 0
    /** The registered network watch's callback while one is registered. */
    private var networkChanged: (() -> Unit)? = null
    private var networkWatches = 0

    @After fun tearDown() {
        discovery.stop()
        server.stop()
        realScope.cancel()
    }

    private fun TestScope.controller(selfId: suspend () -> String = { "self" }, foreground: Boolean = true): CastController {
        val c = CastController(
            backgroundScope, selfId, playback, host, discovery, table, client, server, { addresses },
            settings, { "This TV" }, clock = { 5_000L }, canAutoJoin = { canAutoJoin() },
            castPosition = { position }, seekCast = { seeks += it },
            // The tests' server is on loopback only; whether other TVs could reach it is up to the test.
            reachable = { homeNetwork },
            holdWifi = {
                wifiTaken++
                AutoCloseable { wifiReleased++ }
            },
            watchNetwork = { onChange ->
                networkWatches++
                networkChanged = onChange
                AutoCloseable { if (networkChanged === onChange) networkChanged = null }
            },
        )
        backgroundScope.launch { c.messages.collect { messages += it } }
        if (foreground) foreground(c)
        return c
    }

    /** Comes to the foreground and lets the first sightings settle. */
    private fun TestScope.foreground(c: CastController) {
        c.onForeground(true)
        runCurrent()
        advanceTimeBy(CastController.SETTLE_MS + 1)
        runCurrent()
    }

    private fun announce(
        id: String = "tv-a",
        name: String = "Living room",
        status: CastStatus = CastStatus.CASTING,
        castId: String? = "cast1",
        startedAt: Long = 1_000L,
        host: String = "192.168.1.20",
    ) {
        now++
        val a = CastAnnounce(
            id, name, 41_000, status, true, castId, castId?.let { "tok$it" },
            startedAt.takeIf { status == CastStatus.CASTING }, announceSeq++,
        )
        table.seen(a, host)
    }

    private fun playing(epoch: Int, hls: Boolean = false, castId: String = "cast1") = CastEvent.Playing(
        castId, epoch, if (hls) "/c/tok$castId/$epoch/index.m3u8" else "/c/tok$castId/$epoch.ts", hls,
    )

    /** Runs the test dispatcher until [done], letting the relay's IO threads finish meanwhile. */
    private fun TestScope.eventually(advanceMs: Long = 0, done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            if (advanceMs > 0) advanceTimeBy(advanceMs)
            runCurrent()
            if (done()) return
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    // Casting

    @Test fun startCastingRestartsThroughRelay() = runTest {
        val c = controller()
        c.startCasting()
        runCurrent()

        assertNotNull(host.active.value)
        assertEquals(listOf(true), playback.reopens)
        assertEquals(CastState.Casting(0), c.state.value)
        assertEquals(CastStatus.CASTING, c.selfAnnounce().status)
    }

    @Test fun stopCastingRestartsDirect() = runTest {
        val c = controller()
        c.startCasting()
        runCurrent()
        c.stopCasting()

        assertEquals(CastState.Off, c.state.value)
        assertNull(host.active.value)
        eventually { playback.reopens.size == 2 }
        assertEquals(listOf(true, false), playback.reopens)
    }

    @Test fun leftAppAndExternalDoNotReopen() = runTest {
        val c = controller()
        c.startCasting()
        runCurrent()
        c.stopCasting(StopReason.EXTERNAL_PLAYER)
        assertNull(host.active.value)

        c.startCasting()
        runCurrent()
        c.onForeground(false)
        assertNull(host.active.value)
        eventually(advanceMs = 50) { server.port == null }

        assertEquals(listOf(true, true), playback.reopens)
    }

    @Test fun selfStatusFollowsPlayback() = runTest {
        val c = controller()
        val idle = c.selfAnnounce()
        assertEquals(CastStatus.IDLE, idle.status)
        assertEquals("self", idle.id)
        assertEquals("This TV", idle.name)
        assertEquals(server.port, idle.port)
        assertTrue(idle.accepts)
        assertNull(idle.castId)
        assertTrue("seeded from the wall clock", idle.seq > 5_000L)
        assertEquals("unchanged keeps its number", idle.seq, c.selfAnnounce().seq)
        assertEquals(CastAnnounce.SETUP_VERSION, idle.setup)

        playback.watching()
        val watching = c.selfAnnounce()
        assertEquals(CastStatus.WATCHING, watching.status)
        assertTrue(watching.seq > idle.seq)
        playback.stateFlow.value = PlaybackState.Buffering(3)
        assertEquals(CastStatus.WATCHING, c.selfAnnounce().status)

        c.startCasting()
        runCurrent()
        val casting = c.selfAnnounce()
        val session = host.active.value!!
        assertEquals(CastStatus.CASTING, casting.status)
        assertEquals(session.castId, casting.castId)
        assertEquals(session.token, casting.token)
        assertEquals(session.startedAtMs, casting.castStartedAtMs)
        assertTrue(casting.seq > watching.seq)

        c.stopCasting()
        assertEquals(CastStatus.WATCHING, c.selfAnnounce().status)
        playback.stop()
        assertEquals(CastStatus.IDLE, c.selfAnnounce().status)

        settings.value = AppSettings(castAllowJoin = false)
        runCurrent()
        assertEquals(false, c.selfAnnounce().accepts)
    }

    @Test fun staleStopDoesNotReopenAfterARestart() = runTest {
        val c = controller()
        playback.watching()
        c.startCasting()
        runCurrent()
        c.stopCasting()
        c.startCasting()
        repeat(20) {
            Thread.sleep(10)
            runCurrent()
        }
        assertEquals("one reopen per start, none from the old stop", listOf(true, true), playback.reopens)
        assertTrue(c.state.value is CastState.Casting)
    }

    @Test fun staleStopDoesNotReopenAfterAChannelChange() = runTest {
        val gate = CompletableDeferred<Unit>()
        host.onStopping = { gate.await() }
        val c = controller()
        playback.watching(7)
        c.startCasting()
        runCurrent()
        c.stopCasting()
        // The stop is still suspended when the channel changes.
        playback.localPlay(9)
        gate.complete(Unit)
        repeat(20) {
            Thread.sleep(10)
            runCurrent()
        }
        assertEquals("the new channel is already opening directly", listOf(true), playback.reopens)
    }

    @Test fun noHomeNetworkMeansNoCast() = runTest {
        homeNetwork = false
        val c = controller()
        playback.watching()
        c.startCasting()
        runCurrent()

        assertEquals(CastState.Off, c.state.value)
        assertNull(host.active.value)
        assertEquals(emptyList<Boolean>(), playback.reopens)
        assertEquals(listOf("Connect this TV to your home network to cast."), messages)
        assertEquals(0, wifiTaken)
    }

    @Test fun wifiLockIsHeldOnlyWhileCasting() = runTest {
        val c = controller()
        assertEquals(0, wifiTaken)

        c.startCasting()
        runCurrent()
        c.startCasting()
        assertEquals(1, wifiTaken)
        assertEquals(0, wifiReleased)

        c.stopCasting()
        runCurrent()
        assertEquals(1, wifiReleased)

        c.startCasting()
        runCurrent()
        c.onForeground(false)
        assertEquals(2, wifiTaken)
        assertEquals(2, wifiReleased)
    }

    @Test fun networkIsWatchedOnlyInTheForeground() = runTest {
        val c = controller()
        assertNotNull(networkChanged)

        c.onForeground(false)
        assertNull(networkChanged)

        foreground(c)
        assertNotNull(networkChanged)
        assertEquals(2, networkWatches)
    }

    @Test fun joiningTheHomeNetworkRebindsTheServer() = runTest {
        homeNetwork = false
        val c = controller()
        val port = server.port!!
        c.startCasting()
        runCurrent()
        assertEquals(listOf(CastController.NO_NETWORK_MESSAGE), messages)

        homeNetwork = true
        addresses = listOf(loopback6, loopback)
        networkChanged!!.invoke()
        runCurrent()

        assertEquals("the same port while it is free", port, server.port)
        java.net.Socket(loopback6, port).close()
        c.startCasting()
        runCurrent()
        assertTrue(c.state.value is CastState.Casting)
    }

    @Test fun addressChangeWhileCastingReopensThroughTheNewServer() = runTest {
        val c = controller()
        c.startCasting()
        runCurrent()
        assertEquals(listOf(true), playback.reopens)

        networkChanged!!.invoke()
        runCurrent()
        assertEquals("nothing changed: nothing restarts", listOf(true), playback.reopens)

        addresses = listOf(loopback, loopback6)
        networkChanged!!.invoke()
        runCurrent()
        assertEquals(listOf(true, true), playback.reopens)
        assertTrue(c.state.value is CastState.Casting)
        assertNotNull(server.port)
    }

    @Test fun onlyLoopbackIsNotAHomeNetwork() = runTest {
        val c = CastController(
            backgroundScope, { "self" }, playback, host, discovery, table, client, server, { listOf(loopback) },
            settings, { "This TV" }, clock = { 5_000L },
        )
        backgroundScope.launch { c.messages.collect { messages += it } }
        foreground(c)
        c.startCasting()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf(CastController.NO_NETWORK_MESSAGE), messages)
    }

    @Test fun nothingStartsUntilTheIdIsLoaded() = runTest {
        val id = kotlinx.coroutines.CompletableDeferred<String>()
        val c = controller(selfId = { id.await() })
        assertNull(server.port)
        assertEquals(0, discovery.listeningPort)
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)

        id.complete("self")
        runCurrent()
        assertNotNull(server.port)
        assertTrue(discovery.listeningPort > 0)
        assertEquals("self", c.selfAnnounce().id)
        advanceTimeBy(CastController.SETTLE_MS + 1)
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun announceNameFollowsSettings() = runTest {
        val c = controller()
        assertEquals("This TV", c.selfAnnounce().name)
        settings.value = AppSettings(castTvName = "Kitchen")
        runCurrent()
        assertEquals("Kitchen", c.selfAnnounce().name)
    }

    // Joining on its own

    @Test fun idleTvAutoJoinsNewCast() = runTest {
        val c = controller()
        announce()
        runCurrent()

        val state = c.state.value as CastState.Following
        assertEquals("tv-a", state.peer.id)
        assertEquals(listOf("tv-a"), client.joins)
        assertEquals(1, playback.stops)
        assertNull(c.invite.value)
    }

    @Test fun watchingTvGetsInviteNotJoin() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()

        assertEquals(CastState.Off, c.state.value)
        assertEquals("tv-a", c.invite.value?.peer?.id)
        assertEquals(emptyList<String>(), client.joins)

        c.join("tv-a")
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
        assertNull(c.invite.value)
    }

    @Test fun inviteTimesOutAfter10sAndIsDeclined() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()

        advanceTimeBy(9_999)
        assertNotNull(c.invite.value)
        advanceTimeBy(2)
        assertNull(c.invite.value)

        playback.stop()
        c.onForeground(false)
        foreground(c)
        announce()
        runCurrent()
        assertNull(c.invite.value)
        assertEquals(CastState.Off, c.state.value)
    }

    @Test fun ignoredCastNotReoffered() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()
        c.ignoreInvite()
        assertNull(c.invite.value)

        announce()
        runCurrent()
        assertNull(c.invite.value)

        // Not after coming back to the foreground, nor once idle.
        c.onForeground(false)
        playback.stop()
        foreground(c)
        announce()
        runCurrent()
        assertNull(c.invite.value)
        assertEquals(CastState.Off, c.state.value)

        // Picking it in the TVs list still works.
        c.join("tv-a")
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun leftCastNotReofferedButNewCastIs() = runTest {
        val c = controller()
        announce()
        runCurrent()
        assertTrue(c.state.value is CastState.Following)

        c.leave()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("tv-a"), client.leaves)

        announce()
        c.onForeground(false)
        foreground(c)
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(1, client.joins.size)

        announce(castId = "cast2", startedAt = 2_000L)
        runCurrent()
        assertEquals("cast2", (c.state.value as CastState.Following).peer.castId)
    }

    @Test fun openingLoopholeDuringACastJoinsIt() = runTest {
        val c = controller()
        c.onForeground(false)
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)

        foreground(c)
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun channelChangeIsNotANewInvite() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()
        val first = c.invite.value
        assertNotNull(first)

        advanceTimeBy(6_000)
        announce(name = "Living room") // the casting TV changed channel: same cast, newer announce
        runCurrent()
        advanceTimeBy(4_001)
        assertNull("the timer wasn't restarted", c.invite.value)

        announce()
        runCurrent()
        assertNull(c.invite.value)
        assertEquals(emptyList<String>(), client.joins)
    }

    @Test fun allowJoinOffMeansNoAutoJoinNoInvite() = runTest {
        settings.value = AppSettings(castAllowJoin = false)
        val c = controller()
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)

        playback.watching()
        announce(id = "tv-b", castId = "cast2", host = "192.168.1.21")
        runCurrent()
        assertNull(c.invite.value)

        c.join("tv-a")
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun canAutoJoinFalseBlocksAutoJoin() = runTest {
        autoJoin = false
        val c = controller()
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertNull(c.invite.value)

        playback.watching()
        announce(id = "tv-b", castId = "cast2", host = "192.168.1.21")
        runCurrent()
        assertNull(c.invite.value)
    }

    @Test fun castingTvIgnoresInvitations() = runTest {
        val c = controller()
        playback.watching()
        c.startCasting()
        runCurrent()

        announce()
        runCurrent()
        assertNull(c.invite.value)
        assertTrue(c.state.value is CastState.Casting)
        assertEquals(emptyList<String>(), client.joins)

        c.stopCasting()
        announce()
        runCurrent()
        assertNull(c.invite.value)
        assertEquals(emptyList<String>(), client.joins)
    }

    @Test fun staleCastAfterBackgroundIsNotJoined() = runTest {
        val c = controller()
        c.onForeground(false)
        announce()
        now += 60_000 // An hour, or a minute: long gone either way.
        foreground(c)

        assertEquals(CastState.Off, c.state.value)
        assertEquals(emptyList<String>(), client.joins)
        assertEquals(emptyList<CastPeer>(), c.peers.value)
    }

    @Test fun latestCastWinsAcrossSightingsAfterReturning() = runTest {
        val c = controller(foreground = false)
        c.onForeground(true)
        runCurrent()
        announce(id = "tv-a", castId = "early", startedAt = 3_000L)
        runCurrent()
        advanceTimeBy(1_000)
        announce(id = "tv-b", name = "Kitchen", castId = "late", startedAt = 9_000L, host = "192.168.1.21")
        runCurrent()
        assertEquals("still settling", CastState.Off, c.state.value)

        advanceTimeBy(CastController.SETTLE_MS)
        runCurrent()
        assertEquals("late", (c.state.value as CastState.Following).peer.castId)
        assertEquals(listOf("tv-b"), client.joins)
    }

    @Test fun mostRecentCastWins() = runTest {
        val c = controller()
        announce(id = "tv-b", name = "Kitchen", castId = "late", startedAt = 9_000L, host = "192.168.1.21")
        announce(id = "tv-a", castId = "early", startedAt = 3_000L)
        runCurrent()

        assertEquals("late", (c.state.value as CastState.Following).peer.castId)
        assertEquals(listOf("tv-b"), client.joins)
    }

    @Test fun inviteGoesWhenTheCastEnds() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()
        assertNotNull(c.invite.value)

        announce(status = CastStatus.WATCHING, castId = null)
        runCurrent()
        assertNull(c.invite.value)
    }

    @Test fun joinWhileCastingStopsCastingWithoutReopening() = runTest {
        val c = controller()
        playback.watching()
        c.startCasting()
        runCurrent()
        announce()
        runCurrent()

        c.join("tv-a")
        runCurrent()
        assertNull(host.active.value)
        assertTrue(c.state.value is CastState.Following)
        Thread.sleep(50)
        runCurrent()
        assertEquals(listOf(true), playback.reopens)
    }

    @Test fun joinRefusesAPeerNotCasting() = runTest {
        val c = controller()
        playback.watching()
        announce(status = CastStatus.WATCHING, castId = null)
        runCurrent()
        c.join("tv-a")
        c.join("nobody")
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(emptyList<String>(), client.joins)
    }

    // Following

    @Test fun epochChangeReplaysOnFollower() = runTest {
        val c = controller()
        announce()
        runCurrent()

        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        assertEquals(emptyList<Pair<String, Boolean>>(), playback.casts)

        client.send(playing(1))
        client.send(CastEvent.Heartbeat("cast1", 1, 1))
        runCurrent()
        client.send(playing(1))
        client.send(playing(2, hls = true))
        client.send(playing(3, castId = "other"))
        runCurrent()

        assertEquals(
            listOf(
                "http://192.168.1.20:41000/c/tokcast1/1.ts?id=self" to false,
                "http://192.168.1.20:41000/c/tokcast1/2/index.m3u8" to true,
            ),
            playback.casts,
        )
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun followingEpochTracksThePlayingEvents() = runTest {
        val c = controller()
        announce()
        runCurrent()
        assertNull((c.state.value as CastState.Following).epoch)

        client.send(playing(1))
        runCurrent()
        assertEquals(1, (c.state.value as CastState.Following).epoch)

        client.send(playing(2, hls = true))
        runCurrent()
        assertEquals(2, (c.state.value as CastState.Following).epoch)

        // The same epoch again (a resume) leaves the state as it was.
        val before = c.state.value
        client.send(playing(2, hls = true))
        runCurrent()
        assertSame(before, c.state.value)
    }

    @Test fun stoppedShowsMessageAndReturnsOff() = runTest {
        val c = controller()
        val expected = mapOf(
            StopReason.STOPPED to "Living room stopped casting",
            StopReason.LEFT_APP to "Living room stopped casting",
            StopReason.EXTERNAL_PLAYER to "Living room switched to another player",
        )
        var n = 0
        for ((reason, message) in expected) {
            n++
            announce(castId = "cast$n", startedAt = 1_000L + n)
            runCurrent()
            client.send(playing(1, castId = "cast$n"))
            runCurrent()
            val stopsBefore = playback.stops
            client.send(CastEvent.Stopped("cast$n", reason))
            runCurrent()

            assertEquals(CastState.Off, c.state.value)
            assertEquals(message, messages.last())
            assertEquals(stopsBefore + 1, playback.stops)
        }
        assertEquals(3, messages.size)
        assertEquals("the casting TV ended it; nobody left", emptyList<String>(), client.leaves)
    }

    @Test fun lostConnectionEndsFollowing() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        client.send(IOException("read timed out"))
        runCurrent()

        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("Lost Living room"), messages)
        assertEquals(2, playback.stops)
    }

    @Test fun refusedJoinIsLost() = runTest {
        client.failJoin = CastRefusedException(409)
        val c = controller()
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("Lost Living room"), messages)
    }

    @Test fun fullCastShowsMessage() = runTest {
        client.failJoin = CastFullException()
        val c = controller()
        announce()
        runCurrent()

        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("Living room is already casting to 3 TVs."), messages)
    }

    @Test fun unsupportedShowsMessageOnceAndKeepsFollowing() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        client.send(CastEvent.Idle("cast1", CastEvent.Idle.REASON_UNSUPPORTED))
        client.send(CastEvent.Heartbeat("cast1", 1, 1))
        runCurrent()
        assertEquals(listOf(CastController.UNSUPPORTED_MESSAGE), messages)
        assertEquals("This programme can't be shown on other TVs.", CastController.UNSUPPORTED_MESSAGE)
        assertTrue(c.state.value is CastState.Following)

        client.send(playing(2))
        runCurrent()
        assertEquals(2, playback.casts.size)
        client.send(CastEvent.Idle("cast1", CastEvent.Idle.REASON_UNSUPPORTED))
        runCurrent()
        assertEquals(2, messages.size)
    }

    @Test fun pausedCastKeepsTheLastFrameDespiteErrors() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        playback.stateFlow.value = PlaybackState.Error(CAST_CHANNEL_ID, "Can't play this stream.")
        client.send(CastEvent.Heartbeat("cast1", 1, 1))
        runCurrent()

        assertTrue(c.state.value is CastState.Following)
        assertEquals(1, playback.casts.size)
        assertEquals(emptyList<String>(), messages)

        client.send(playing(2))
        runCurrent()
        assertEquals(2, playback.casts.size)
    }

    @Test fun followerPausesWithTheCastingTvAndPlaysOnWithIt() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1, hls = true))
        runCurrent()
        playback.stateFlow.value = PlaybackState.Playing(CAST_CHANNEL_ID, StreamTags.NONE)
        runCurrent()

        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        assertEquals(1, playback.pauses)
        // While paused, heartbeats don't pull it to the casting TV's position.
        position = 0
        client.send(CastEvent.Heartbeat("cast1", 1, 1, positionMs = 600_000))
        runCurrent()
        assertEquals(emptyList<Long>(), seeks)

        // The same HLS stream plays on where it is, without reconnecting.
        client.send(playing(1, hls = true))
        runCurrent()
        assertEquals(1, playback.resumes)
        assertEquals(1, playback.casts.size)
        assertTrue(c.state.value is CastState.Following)

        // A new stream after a pause is opened as usual (which plays).
        client.send(CastEvent.Idle("cast1"))
        client.send(playing(2, hls = true))
        runCurrent()
        assertEquals(2, playback.pauses)
        assertEquals(1, playback.resumes)
        assertEquals(2, playback.casts.size)
        assertEquals(emptyList<String>(), messages)
    }

    @Test fun pausedTsCastOpensAgainWhenTheCastingTvPlaysOn() = runTest {
        controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        assertEquals(1, playback.pauses)
        // Nothing was read while paused, so the connection may be gone: open it again.
        client.send(playing(1))
        runCurrent()
        assertEquals(0, playback.resumes)
        assertEquals(List(2) { "http://192.168.1.20:41000/c/tokcast1/1.ts?id=self" to false }, playback.casts)
    }

    @Test fun pausedHlsCastThatFailedOpensAgain() = runTest {
        controller()
        announce()
        runCurrent()
        client.send(playing(1, hls = true))
        runCurrent()
        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        playback.stateFlow.value = PlaybackState.Error(CAST_CHANNEL_ID, "Can't play this stream.")
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals("no retries while paused", 1, playback.casts.size)
        client.send(playing(1, hls = true))
        runCurrent()
        assertEquals(0, playback.resumes)
        assertEquals(2, playback.casts.size)
    }

    @Test fun pausingTheCastingTvReachesTheHost() = runTest {
        val c = controller()
        playback.watching()
        c.startCasting()
        runCurrent()
        val session = host.active.value!!
        val follower = java.net.Socket("127.0.0.1", server.port!!)
        follower.getOutputStream().write("GET /c/${session.token}/events?id=f1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val lines = follower.getInputStream().bufferedReader()
        fun nextEvent(): CastEvent {
            while (true) {
                val line = lines.readLine() ?: error("events ended")
                CastJson.event(line)?.let { return it }
            }
        }
        assertEquals(CastEvent.Idle(session.castId), nextEvent())
        // Something to show: an unsupported stream reads as idle, so open a real one through the host.
        val ts = okhttp3.mockwebserver.MockWebServer()
        ts.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(CastHostTest.ts(40_000))))
        ts.start()
        try {
            kotlinx.coroutines.runBlocking {
                host.open(io.github.loopholetv.player.ResolvedStream(ts.url("/a.ts").toString(), "UA", emptyMap()), hls = false, archive = true)
            }
            val playing = nextEvent() as CastEvent.Playing

            playback.pause()
            runCurrent()
            assertEquals(CastEvent.Idle(session.castId), nextEvent())
            playback.resume()
            runCurrent()
            assertEquals(playing, nextEvent())
            assertEquals(1, ts.requestCount)
        } finally {
            follower.close()
            c.stopCasting()
            ts.shutdown()
        }
    }

    @Test fun failedStreamIsAskedForAgainWithGrowingWaits() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        val url = "http://192.168.1.20:41000/c/tokcast1/1.ts?id=self" to false

        fun fail() {
            playback.stateFlow.value = PlaybackState.Error(CAST_CHANNEL_ID, "Can't play this stream.")
            runCurrent()
        }

        // 2 s, 4 s, 8 s, 16 s, then 30 s at most.
        var asked = 1
        for (wait in listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            fail()
            advanceTimeBy(wait - 1)
            assertEquals("not before $wait ms", asked, playback.casts.size)
            advanceTimeBy(2)
            asked++
            assertEquals("after $wait ms", asked, playback.casts.size)
        }
        assertEquals(List(asked) { url }, playback.casts)
        assertEquals("told once, after the third failure", listOf("Can't play this stream."), messages)
        assertTrue(c.state.value is CastState.Following)

        // Playing again starts over: a quick retry, and the message may show again later.
        playback.stateFlow.value = PlaybackState.Playing(CAST_CHANNEL_ID, StreamTags.NONE)
        runCurrent()
        fail()
        advanceTimeBy(2_001)
        assertEquals(asked + 1, playback.casts.size)
        assertEquals(1, messages.size)
    }

    @Test fun noRetryWhileTheCastIsIdle() = runTest {
        controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        playback.stateFlow.value = PlaybackState.Error(CAST_CHANNEL_ID, "Can't play this stream.")
        runCurrent()
        client.send(CastEvent.Idle("cast1"))
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(1, playback.casts.size)
    }

    @Test fun heartbeatPositionResyncsASeekableCast() = runTest {
        controller()
        announce()
        runCurrent()
        client.send(playing(1, hls = true))
        runCurrent()
        playback.stateFlow.value = PlaybackState.Playing(CAST_CHANNEL_ID, StreamTags.NONE)
        position = 60_000
        client.send(CastEvent.Heartbeat("cast1", 1, 1, positionMs = 65_000))
        client.send(CastEvent.Heartbeat("cast1", 1, 1, positionMs = 75_000))
        client.send(CastEvent.Heartbeat("cast1", 1, 1, positionMs = null))
        runCurrent()
        assertEquals(listOf(75_000L), seeks)

        position = null
        client.send(CastEvent.Heartbeat("cast1", 1, 1, positionMs = 300_000))
        runCurrent()
        assertEquals("not seekable here", listOf(75_000L), seeks)
    }

    @Test fun localPlayEndsFollowing() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()
        val stops = playback.stops

        playback.localPlay(42)
        runCurrent()

        assertEquals(CastState.Off, c.state.value)
        assertEquals(emptyList<String>(), messages)
        assertEquals("the new play is left alone", stops, playback.stops)
        assertEquals(listOf("tv-a"), client.leaves)

        // The left cast isn't joined again on its own.
        playback.stop()
        c.onForeground(false)
        foreground(c)
        assertEquals(CastState.Off, c.state.value)
    }

    @Test fun localStopEndsFollowing() = runTest {
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1))
        runCurrent()

        playback.stop()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("tv-a"), client.leaves)
        assertEquals(emptyList<String>(), messages)
    }

    @Test fun localPlayBeforeTheFirstPictureEndsFollowing() = runTest {
        val c = controller()
        announce()
        runCurrent()
        playback.localPlay(42)
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        client.send(playing(1))
        runCurrent()
        assertEquals(emptyList<Pair<String, Boolean>>(), playback.casts)
    }

    @Test fun backgroundStopsCastingAndLeaves() = runTest {
        val c = controller()
        c.startCasting()
        runCurrent()
        c.onForeground(false)
        assertEquals(CastState.Off, c.state.value)
        assertNull(host.active.value)
        assertEquals(0, discovery.listeningPort)
        assertNotNull("followers get the last events first", server.port)
        eventually(advanceMs = 50) { server.port == null }

        foreground(c)
        assertNotNull(server.port)
        announce()
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
        val stops = playback.stops

        c.onForeground(false)
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf("tv-a"), client.leaves)
        assertEquals(stops + 1, playback.stops)
        assertEquals(emptyList<String>(), messages)
        eventually { server.port == null }
    }

    @Test fun backgroundDropsTheInvite() = runTest {
        val c = controller()
        playback.watching()
        announce()
        runCurrent()
        assertNotNull(c.invite.value)

        c.onForeground(false)
        assertNull(c.invite.value)

        foreground(c)
        assertNotNull("not declined: offered again on the next sighting", c.invite.value)
    }

    @Test fun castSeenWhileAutoJoinIsHeldIsJoinedOnceReleased() = runTest {
        autoJoin = false
        val c = controller()
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertNull(c.invite.value)

        // Released (for example, the first-start screen closed): the cast seen meanwhile is joined.
        autoJoin = true
        c.recheckAutoJoin()
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun noJoinWhileTheFirstStartScreensShowAndAJoinOnceTheyClose() = runTest {
        lateinit var c: CastController
        val hold = AutoJoinHold { c.recheckAutoJoin() }
        canAutoJoin = hold::allows
        val route = MutableStateFlow<String?>(null)
        backgroundScope.launch { holdAutoJoin(route, flowOf(false)) { hold.set(it) } }
        c = controller()

        // The first-start screen is up and another TV is casting: nothing is joined.
        route.value = Routes.ONBOARDING
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        route.value = Routes.SETUP_COPY_FROM
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertTrue(client.joins.isEmpty())

        // First start is over (a playlist was added or copied): the cast already running is joined.
        route.value = Routes.GUIDE
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun noJoinWhileASetupCopyIsUnderWayOnATvAlreadySetUp() = runTest {
        lateinit var c: CastController
        val hold = AutoJoinHold { c.recheckAutoJoin() }
        canAutoJoin = hold::allows
        val route = MutableStateFlow<String?>(Routes.GUIDE)
        val setup = MutableStateFlow<SetupState>(SetupState.Idle)
        c = controller()
        backgroundScope.launch { holdAutoJoin(route, setup.map { it !is SetupState.Idle }) { hold.set(it) } }
        runCurrent()

        // This TV shows a code for another TV's setup when a cast starts: nothing is joined.
        setup.value = SetupState.ShowingCode("1234", "Living room", 0)
        runCurrent()
        announce()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        setup.value = SetupState.Working("Copying the setup…")
        runCurrent()
        setup.value = SetupState.Done("Setup copied from Living room.", copied = true, received = true)
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertTrue(client.joins.isEmpty())

        // The copy screen closed: the cast already running is joined.
        setup.value = SetupState.Idle
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
    }

    @Test fun recheckWhileHeldDoesNothing() = runTest {
        autoJoin = false
        val c = controller()
        announce()
        runCurrent()
        c.recheckAutoJoin()
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertNull(c.invite.value)
    }
}
