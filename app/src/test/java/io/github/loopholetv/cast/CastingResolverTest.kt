package io.github.loopholetv.cast

import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamResolver
import io.github.loopholetv.player.catchup.ArchiveRequest
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastingResolverTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provider = MockWebServer()
    private val upstream = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    private val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    private val server = LocalHttpServer(scope, maxPerRemote = 16)
    private var port = 0
    private lateinit var host: CastHost
    private lateinit var inner: FakeResolver
    private lateinit var resolver: CastingResolver

    private val channel = ChannelEntity(
        id = 7, playlistId = 1, key = "k7", number = 7, name = "Seven", streamUrl = "http://x/7",
        logo = null, groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = null,
    )
    private val request = ArchiveRequest(Instant.parse("2026-11-10T20:00:00Z"), Instant.parse("2026-11-10T21:00:00Z"))
    private val now = Instant.parse("2026-11-11T08:00:00Z")

    private class FakeResolver(var url: String) : StreamResolver {
        var resolves = 0
        var archives = 0
        var keepAlives = 0
        var remintChecks = 0
        var archiveUrl: String? = null
        var gate: CompletableDeferred<Unit>? = null
        val headers = mapOf("Referer" to "http://portal.example.test/")

        val entered = CompletableDeferred<Unit>()
        var onResolve: (() -> Unit)? = null
        var failure: Exception? = null

        override suspend fun resolve(channel: ChannelEntity): ResolvedStream {
            resolves++
            entered.complete(Unit)
            onResolve?.invoke()
            gate?.await()
            failure?.let { throw it }
            return ResolvedStream(url, "ProviderAgent/9", headers)
        }

        override suspend fun keepAlive(channel: ChannelEntity) {
            keepAlives++
        }

        override suspend fun canRemint(channel: ChannelEntity): Boolean {
            remintChecks++
            return true
        }

        override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? {
            archives++
            onResolve?.invoke()
            return archiveUrl?.let { ResolvedStream(it, "ProviderAgent/9", headers) }
        }
    }

    @Before fun setUp() {
        provider.start()
        port = server.start(listOf(loopback))
        host = CastHost(
            scope = scope,
            server = server,
            upstreamHttp = upstream,
            clock = { 0L },
            random = SecureRandom(),
            userAgent = "Loophole/test",
            tsCapacityBytes = 512 * 1024,
        )
        inner = FakeResolver(provider.url("/live/7").toString())
        resolver = CastingResolver(inner, host)
    }

    @After fun tearDown() {
        runBlocking { host.stop(StopReason.STOPPED) }
        server.stop()
        provider.shutdown()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        upstream.dispatcher.executorService.shutdown()
    }

    private fun ts(packets: Int) = MockResponse().setBody(Buffer().write(CastHostTest.ts(packets)))

    private fun live() = ts(40_000).throttleBody(188 * 100L, 10, TimeUnit.MILLISECONDS)

    @Test fun resolverDelegatesWhenNotCasting() = runBlocking {
        inner.archiveUrl = provider.url("/archive/7.ts").toString()
        val live = resolver.resolve(channel)
        assertEquals(ResolvedStream(inner.url, "ProviderAgent/9", inner.headers), live)
        assertEquals(inner.archiveUrl, resolver.resolveArchive(channel, request, now)?.url)
        inner.archiveUrl = null
        assertNull(resolver.resolveArchive(channel, request, now))
        resolver.keepAlive(channel)
        assertTrue(resolver.canRemint(channel))
        assertEquals(1, inner.keepAlives)
        assertEquals(1, inner.remintChecks)
        assertEquals(0, provider.requestCount)
    }

    @Test fun resolverRelaysWhenCasting() = runBlocking {
        provider.enqueue(live())
        val session = host.start()
        val stream = resolver.resolve(channel)
        assertTrue(stream.url.startsWith("http://127.0.0.1:$port/c/${session.token}/1.ts"))
        assertEquals(emptyMap<String, String>(), stream.headers)
        assertEquals("Loophole/test", stream.userAgent)
        assertEquals(1, provider.requestCount)
        // While casting, the keep-alive and re-mint questions still go to the real resolver.
        resolver.keepAlive(channel)
        assertTrue(resolver.canRemint(channel))
        assertEquals(1, inner.keepAlives)
    }

    @Test fun hlsStreamGoesThroughHlsRelay() = runBlocking {
        inner.url = provider.url("/live/7.m3u8").toString()
        // Busy, and busy again when asked once more.
        repeat(2) { provider.enqueue(MockResponse().setResponseCode(503)) }
        val session = host.start()
        // The upstream refused before anything could be sniffed: the URL decides the shape.
        val refused = resolver.resolve(channel)
        assertEquals("http://127.0.0.1:$port/c/${session.token}/1/index.m3u8", refused.url)
        provider.enqueue(
            MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n#EXT-X-ENDLIST\n"),
        )
        val ok = resolver.resolve(channel)
        assertEquals("http://127.0.0.1:$port/c/${session.token}/2/index.m3u8", ok.url)
        val playlist = client.newCall(Request.Builder().url(ok.url).build()).execute().use { it.body.string() }
        assertTrue(playlist, playlist.contains("s-1"))
        // The link was requested once for the probe and the playlist came from it.
        assertEquals(3, provider.requestCount)
    }

    @Test fun theRelayLetsGoOfTheProviderBeforeTheNextResolve() = runBlocking {
        provider.enqueue(live())
        provider.enqueue(live())
        provider.enqueue(live())
        inner.archiveUrl = provider.url("/archive/7.ts").toString()
        host.start()
        val first = resolver.resolve(channel)
        client.newCall(Request.Builder().url(first.url).build()).execute().use { it.body.source().require(188L * 10) }
        assertEquals(1, upstream.connectionPool.connectionCount())
        val connectedDuringResolve = mutableListOf<Int>()
        inner.onResolve = { connectedDuringResolve += upstream.connectionPool.connectionCount() }
        resolver.resolve(channel)
        resolver.resolveArchive(channel, request, now)
        assertEquals(listOf(0, 0), connectedDuringResolve)
        assertEquals(3, provider.requestCount)
    }

    @Test fun aFailedResolveTellsFollowersTheCastIsIdle() = runBlocking {
        provider.enqueue(live())
        val session = host.start()
        val events = client.newCall(
            Request.Builder().url("http://127.0.0.1:$port/c/${session.token}/events?id=a").build(),
        ).execute()
        val lines = events.body.source()
        assertEquals(CastEvent.Idle(session.castId), CastJson.event(lines.readUtf8Line()!!))
        resolver.resolve(channel)
        assertTrue(CastJson.event(lines.readUtf8Line()!!) is CastEvent.Playing)
        inner.failure = java.io.IOException("portal said no")
        assertTrue(runCatching { resolver.resolve(channel) }.isFailure)
        assertEquals(CastEvent.Idle(session.castId), CastJson.event(lines.readUtf8Line()!!))
        // An archive the channel can't play leaves the cast idle as well.
        inner.failure = null
        inner.archiveUrl = null
        assertNull(resolver.resolveArchive(channel, request, now))
        assertEquals(CastEvent.Idle(session.castId), CastJson.event(lines.readUtf8Line()!!))
        events.close()
    }

    @Test fun archiveIsRelayedAtThePaceOfTheLocalPlayer() = runBlocking {
        val total = 20_000
        provider.enqueue(ts(total))
        inner.archiveUrl = provider.url("/archive/7.ts").toString()
        val session = host.start()
        val stream = resolver.resolveArchive(channel, request, now)!!
        assertTrue(stream.url.startsWith("http://127.0.0.1:$port/c/${session.token}/1.ts"))
        val body = client.newCall(Request.Builder().url(stream.url).build()).execute().use { it.body.bytes() }
        assertEquals((0 until total).toList(), CastHostTest.counters(body))
    }

    @Test fun castStoppedDuringResolveGetsTheRealStream() = runBlocking {
        host.start()
        val gate = CompletableDeferred<Unit>()
        inner.gate = gate
        val pending = async(Dispatchers.IO) { resolver.resolve(channel) }
        inner.entered.await()
        host.stop(StopReason.STOPPED)
        gate.complete(Unit)
        val stream = pending.await()
        assertEquals(inner.url, stream.url)
        assertEquals(0, provider.requestCount)
    }

    @Test fun archiveWithNoStreamStaysNull() = runBlocking {
        host.start()
        assertNull(resolver.resolveArchive(channel, request, now))
        assertEquals(1, inner.archives)
        assertEquals(0, provider.requestCount)
        assertEquals(0, host.active.value?.epoch)
    }
}
