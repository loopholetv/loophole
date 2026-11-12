package io.github.loopholetv.cast

import androidx.media3.common.Player
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.catchup.PlayMode
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller against a real relay and event stream on loopback, in real time. */
class CastControllerLoopbackTest {
    private class LoopbackPlayback : Playback {
        override val player: Player get() = error("not used")
        val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = stateFlow
        private val modeFlow = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = modeFlow
        @Volatile var stops = 0

        override fun play(channel: io.github.loopholetv.data.db.ChannelEntity) {}

        override fun stop() {
            stops++
            modeFlow.value = PlayMode.Live
            stateFlow.value = PlaybackState.Idle
        }

        override fun playCast(url: String, hls: Boolean) {
            stateFlow.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
            modeFlow.value = PlayMode.Cast
        }
    }

    private class NoNsd : NsdPort {
        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {}
        override fun unregister() {}
        override fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit) {}
        override fun stopBrowse() {}
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val main = CoroutineScope(SupervisorJob() + mainThread)
    private val servers = mutableListOf<LocalHttpServer>()
    private val discoveries = mutableListOf<CastDiscovery>()

    @After fun tearDown() {
        main.cancel()
        io.cancel()
        discoveries.forEach { it.stop() }
        servers.forEach { it.stop() }
        mainThread.close()
    }

    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking(mainThread) { block() }

    private fun await(timeoutMs: Long = 5_000, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun server() = LocalHttpServer(io).also { servers += it }

    private fun host(server: LocalHttpServer) = CastHost(io, server, OkHttpClient(), clock = { 0L }, heartbeatMs = 3_600_000)

    private class Tv(val controller: CastController, val playback: LoopbackPlayback, val table: PeerTable, val messages: MutableList<String>)

    private fun tv(server: LocalHttpServer, host: CastHost, client: CastClient): Tv {
        val table = PeerTable("self", clock = { 0L })
        val discovery = CastDiscovery(io, NoNsd(), table, clock = { 0L }, listenPort = 0, targets = { emptyList() })
        discoveries += discovery
        val playback = LoopbackPlayback()
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val controller = onMain {
            CastController(
                main, { "self" }, playback, host, discovery, table, client, server, { listOf(loopback) },
                MutableStateFlow(AppSettings()), { "This TV" }, clock = System::currentTimeMillis,
                // Loopback stands in for the home network here.
                reachable = { true }, settleMs = 0,
            ).also { c ->
                main.launch(start = CoroutineStart.UNDISPATCHED) { c.messages.collect { messages += it } }
                c.onForeground(true)
            }
        }
        return Tv(controller, playback, table, messages)
    }

    @Test fun heartbeatTimeoutEndsFollowing() {
        // The other TV casts, then goes silent (asleep, unplugged): no heartbeat and no Stopped.
        val castServer = server()
        val castPort = castServer.start(listOf(loopback))
        val castHost = host(castServer)
        val session = castHost.start()

        val readTimeout = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        val myServer = server()
        val me = tv(myServer, host(myServer), CastClient(readTimeout))
        onMain {
            me.table.seen(
                CastAnnounce("tv-a", "Living room", castPort, CastStatus.CASTING, true, session.castId, session.token, session.startedAtMs, 1),
                "127.0.0.1",
            )
        }
        await { me.controller.state.value is CastState.Following }
        await { castHost.followers.value == 1 }

        val started = System.nanoTime()
        await(10_000) { me.messages.isNotEmpty() }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        assertEquals(listOf("Lost Living room"), me.messages.toList())
        assertEquals(CastState.Off, me.controller.state.value)
        await { me.playback.stops == 2 }
    }

    @Test fun localStopBroadcastsIdle() {
        val server = server()
        val host = host(server)
        val me = tv(server, host, CastClient(OkHttpClient()))
        onMain {
            me.playback.stateFlow.value = PlaybackState.Playing(5, StreamTags.NONE)
            me.controller.startCasting()
        }
        val session = host.active.value!!
        val follower = CastClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
        val peer = CastPeer(
            "self", "This TV", "127.0.0.1", server.port!!, CastStatus.CASTING, true,
            session.castId, session.token, session.startedAtMs, 0,
        )
        val events = Channel<CastEvent>(Channel.UNLIMITED)
        io.launch {
            try {
                follower.events(peer, "follower-1").collect { events.send(it) }
            } catch (_: java.io.IOException) {
                // The cast ends with the test.
            }
        }

        runBlocking {
            withTimeout(5_000) {
                assertEquals(CastEvent.Idle(session.castId), events.receive())
                await { me.controller.state.value == CastState.Casting(1) }

                // Something the relay can't share first, so the next Idle is the stop's own.
                host.open(ResolvedStream("rtsp://example.test/live", "ua", emptyMap()), hls = false)
                assertEquals(CastEvent.Idle(session.castId, CastEvent.Idle.REASON_UNSUPPORTED), events.receive())

                onMain { me.playback.stateFlow.value = PlaybackState.Idle }
                assertEquals(CastEvent.Idle(session.castId), events.receive())
            }
        }
        assertTrue(me.controller.state.value is CastState.Casting)
    }
}
