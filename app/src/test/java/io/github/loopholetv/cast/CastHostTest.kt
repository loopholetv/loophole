package io.github.loopholetv.cast

import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.player.ResolvedStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastHostTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provider = MockWebServer()
    private val upstreamLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val closeThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val upstream = OkHttpClient.Builder()
        .eventListenerFactory { UpstreamLog(upstreamLog, closeThreads) }
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val client = OkHttpClient.Builder()
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val server = LocalHttpServer(serverScope, maxPerRemote = 16)
    private var port = 0
    private var host: CastHost? = null

    @Before fun setUp() {
        provider.start()
        port = server.start(listOf(loopback))
    }

    @After fun tearDown() {
        host?.let { runBlocking { it.stop(StopReason.STOPPED) } }
        server.stop()
        provider.shutdown()
        serverScope.cancel()
        hostScope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        upstream.dispatcher.executorService.shutdown()
        upstream.connectionPool.evictAll()
        Dispatchers.resetMain()
    }

    // Helpers

    private fun newHost(
        scope: CoroutineScope = hostScope,
        heartbeatMs: Long = 600_000,
        clock: () -> Long = { 0L },
        capacity: Int = 4 * 1024 * 1024,
        positionMs: () -> Long? = { null },
        hlsCacheBytes: Long = 48L * 1024 * 1024,
    ) = CastHost(
        scope = scope,
        server = server,
        upstreamHttp = upstream,
        clock = clock,
        random = SecureRandom(),
        wallClock = { STARTED_AT },
        heartbeatMs = heartbeatMs,
        positionMs = positionMs,
        userAgent = APP_UA,
        tsCapacityBytes = capacity,
        hlsCacheBytes = hlsCacheBytes,
    ).also { host = it }

    private fun stream(path: String) = ResolvedStream(
        provider.url(path).toString(),
        "ProviderAgent/9",
        mapOf("Referer" to "http://portal.example.test/"),
    )

    private fun open(host: CastHost, s: ResolvedStream, hls: Boolean = false, archive: Boolean = false) =
        runBlocking { host.open(s, hls, archive) }

    private fun local(path: String) = "http://127.0.0.1:$port$path"

    private fun get(url: String, range: String? = null): Response {
        val builder = Request.Builder().url(url)
        if (range != null) builder.header("Range", range)
        return client.newCall(builder.build()).execute()
    }

    private fun code(url: String): Int = get(url).use { it.code }

    private fun post(path: String): Int =
        client.newCall(Request.Builder().url(local(path)).post(ByteArray(0).toRequestBody()).build())
            .execute().use { it.code }

    private inner class Follower(id: String, session: CastSession) : Closeable {
        val response = get(local("/c/${session.token}/events?id=$id"))
        private val source: BufferedSource get() = response.body.source()
        val lines = mutableListOf<String>()

        fun next(): CastEvent {
            val line = source.readUtf8Line() ?: throw IOException("events ended")
            lines += line
            return CastJson.event(line) ?: throw AssertionError("bad event line")
        }

        fun ended(): Boolean = source.readUtf8Line() == null

        override fun close() = response.close()
    }

    private fun follower(id: String, session: CastSession): Follower = Follower(id, session).also {
        assertEquals(200, it.response.code)
    }

    private fun tsResponse(packets: Int, from: Int = 0) =
        MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ts(packets, from)))

    /** A live-like stream: [packets] packets at about 1.9 MB/s. */
    private fun liveResponse(packets: Int = 60_000) = tsResponse(packets).throttleBody(PACKET * 100L, 10, TimeUnit.MILLISECONDS)

    private fun readExactly(response: Response, bytes: Long): ByteArray {
        val source = response.body.source()
        source.require(bytes)
        return source.readByteArray(bytes)
    }

    /** The upstream log never shows two provider requests in flight at once. */
    private fun assertOneAtATime() {
        var open = 0
        synchronized(upstreamLog) {
            upstreamLog.forEach {
                if (it.startsWith("start")) open++ else open--
                assertTrue(upstreamLog.toString(), open in 0..1)
            }
        }
    }

    // Session

    @Test fun startUsesWallClockAndFreshSecrets() {
        val h = newHost()
        val first = h.start()
        assertEquals(STARTED_AT, first.startedAtMs)
        assertEquals(0, first.epoch)
        assertTrue(first.token.matches(Regex("[0-9a-f]{32}")))
        assertEquals(first, h.active.value)
        assertSame(first, h.start())
        assertFalse(first.toString().contains(first.token))
        runBlocking { h.stop(StopReason.STOPPED) }
        assertNull(h.active.value)
        val second = h.start()
        assertNotEquals(first.token, second.token)
        assertNotEquals(first.castId, second.castId)
    }

    @Test fun openOnInactiveHostReturnsTheRealStream() {
        val h = newHost()
        val real = stream("/live/1.ts")
        assertSame(real, open(h, real))
        assertEquals(0, provider.requestCount)
    }

    // Local player

    @Test fun localPlayerGetsLoopbackUrl() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        val loop = open(h, stream("/live/1.ts"))
        assertTrue(loop.url.startsWith("http://127.0.0.1:$port/c/${session.token}/1.ts"))
        assertEquals(APP_UA, loop.userAgent)
        assertEquals(emptyMap<String, String>(), loop.headers)
        assertEquals(1, h.active.value?.epoch)
        val body = get(loop.url).use { readExactly(it, PACKET * 400L) }
        assertEquals((0 until 400).toList(), counters(body))
        val seen = provider.takeRequest()
        assertEquals("ProviderAgent/9", seen.getHeader("User-Agent"))
        assertEquals("http://portal.example.test/", seen.getHeader("Referer"))
    }

    @Test fun oneUpstreamForPlayerAndThreeFollowers() {
        provider.enqueue(liveResponse(20_000))
        val h = newHost()
        val session = h.start()
        val followers = listOf("a", "b", "c").map { follower(it, session) }
        followers.forEach { assertEquals(CastEvent.Idle(session.castId), it.next()) }
        val loop = open(h, stream("/live/1.ts"))
        val paths = followers.map { (it.next() as CastEvent.Playing).path }
        val readers = listOf(loop.url) + paths.map { local(it) }
        readers.map { get(it) }.forEach { r ->
            r.use {
                assertEquals(200, it.code)
                assertEquals("video/mp2t", it.header("Content-Type"))
                val got = counters(readExactly(it, PACKET * 1_000L))
                assertEquals((got.first() until got.first() + 1_000).toList(), got)
            }
        }
        assertEquals(3, h.followers.value)
        assertEquals(1, provider.requestCount)
        followers.forEach { it.close() }
    }

    @Test fun rangedRetryGets206() {
        provider.enqueue(liveResponse())
        val h = newHost()
        h.start()
        val loop = open(h, stream("/live/1.ts"))
        get(loop.url, range = "bytes=5000-").use {
            assertEquals(206, it.code)
            assertEquals("bytes 5000-*/*", it.header("Content-Range"))
            readExactly(it, PACKET * 10L)
        }
        get(loop.url).use {
            assertEquals(200, it.code)
            assertNull(it.header("Content-Range"))
        }
    }

    // Followers

    @Test fun fourthFollowerIs503() {
        val h = newHost()
        val session = h.start()
        val three = listOf("a", "b", "c").map { follower(it, session) }
        three.forEach { it.next() }
        assertEquals(3, h.followers.value)
        assertEquals(503, code(local("/c/${session.token}/events?id=d")))
        assertEquals(204, post("/c/${session.token}/leave?id=a"))
        assertTrue(three[0].ended())
        assertEquals(2, h.followers.value)
        follower("d", session).use { assertEquals(CastEvent.Idle(session.castId), it.next()) }
        three.forEach { it.close() }
    }

    @Test fun rejoiningWithTheSameIdReplacesTheOldStream() {
        val h = newHost()
        val session = h.start()
        val old = follower("a", session)
        old.next()
        follower("a", session).use { again ->
            assertEquals(CastEvent.Idle(session.castId), again.next())
            assertTrue(old.ended())
            assertEquals(1, h.followers.value)
        }
        old.close()
    }

    @Test fun wrongTokenIs401() {
        val h = newHost()
        val session = h.start()
        val wrong = session.token.reversed()
        assertEquals(401, code(local("/c/$wrong/events?id=a")))
        assertEquals(401, code(local("/c/$wrong/1.ts")))
        assertEquals(401, code(local("/c/$wrong/1/index.m3u8")))
        assertEquals(401, code(local("/c/${session.token}x/events?id=a")))
        assertEquals(401, post("/c/$wrong/leave?id=a"))
        assertEquals(400, code(local("/c/${session.token}/events")))
    }

    @Test fun oldEpochIs409() {
        provider.enqueue(tsResponse(10))
        provider.enqueue(tsResponse(10))
        val h = newHost()
        val session = h.start()
        open(h, stream("/live/1.ts"))
        val second = open(h, stream("/live/2.ts"))
        assertTrue(second.url.contains("/c/${session.token}/2.ts"))
        assertEquals(409, code(local("/c/${session.token}/1.ts")))
        assertEquals(409, code(local("/c/${session.token}/1/index.m3u8")))
        assertEquals(409, code(local("/c/${session.token}/7.ts")))
        assertEquals(200, code(second.url))
    }

    @Test fun channelChangeMovesFollowersToNewEpoch() {
        provider.enqueue(liveResponse())
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        val f = follower("a", session)
        f.next()
        open(h, stream("/one.ts"))
        val first = f.next() as CastEvent.Playing
        assertEquals(1, first.epoch)
        val media = get(local(first.path))
        readExactly(media, PACKET * 100L)
        open(h, stream("/two.ts"))
        assertEquals(CastEvent.Playing(session.castId, 2, "/c/${session.token}/2.ts", false), f.next())
        // The old epoch's stream ends rather than splicing in the new channel.
        media.use { it.body.source().readByteArray() }
        assertEquals(2, provider.requestCount)
        val log = synchronized(upstreamLog) { upstreamLog.toList() }
        val firstEnded = log.indexOf("end /one.ts")
        assertTrue(log.toString(), firstEnded >= 0 && firstEnded < log.indexOf("start /two.ts"))
        assertOneAtATime()
        f.close()
    }

    @Test fun stalledFollowerDoesNotStallOthers() {
        val total = 32_000
        provider.enqueue(tsResponse(total).throttleBody(PACKET * 170L, 10, TimeUnit.MILLISECONDS))
        val h = newHost(capacity = 1024 * 1024)
        val session = h.start()
        val stalled = follower("stalled", session)
        val active = listOf("a", "b").map { follower(it, session) }
        active.forEach { it.next() }
        val loop = open(h, stream("/live/1.ts"))
        val path = (active.first().next() as CastEvent.Playing).path
        active.last().next()
        // Neither the stalled follower's events nor its media are ever read.
        val stalledMedia = get(local(path))
        val readers = listOf(get(loop.url), get(local(path)), get(local(path)))
        val results = runBlocking {
            readers.map { r -> async(Dispatchers.IO) { r.use { counters(it.body.bytes()) } } }.awaitAll()
        }
        results.forEach { got ->
            assertTrue(got.isNotEmpty())
            assertEquals(total - 1, got.last())
            assertEquals((got.first() until total).toList(), got)
        }
        stalledMedia.close()
        stalled.close()
        active.forEach { it.close() }
    }

    @Test fun tsReadersCappedAtFollowersPlusTwo() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        val loop = open(h, stream("/live/1.ts"))
        val path = local("/c/${session.token}/1.ts")
        val localReader = get(loop.url)
        readExactly(localReader, PACKET.toLong())
        val one = get(path)
        assertEquals(200, one.code)
        readExactly(one, PACKET.toLong())
        assertEquals(CastHost.READERS_FULL, code(path))
        follower("a", session).use { f ->
            f.next()
            get(path).use {
                assertEquals(200, it.code)
                readExactly(it, PACKET.toLong())
                assertEquals(CastHost.READERS_FULL, code(path))
                // The casting TV's own player is never refused.
                get(loop.url).use { again -> assertEquals(200, again.code) }
            }
        }
        one.close()
        localReader.close()
    }

    // Pausing

    @Test fun pauseIdlesFollowersAndKeepsTheTsArchiveOpen() {
        provider.enqueue(tsResponse(40_000))
        val h = newHost(capacity = 512 * 1024)
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val loop = open(h, stream("/archive/1.ts"), archive = true)
            val playing = f.next() as CastEvent.Playing
            val local = get(loop.url)
            readExactly(local, PACKET * 100L)

            h.pause(true)
            h.pause(true)
            assertEquals(CastEvent.Idle(session.castId), f.next())
            // A TV joining meanwhile is told the same.
            follower("b", session).use { assertEquals(CastEvent.Idle(session.castId), it.next()) }
            // The provider connection stays, and this TV's player reads on from where it was.
            assertFalse(upstreamLog.toString(), upstreamLog.contains("end /archive/1.ts"))
            assertEquals(100, counters(readExactly(local, PACKET * 10L)).first())

            h.pause(false)
            assertEquals(playing, f.next())
            assertEquals(1, h.active.value?.epoch)
            assertEquals(1, provider.requestCount)
            local.close()
        }
    }

    @Test fun pauseIdlesFollowersAndKeepsTheHlsArchiveOpen() {
        provider.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/archive/1.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(
                    "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:6\n" +
                        "#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n#EXT-X-ENDLIST\n",
                )
                "/archive/seg1.ts" -> MockResponse().setBody("SEGMENT-ONE")
                "/archive/seg2.ts" -> MockResponse().setBody("SEGMENT-TWO")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            open(h, stream("/archive/1.m3u8"), hls = true, archive = true)
            val playing = f.next() as CastEvent.Playing
            assertTrue(playing.hls)

            h.pause(true)
            assertEquals(CastEvent.Idle(session.castId), f.next())
            // Still served while paused: this TV's player (and a follower catching up) read on.
            get(local("/c/${session.token}/1/index.m3u8")).use { assertEquals(200, it.code) }
            get(local("/c/${session.token}/1/s-1")).use { assertEquals("SEGMENT-ONE", it.body.string()) }

            h.pause(false)
            assertEquals(playing, f.next())
            get(local("/c/${session.token}/1/s-2")).use { assertEquals("SEGMENT-TWO", it.body.string()) }
        }
        // The link was asked for once, by the open; then the two segments.
        assertEquals(1, upstreamLog.count { it == "start /archive/1.m3u8" })
        assertEquals(3, provider.requestCount)
    }

    @Test fun streamOpenedWhilePausedIsAnnouncedWhenPlayingOn() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        h.pause(true)
        follower("a", session).use { f ->
            assertEquals(CastEvent.Idle(session.castId), f.next())
            open(h, stream("/live/1.ts"))
            assertEquals(CastEvent.Idle(session.castId), f.next())
            h.pause(false)
            assertEquals(CastEvent.Playing(session.castId, 1, "/c/${session.token}/1.ts", false), f.next())
        }
        // A new cast starts unpaused.
        runBlocking { h.stop(StopReason.STOPPED) }
        val next = h.start()
        provider.enqueue(liveResponse())
        follower("b", next).use { f ->
            f.next()
            open(h, stream("/live/2.ts"))
            assertTrue(f.next() is CastEvent.Playing)
        }
    }

    @Test fun upstreamEndBroadcastsIdle() {
        provider.enqueue(tsResponse(20))
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            open(h, stream("/live/1.ts"))
            assertTrue(f.next() is CastEvent.Playing)
            assertEquals(CastEvent.Idle(session.castId), f.next())
        }
    }

    @Test fun idleReleasesTheUpstreamAndBroadcastsIdle() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            open(h, stream("/live/1.ts"))
            f.next()
            runBlocking { h.idle() }
            assertEquals(CastEvent.Idle(session.castId), f.next())
            assertTrue(upstreamLog.contains("end /live/1.ts"))
            assertEquals(0, upstream.connectionPool.connectionCount())
            assertEquals(409, code(local("/c/${session.token}/1.ts")))
        }
    }

    @Test fun stopSendsStoppedAndClosesUpstream() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        val f = follower("a", session)
        f.next()
        open(h, stream("/live/1.ts"))
        f.next()
        runBlocking { h.stop(StopReason.LEFT_APP) }
        assertEquals(CastEvent.Stopped(session.castId, StopReason.LEFT_APP), f.next())
        assertTrue(f.ended())
        assertTrue(upstreamLog.contains("end /live/1.ts"))
        assertEquals(0, upstream.connectionPool.connectionCount())
        assertNull(h.active.value)
        assertEquals(0, h.followers.value)
        assertEquals(404, code(local("/c/${session.token}/events?id=b")))
        val real = stream("/live/2.ts")
        assertSame(real, open(h, real))
        assertEquals(1, provider.requestCount)
        f.close()
    }

    @Test fun heartbeatEvery2s() {
        val test = TestScope()
        val onMain = ThreadLocal<Boolean>()
        Dispatchers.setMain(MarkedDispatcher(StandardTestDispatcher(test.testScheduler), onMain))
        val position = AtomicLong(-1)
        val calledOnMain = Collections.synchronizedList(mutableListOf<Boolean>())
        val supplier = {
            calledOnMain += onMain.get() == true
            position.get().takeIf { it >= 0 }
        }
        val h = newHost(scope = test, heartbeatMs = 2_000, positionMs = supplier)
        val session = h.start()
        test.runCurrent()
        follower("a", session).use { f ->
            assertEquals(CastEvent.Idle(session.castId), f.next())
            test.advanceTimeBy(1_999)
            test.runCurrent()
            // A marker event: had a heartbeat gone out early, it would come before this.
            runBlocking { h.idle() }
            assertEquals(CastEvent.Idle(session.castId), f.next())
            test.advanceTimeBy(1)
            test.runCurrent()
            assertEquals(CastEvent.Heartbeat(session.castId, 0, 1, null), f.next())
            position.set(90_000)
            test.advanceTimeBy(2_000)
            test.runCurrent()
            assertEquals(CastEvent.Heartbeat(session.castId, 0, 1, 90_000), f.next())
            // A supplier that throws costs the position, not the heartbeat.
            position.set(-2)
            val throwing = newHost(scope = test, heartbeatMs = 2_000, positionMs = { error("wrong thread") })
            assertEquals(listOf(true, true), calledOnMain.toList())
            runBlocking { h.stop(StopReason.STOPPED) }
            val other = throwing.start()
            test.runCurrent()
            follower("b", other).use { g ->
                g.next()
                test.advanceTimeBy(2_000)
                test.runCurrent()
                assertEquals(CastEvent.Heartbeat(other.castId, 0, 1, null), g.next())
            }
        }
        test.cancel()
    }

    @Test fun followerThatStopsAnsweringHeartbeatsIsDroppedWithin10s() {
        val test = TestScope()
        Dispatchers.setMain(StandardTestDispatcher(test.testScheduler))
        val h = newHost(scope = test, heartbeatMs = 2_000, clock = { test.testScheduler.currentTime })
        val session = h.start()
        test.runCurrent()
        val answering = follower("a", session)
        val gone = follower("b", session)
        answering.next()
        gone.next()
        assertEquals(2, h.followers.value)
        // A follower's answer only counts for a follower this cast has.
        assertEquals(404, post("/c/${session.token}/alive?id=nobody"))
        assertEquals(401, post("/c/${session.token.reversed()}/alive?id=a"))

        for (second in listOf(2, 4, 6, 8, 10)) {
            test.advanceTimeBy(2_000)
            test.runCurrent()
            val beat = answering.next() as CastEvent.Heartbeat
            assertEquals("at $second s", if (second < 10) 2 else 1, beat.followers)
            assertEquals(204, post("/c/${session.token}/alive?id=a"))
        }
        assertEquals(1, h.followers.value)
        // The silent one's stream ends once the heartbeats sent before are read.
        while (!gone.ended()) Unit
        test.advanceTimeBy(2_000)
        test.runCurrent()
        assertEquals(CastEvent.Heartbeat(session.castId, 0, 1, null), answering.next())
        answering.close()
        gone.close()
        test.cancel()
    }

    @Test fun followerPathsCarryNoProviderUrl() {
        provider.enqueue(tsResponse(10))
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val loop = open(h, stream("/live/user1/pass1/77.ts?token=s3cret"))
            val playing = f.next() as CastEvent.Playing
            assertTrue(playing.path, playing.path.matches(Regex("/c/[0-9a-f]{32}/1\\.ts")))
            val shown = f.lines.joinToString("\n") + loop.url
            listOf("user1", "pass1", "s3cret", "77.ts", "ProviderAgent", "portal.example").forEach {
                assertFalse(it, shown.contains(it))
            }
            assertFalse(f.lines.joinToString().contains(":${provider.port}"))
        }
    }

    // Stream kinds

    @Test fun hlsStreamGoesThroughHlsRelay() {
        provider.dispatcher = hlsProvider()
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val loop = open(h, stream("/hls/live.m3u8"), hls = true)
            assertEquals(local("/c/${session.token}/1/index.m3u8"), loop.url)
            assertEquals(CastEvent.Playing(session.castId, 1, "/c/${session.token}/1/index.m3u8", true), f.next())
            val playlist = get(loop.url).use {
                assertEquals(200, it.code)
                it.body.string()
            }
            assertTrue(playlist, playlist.contains("s-1"))
            assertFalse(playlist, playlist.contains("s3cret") || playlist.contains("seg1"))
            get(local("/c/${session.token}/1/s-1")).use { assertEquals("SEGMENT-ONE", it.body.string()) }
            assertEquals(503, code(local("/c/${session.token}/1/s-2")))
            assertEquals(404, code(local("/c/${session.token}/1/s-999")))
            assertEquals(404, code(local("/c/${session.token}/1.ts")))
        }
        // The probe's playlist was reused, so the link was requested once; then the two segments.
        assertEquals(3, provider.requestCount)
        assertEquals(1, upstreamLog.count { it == "start /hls/live.m3u8" })
        assertOneAtATime()
    }

    @Test fun hlsCacheBudgetReachesTheRelay() {
        provider.dispatcher = hlsProvider()
        for ((budget, fetches) in listOf(48L * 1024 * 1024 to 1, 4L to 2)) {
            val h = newHost(hlsCacheBytes = budget)
            val session = h.start()
            open(h, stream("/hls/live.m3u8"), hls = true)
            get(local("/c/${session.token}/1/index.m3u8")).close()
            val before = provider.requestCount
            repeat(2) { get(local("/c/${session.token}/1/s-1")).use { assertEquals("SEGMENT-ONE", it.body.string()) } }
            // An 11-byte segment fits the default budget once; a 4-byte budget can't keep it.
            assertEquals("budget $budget", fetches, provider.requestCount - before)
            runBlocking { h.stop(StopReason.STOPPED) }
        }
    }

    @Test fun extensionlessHlsIsDetectedFromTheBody() {
        provider.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain")
                .setBody("﻿ \n#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"),
        )
        val h = newHost()
        val session = h.start()
        val loop = open(h, stream("/play?id=5"), hls = false)
        assertEquals(local("/c/${session.token}/1/index.m3u8"), loop.url)
    }

    @Test fun tsIsDetectedFromTheBodyDespiteAnHlsHint() {
        provider.enqueue(tsResponse(10))
        val h = newHost()
        val session = h.start()
        val loop = open(h, stream("/live/1.m3u8"), hls = true)
        assertTrue(loop.url.startsWith(local("/c/${session.token}/1.ts")))
    }

    @Test fun unsupportedFormatPlaysDirectlyAndTellsFollowers() {
        val mp4 = Buffer().write(byteArrayOf(0, 0, 0, 0x18)).writeUtf8("ftypmp42").write(ByteArray(4_000))
        provider.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(mp4))
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val real = stream("/vod/9.mp4")
            assertSame(real, open(h, real, archive = true))
            assertEquals(CastEvent.Idle(session.castId, CastEvent.Idle.REASON_UNSUPPORTED), f.next())
            // Our probe is gone before the casting TV opens the stream itself.
            assertTrue(upstreamLog.contains("end /vod/9.mp4"))
            assertEquals(0, upstream.connectionPool.connectionCount())
            val rtsp = ResolvedStream("rtsp://cam.example.test/1", "UA", emptyMap())
            assertSame(rtsp, open(h, rtsp))
            assertEquals(CastEvent.Idle(session.castId, CastEvent.Idle.REASON_UNSUPPORTED), f.next())
        }
        assertEquals(1, provider.requestCount)
    }

    // Upstream failures

    @Test fun busyUpstreamSurfacesAs503ToLocalPlayer() {
        // Each busy answer is asked about once more before it counts.
        for (code in listOf(503, 403, 429)) repeat(2) { provider.enqueue(MockResponse().setResponseCode(code)) }
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val busy = open(h, stream("/live/1.ts"))
            assertEquals(503, code(busy.url))
            assertEquals(CastEvent.Idle(session.castId), f.next())
            val refused = open(h, stream("/live/1.ts"))
            assertEquals(403, code(refused.url))
            f.next()
            val limited = open(h, stream("/live/1.m3u8"), hls = true)
            assertEquals(local("/c/${session.token}/3/index.m3u8"), limited.url)
            assertEquals(429, code(limited.url))
            f.next()
            // Nothing listening: the player sees a busy-style refusal it already retries.
            val unreachable = open(h, ResolvedStream("http://127.0.0.1:1/live.ts", "UA", emptyMap()))
            assertEquals(503, code(unreachable.url))
            assertEquals(CastEvent.Idle(session.castId), f.next())
        }
    }

    @Test fun busyProviderIsAskedOnceMoreWithinTheOpen() {
        provider.enqueue(MockResponse().setResponseCode(403))
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val started = System.nanoTime()
            val loop = open(h, stream("/live/1.ts"))
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(500))
            assertTrue(loop.url.startsWith(local("/c/${session.token}/1.ts")))
            assertEquals(CastEvent.Playing(session.castId, 1, "/c/${session.token}/1.ts", false), f.next())
            get(loop.url).use { assertEquals(200, it.code); readExactly(it, PACKET * 100L) }
        }
        assertEquals(2, provider.requestCount)
        assertOneAtATime()
    }

    @Test fun otherRefusalsAreNotAskedAgain() {
        provider.enqueue(MockResponse().setResponseCode(404))
        provider.enqueue(MockResponse().setResponseCode(500))
        val h = newHost()
        val session = h.start()
        assertEquals(404, code(open(h, stream("/live/1.ts")).url))
        assertEquals(500, code(open(h, stream("/live/1.ts")).url))
        assertEquals(503, code(open(h, ResolvedStream("http://127.0.0.1:1/live.ts", "UA", emptyMap())).url))
        assertEquals(2, provider.requestCount)
        assertEquals(3, h.active.value?.epoch)
        assertEquals(session.castId, h.active.value?.castId)
    }

    @Test fun stopDuringTheBusyWaitAsksNoMore() {
        provider.enqueue(MockResponse().setResponseCode(503))
        provider.enqueue(liveResponse())
        val h = newHost()
        h.start()
        val real = stream("/live/1.ts")
        val pending = hostScope.async { h.open(real, hls = false) }
        assertEquals("/live/1.ts", provider.takeRequest(20, TimeUnit.SECONDS)?.path)
        runBlocking { h.stop(StopReason.STOPPED) }
        assertSame(real, runBlocking { pending.await() })
        Thread.sleep(700)
        assertEquals(1, provider.requestCount)
        assertEquals(0, upstream.connectionPool.connectionCount())
    }

    @Test fun stopRacingTheBusyRetryNeverStartsAProviderRequestAfterwards() {
        provider.enqueue(MockResponse().setResponseCode(503))
        provider.enqueue(liveResponse())
        val h = newHost()
        h.start()
        var probes = 0
        var stopping: Job? = null
        // The stop finds nothing to cancel: it lands just before the retry's call is published.
        h.beforePublish = {
            if (++probes == 2) {
                stopping = hostScope.launch { h.stop(StopReason.STOPPED) }
                while (h.active.value != null) Thread.sleep(5)
                Thread.sleep(200)
            }
        }
        val real = stream("/live/1.ts")
        assertSame(real, runBlocking { h.open(real, hls = false) })
        runBlocking { stopping?.join() }
        Thread.sleep(300)
        assertEquals(2, probes)
        assertEquals(1, provider.requestCount)
        assertEquals(0, upstream.connectionPool.connectionCount())
    }

    @Test fun followersReopeningAfterAPauseReplaceTheirOwnTsReaders() {
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        val loop = open(h, stream("/live/1.ts"))
        val path = local("/c/${session.token}/1.ts")
        val localReader = get(loop.url)
        readExactly(localReader, PACKET.toLong())
        var a = follower("a", session).also { it.next() }
        var b = follower("b", session).also { it.next() }
        val before = listOf("a", "b").map { get("$path?id=$it").also { r -> readExactly(r, PACKET.toLong()) } }
        // Paused: both followers' event sockets drop, but their media connections are left behind.
        a.close()
        b.close()
        a = follower("a", session).also { it.next() }
        b = follower("b", session).also { it.next() }
        val after = listOf("a", "b").map { get("$path?id=$it") }
        assertEquals(listOf(200, 200), after.map { it.code })
        after.forEach { readExactly(it, PACKET.toLong()); it.close() }
        before.forEach { it.close() }
        a.close()
        b.close()
        localReader.close()
    }

    @Test fun archiveUpstreamWaitsForLocalReader() {
        val total = 40_000
        provider.enqueue(tsResponse(total))
        val h = newHost(capacity = 512 * 1024)
        h.start()
        val loop = open(h, stream("/archive/1.ts"), archive = true)
        // About 7.5 MB against a 512 KB ring: without waiting for the casting TV's player the start
        // would be long gone by the time it connects.
        val got = get(loop.url).use { counters(it.body.bytes()) }
        assertEquals((0 until total).toList(), got)
    }

    @Test fun localRangedReconnectContinuesWhereItWas() {
        val total = 40_000
        provider.enqueue(tsResponse(total))
        val h = newHost(capacity = 8 * 1024 * 1024)
        h.start()
        val loop = open(h, stream("/archive/1.ts"), archive = true)
        get(loop.url).use { readExactly(it, PACKET * 3_000L) }
        get(loop.url, range = "bytes=${PACKET * 3_000L}-").use {
            assertEquals(206, it.code)
            assertEquals(3_000, counters(readExactly(it, PACKET * 10L)).first())
        }
    }

    @Test fun tsBodyLabelledAsPlaylistIsRelayedAsTs() {
        provider.enqueue(tsResponse(10).setHeader("Content-Type", "application/vnd.apple.mpegurl"))
        val h = newHost()
        val session = h.start()
        assertTrue(open(h, stream("/live/1.m3u8"), hls = true).url.startsWith(local("/c/${session.token}/1.ts")))
    }

    @Test fun errorPageOrEmptyAnswerIsAProviderFailure() {
        provider.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Not allowed</html>"))
        provider.enqueue(MockResponse().setBody(""))
        provider.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("{\"error\":1}"))
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            repeat(3) {
                val loop = open(h, stream("/live/1.ts"))
                assertEquals(502, code(loop.url))
                assertEquals(CastEvent.Idle(session.castId), f.next())
            }
        }
        assertEquals(0, upstream.connectionPool.connectionCount())
    }

    // Cancellation and switching races

    @Test fun openCancelledAfterTheProbeClosesItOffTheCallersThread() {
        provider.enqueue(liveResponse())
        val h = newHost()
        h.start()
        val callerThread = Executors.newSingleThreadExecutor { Thread(it, "caller") }
        val caller = callerThread.asCoroutineDispatcher()
        val probed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        // Occupy the caller's thread before the open can resume there, then cancel it: the open wakes
        // up cancelled with a live probe in hand.
        h.onProbed = {
            callerThread.execute { resume.await() }
            probed.countDown()
        }
        val job = hostScope.launch(caller) { h.open(stream("/live/1.ts"), hls = false) }
        assertTrue(probed.await(20, TimeUnit.SECONDS))
        job.cancel()
        resume.countDown()
        runBlocking { job.join() }
        assertTrue(upstreamLog.toString(), upstreamLog.contains("end /live/1.ts"))
        assertTrue(closeThreads.isNotEmpty())
        assertFalse(closeThreads.toString(), closeThreads.any { it.startsWith("caller") })
        assertEquals(0, upstream.connectionPool.connectionCount())
        callerThread.shutdown()
    }

    @Test fun openCancelledWhileWaitingForTheProviderLeavesNothingConnected() {
        provider.enqueue(liveResponse().setHeadersDelay(30, TimeUnit.SECONDS))
        provider.enqueue(liveResponse())
        val h = newHost()
        val session = h.start()
        follower("a", session).use { f ->
            f.next()
            val job = hostScope.launch { h.open(stream("/slow.ts"), hls = false) }
            assertEquals("/slow.ts", provider.takeRequest(20, TimeUnit.SECONDS)?.path)
            runBlocking { job.cancelAndJoin() }
            assertEquals(CastEvent.Idle(session.castId), f.next())
            assertTrue(upstreamLog.contains("end /slow.ts"))
            assertEquals(0, upstream.connectionPool.connectionCount())
            // The next open goes ahead normally.
            open(h, stream("/live/2.ts"))
            assertEquals(CastEvent.Playing(session.castId, 2, "/c/${session.token}/2.ts", false), f.next())
        }
        assertOneAtATime()
    }

    @Test fun stopWithAProbeInFlightGivesTheRealStreamPromptly() {
        provider.enqueue(liveResponse().setHeadersDelay(30, TimeUnit.SECONDS))
        val h = newHost()
        h.start()
        val real = stream("/slow.ts")
        val pending = hostScope.async { h.open(real, hls = false) }
        assertEquals("/slow.ts", provider.takeRequest(20, TimeUnit.SECONDS)?.path)
        val started = System.nanoTime()
        runBlocking { h.stop(StopReason.STOPPED) }
        assertSame(real, runBlocking { pending.await() })
        // Far less than the provider's 30 s: stop doesn't wait for the answer.
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15))
        assertTrue(upstreamLog.contains("end /slow.ts"))
        assertEquals(0, upstream.connectionPool.connectionCount())
    }

    @Test fun switchingFromHlsReleasesItsRequestBeforeTheNextOpen() {
        provider.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/hls/live.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n")
                "/hls/seg1.ts" -> MockResponse().setBody("late").setHeadersDelay(30, TimeUnit.SECONDS)
                "/live.ts" -> tsResponse(10)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val h = newHost()
        val session = h.start()
        open(h, stream("/hls/live.m3u8"), hls = true)
        get(local("/c/${session.token}/1/index.m3u8")).use { assertEquals(200, it.code) }
        // A segment request is still waiting on the provider when the casting TV switches.
        val segment = hostScope.async(Dispatchers.IO) { code(local("/c/${session.token}/1/s-1")) }
        assertEquals("/hls/live.m3u8", provider.takeRequest(20, TimeUnit.SECONDS)?.path)
        assertEquals("/hls/seg1.ts", provider.takeRequest(20, TimeUnit.SECONDS)?.path)
        open(h, stream("/live.ts"))
        val log = synchronized(upstreamLog) { upstreamLog.toList() }
        assertTrue(log.toString(), log.indexOf("end /hls/seg1.ts") in 0 until log.indexOf("start /live.ts"))
        assertOneAtATime()
        assertNotEquals(200, runBlocking { segment.await() })
    }

    // Fixtures

    private fun hlsProvider() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/hls/live.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                    "#EXTINF:6.0,\nseg1.ts?token=s3cret\n#EXTINF:6.0,\nseg2.ts?token=s3cret\n",
            )
            "/hls/seg1.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody("SEGMENT-ONE")
            "/hls/seg2.ts" -> MockResponse().setResponseCode(503)
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** Calls starting and ending, plus the thread every response body was finished or failed on. */
    private class UpstreamLog(private val log: MutableList<String>, private val threads: MutableList<String>) : EventListener() {
        override fun callStart(call: Call) {
            log += "start ${call.request().url.encodedPath}"
        }

        override fun callEnd(call: Call) {
            log += "end ${call.request().url.encodedPath}"
            threads += Thread.currentThread().name
        }

        override fun callFailed(call: Call, ioe: IOException) {
            log += "end ${call.request().url.encodedPath}"
            threads += Thread.currentThread().name
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            threads += Thread.currentThread().name
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            threads += Thread.currentThread().name
        }
    }

    /** Runs everything through [inner] and marks those runs, standing in for the main thread. */
    private class MarkedDispatcher(private val inner: CoroutineDispatcher, private val mark: ThreadLocal<Boolean>) :
        CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(
            context,
            Runnable {
                mark.set(true)
                try {
                    block.run()
                } finally {
                    mark.set(false)
                }
            },
        )
    }

    companion object {
        private const val PACKET = 188
        private const val APP_UA = "Loophole/test"
        private const val STARTED_AT = 1_731_300_000_000L

        /** TS packets on one PID, each carrying its index so a reader's bytes can be checked. */
        fun ts(packets: Int, from: Int = 0): ByteArray {
            val out = ByteArray(packets * PACKET) { 0xFF.toByte() }
            for (i in 0 until packets) {
                val p = i * PACKET
                out[p] = 0x47
                out[p + 1] = 0x01
                out[p + 2] = 0x00
                out[p + 3] = (0x10 or (i and 0x0F)).toByte()
                ByteBuffer.wrap(out, p + 4, 4).putInt(from + i)
            }
            return out
        }

        fun counters(bytes: ByteArray): List<Int> {
            assertEquals("whole packets", 0, bytes.size % PACKET)
            return (0 until bytes.size / PACKET).map { k ->
                assertEquals(0x47.toByte(), bytes[k * PACKET])
                ByteBuffer.wrap(bytes, k * PACKET + 4, 4).int
            }
        }
    }
}
