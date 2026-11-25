package io.github.loopholetv.cast

import androidx.media3.common.Player
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.ui.cast.TvAction
import io.github.loopholetv.ui.cast.TvRow
import io.github.loopholetv.ui.cast.TvsViewModel
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastLockTest {
    private class FakePlayback : Playback {
        override val player: Player get() = error("not used")
        val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = stateFlow
        val modeFlow = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = modeFlow
        override val paused: StateFlow<Boolean> = MutableStateFlow(false)
        var stops = 0
        val casts = mutableListOf<String>()

        override fun pause() {}
        override fun resume() {}
        override fun play(channel: io.github.loopholetv.data.db.ChannelEntity) {}
        override fun reopen() {}
        override fun stop() {
            stops++
            modeFlow.value = PlayMode.Live
            stateFlow.value = PlaybackState.Idle
        }

        override fun playCast(url: String, hls: Boolean) {
            casts += url
            stateFlow.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
            modeFlow.value = PlayMode.Cast
        }

        fun watching() {
            stateFlow.value = PlaybackState.Playing(7, StreamTags.NONE)
        }
    }

    private class FakeClient : CastClient(OkHttpClient()) {
        val joins = mutableListOf<String>()
        val leaves = mutableListOf<String>()
        private val feeds = mutableListOf<Channel<Any>>()

        override fun events(peer: CastPeer, selfId: String): Flow<CastEvent> = flow {
            joins += peer.id
            val feed = Channel<Any>(Channel.UNLIMITED)
            feeds += feed
            for (item in feed) {
                val event = item as CastEvent
                emit(event)
                if (event is CastEvent.Stopped) return@flow
            }
            throw IOException("ended")
        }

        override suspend fun leave(peer: CastPeer, selfId: String) {
            leaves += peer.id
        }

        fun send(event: CastEvent) {
            feeds.last().trySend(event)
        }
    }

    private class FakeNsd : NsdPort {
        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {}
        override fun unregister() {}
        override fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit) {}
        override fun stopBrowse() {}
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LocalHttpServer(realScope)
    private val host = CastHost(realScope, server, OkHttpClient(), clock = { 0L }, heartbeatMs = 3_600_000)
    private var now = 0L
    private val table = PeerTable("self", clock = { now })
    private val discovery = CastDiscovery(realScope, FakeNsd(), table, clock = { 0L }, listenPort = 0, targets = { emptyList() })
    private val playback = FakePlayback()
    private val client = FakeClient()
    private val settings = MutableStateFlow(AppSettings())
    private var announceSeq = 1L
    private val messages = mutableListOf<String>()

    /** This TV has a PIN and it isn't entered. */
    private var lockedNow = true
    private var lockedAsked = 0

    /** What the controller finds out about what plays here when it starts casting. */
    private var playingLocked: suspend () -> Boolean = { false }

    /** Runs while a cast takes the Wi-Fi hold: after the relay started, before playback re-opened. */
    private var onHoldWifi: () -> Unit = {}

    @After fun tearDown() {
        discovery.stop()
        server.stop()
        realScope.cancel()
    }

    private fun TestScope.controller(): CastController {
        val c = CastController(
            backgroundScope, { "self" }, playback, host, discovery, table, client, server, { listOf(loopback) },
            settings, { "This TV" }, clock = { 5_000L }, reachable = { true },
            playingLocked = { playingLocked() },
            holdWifi = {
                onHoldWifi()
                AutoCloseable {}
            },
            lockedNow = {
                lockedAsked++
                lockedNow
            },
        )
        backgroundScope.launch { c.messages.collect { messages += it } }
        c.onForeground(true)
        runCurrent()
        advanceTimeBy(CastController.SETTLE_MS + 1)
        runCurrent()
        return c
    }

    private fun announce(id: String = "tv-a", castId: String = "cast1", locked: Boolean = false) {
        now++
        table.seen(
            CastAnnounce(id, "Living room", 41_000, CastStatus.CASTING, true, castId, "tok$castId", 1_000L, announceSeq++, locked = locked),
            "192.168.1.20",
        )
    }

    private fun playing(epoch: Int, locked: Boolean = false) =
        CastEvent.Playing("cast1", epoch, "/c/tokcast1/$epoch.ts", hls = false, locked = locked)

    @Test fun announceAndPlayingCarryLocked() {
        val a = CastAnnounce("id", "TV", 41_000, CastStatus.CASTING, true, "c", "t", 1L, 3L, locked = true)
        assertTrue(CastJson.announce(CastJson.announce(a))!!.locked)
        assertFalse(CastJson.announce(CastJson.announce(a.copy(locked = false)))!!.locked)
        val p = CastEvent.Playing("c", 1, "/c/t/1.ts", hls = false, locked = true)
        assertEquals(p, CastJson.event(CastJson.event(p)))
        assertFalse((CastJson.event(CastJson.event(p.copy(locked = false))) as CastEvent.Playing).locked)
        // An older TV doesn't send the field: it reads as unlocked.
        val oldAnnounce = CastJson.announce(a.copy(locked = false)).replace(",\"locked\":false", "")
        assertFalse(oldAnnounce.contains("locked"))
        assertFalse(CastJson.announce(oldAnnounce)!!.locked)
        val oldEvent = CastJson.event(p).replace(",\"locked\":true", "")
        assertFalse(oldEvent.contains("locked"))
        assertFalse((CastJson.event(oldEvent) as CastEvent.Playing).locked)
        // The mark also survives the network-service record, and never prints a name.
        assertTrue(CastDiscovery.announceFromTxt(with(CastDiscovery) { a.toTxt() } + mapOf("v" to "1"))!!.locked)
        assertTrue(a.toString().contains("locked=true"))
    }

    @Test fun lockedTvSkipsAutoJoinOfLockedCast() = runTest(StandardTestDispatcher()) {
        lockedNow = true
        val c = controller()
        announce(locked = true)
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(emptyList<String>(), client.joins)
        assertNull(c.invite.value)

        // The same goes for a TV that is playing something: no invite either.
        playback.watching()
        announce(id = "tv-b", castId = "cast2", locked = true)
        runCurrent()
        assertNull(c.invite.value)
    }

    @Test fun unlockedTvAutoJoins() = runTest(StandardTestDispatcher()) {
        lockedNow = false
        val c = controller()
        announce(locked = true)
        runCurrent()
        assertEquals("tv-a", (c.state.value as CastState.Following).peer.id)
    }

    @Test fun noPinTvAutoJoins() = runTest(StandardTestDispatcher()) {
        // No PIN reads as not locked, and a cast that isn't marked never asks at all.
        lockedNow = false
        val c = controller()
        announce(locked = false)
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
        assertEquals(0, lockedAsked)
    }

    @Test fun aFailedLockCheckKeepsALockedCastOut() = runTest(StandardTestDispatcher()) {
        val c = CastController(
            backgroundScope, { "self" }, playback, host, discovery, table, client, server, { listOf(loopback) },
            settings, { "This TV" }, clock = { 5_000L }, lockedNow = { error("no lock state") },
        )
        c.onForeground(true)
        runCurrent()
        advanceTimeBy(CastController.SETTLE_MS + 1)
        runCurrent()
        announce(locked = true)
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
    }

    @Test fun followerStopsWhenHostSwitchesToLocked() = runTest(StandardTestDispatcher()) {
        lockedNow = true
        val c = controller()
        announce(locked = false)
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
        client.send(playing(1))
        runCurrent()
        assertEquals(1, playback.casts.size)

        client.send(playing(2, locked = true))
        runCurrent()
        assertEquals(CastState.Off, c.state.value)
        assertEquals(listOf(CastController.CAST_ENDED_MESSAGE), messages)
        assertEquals("Cast ended", messages.single())
        assertEquals(listOf("tv-a"), client.leaves)
        assertEquals(1, playback.casts.size)
    }

    @Test fun unlockedFollowerKeepsFollowingALockedStream() = runTest(StandardTestDispatcher()) {
        lockedNow = false
        val c = controller()
        announce()
        runCurrent()
        client.send(playing(1, locked = true))
        runCurrent()
        assertTrue(c.state.value is CastState.Following)
        assertEquals(1, playback.casts.size)
        assertEquals(emptyList<String>(), messages)
    }

    @Test fun theFirstCastingAnnounceCarriesTheMarkOfWhatPlays() = runTest(StandardTestDispatcher()) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        playingLocked = {
            gate.await()
            true
        }
        val c = controller()
        c.startCasting()
        runCurrent()
        // The lookup hasn't finished: no announce says this TV casts yet, so no receiver can see it unmarked.
        assertTrue(c.selfAnnounce().status != CastStatus.CASTING)
        gate.complete(Unit)
        runCurrent()
        val first = c.selfAnnounce()
        assertEquals(CastStatus.CASTING, first.status)
        assertTrue(first.locked)
    }

    @Test fun anAnnounceWhileACastStartsIsNeverUnmarked() = runTest(StandardTestDispatcher()) {
        playingLocked = { true }
        lateinit var c: CastController
        val during = mutableListOf<CastAnnounce>()
        // Another thread (the beacon) can ask for the announce at any point of the start.
        onHoldWifi = { during += c.selfAnnounce() }
        c = controller()
        c.startCasting()
        assertEquals(1, during.size)
        assertTrue(during.single().status != CastStatus.CASTING)
        runCurrent()
        val first = c.selfAnnounce()
        assertEquals(CastStatus.CASTING, first.status)
        assertTrue(first.locked)
    }

    @Test fun aStaleLookupDoesNotMarkANewerCast() = runTest(StandardTestDispatcher()) {
        val gates = mutableListOf<kotlinx.coroutines.CompletableDeferred<Boolean>>()
        playingLocked = { kotlinx.coroutines.CompletableDeferred<Boolean>().also { gates += it }.await() }
        val c = controller()
        c.startCasting()
        runCurrent()
        c.stopCasting(StopReason.STOPPED)
        runCurrent()
        c.startCasting()
        runCurrent()
        assertEquals(2, gates.size)
        // The first cast's lookup ends late: the second cast is still waiting for its own mark.
        gates[0].complete(false)
        runCurrent()
        assertTrue(c.selfAnnounce().status != CastStatus.CASTING)
        gates[1].complete(true)
        runCurrent()
        assertEquals(CastStatus.CASTING, c.selfAnnounce().status)
        assertTrue(c.selfAnnounce().locked)
    }

    @Test fun anUnlockedChannelIsAnnouncedUnmarked() = runTest(StandardTestDispatcher()) {
        playingLocked = { false }
        val c = controller()
        c.startCasting()
        runCurrent()
        val a = c.selfAnnounce()
        assertEquals(CastStatus.CASTING, a.status)
        assertFalse(a.locked)
    }

    // The TVs panel

    private class FakeCast(peer: CastPeer) : CastView {
        override val state = MutableStateFlow<CastState>(CastState.Off)
        override val peers = MutableStateFlow(listOf(peer))
        override val invite = MutableStateFlow<CastInvite?>(null)
        override val messages = MutableSharedFlow<String>()
        val joined = mutableListOf<String>()
        override fun startCasting() {}
        override fun stopCasting(reason: StopReason) {}
        override fun join(peerId: String) { joined += peerId }
        override fun leave() {}
        override fun ignoreInvite() {}
    }

    private fun peer(locked: Boolean) = CastPeer(
        "tv-a", "Living room", "192.168.1.20", 41_000, CastStatus.CASTING, true, "c", "t", 1L, 0L,
        setup = CastAnnounce.SETUP_VERSION, locked = locked,
    )

    private fun menuVm(locked: Boolean, pin: Boolean): TvsViewModel {
        val parental = if (pin) ParentalSettings(pinHash = "h", pinSalt = "s") else ParentalSettings()
        val vm = TvsViewModel(FakeCast(peer(locked)), flowOf(false), parental = flowOf(parental))
        vm.onOk(TvRow.Peer("tv-a", "Living room", CastStatus.CASTING, CastAnnounce.SETUP_VERSION))
        return vm
    }

    @Test fun manualJoinAsksPin() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            assertTrue(menuVm(locked = true, pin = true).needsPin(TvAction.JOIN))
            assertFalse(menuVm(locked = false, pin = true).needsPin(TvAction.JOIN))
            assertFalse(menuVm(locked = true, pin = false).needsPin(TvAction.JOIN))
        } finally {
            Dispatchers.resetMain()
        }
    }
}
