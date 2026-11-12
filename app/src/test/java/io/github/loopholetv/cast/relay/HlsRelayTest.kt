package io.github.loopholetv.cast.relay

import io.github.loopholetv.player.ResolvedStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HlsRelayTest {
    private lateinit var server: MockWebServer
    private val bodies = HashMap<String, String>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private var now = 0L
    private val relays = mutableListOf<HlsRelay>()
    private val redirects = HashMap<String, String>()
    private val statuses = HashMap<String, Int>()
    private val headersDelayMs = HashMap<String, Long>()
    private val bodyDelayMs = HashMap<String, Long>()

    // Counts whole calls (headers and body), so a body read outside the lock would overlap.
    private val listener = object : okhttp3.EventListener() {
        override fun callStart(call: okhttp3.Call) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        }
        override fun callEnd(call: okhttp3.Call) { inFlight.decrementAndGet() }
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { inFlight.decrementAndGet() }
    }

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:100
        #EXT-X-KEY:METHOD=AES-128,URI="keys/a.key",IV=0x01
        #EXT-X-MAP:URI="init.mp4"
        #EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z
        #EXTINF:6.0,
        seg100.ts?token=secret
        #EXT-X-DISCONTINUITY
        #EXTINF:6.0,
        seg101.ts
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                try {
                    Thread.sleep(20)
                    val path = request.path!!.substringBefore('?')
                    statuses[path]?.let { return MockResponse().setResponseCode(it) }
                    redirects[path]?.let { return MockResponse().setResponseCode(302).setHeader("Location", it) }
                    val body = bodies[path] ?: return MockResponse().setResponseCode(404)
                    val response = MockResponse().setBody(body)
                    headersDelayMs[path]?.let { response.setHeadersDelay(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    bodyDelayMs[path]?.let { response.setBodyDelay(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    return response
                } finally {
                }
            }
        }
        server.start()
        bodies["/live/index.m3u8"] = media
        bodies["/live/seg100.ts"] = "segment-100"
        bodies["/live/seg101.ts"] = "segment-101"
        bodies["/live/init.mp4"] = "init"
        bodies["/live/keys/a.key"] = "0123456789abcdef"
    }

    @After
    fun tearDown() {
        relays.forEach { it.close() }
        server.shutdown()
    }

    private fun relay(
        url: String = server.url("/live/index.m3u8").toString(),
        cacheBytes: Long = 48L * 1024 * 1024,
        firstIndex: HlsRelay.Prefetched? = null,
    ): HlsRelay {
        val upstream = ResolvedStream(url, "TestAgent/1.0", mapOf("X-Test" to "yes"))
        val client = OkHttpClient.Builder().eventListener(listener).build()
        return HlsRelay(client, upstream, { now }, cacheBytes, firstIndex).also { relays += it }
    }

    @Test
    fun prefetchedIndexIsServedWithoutAskingTheProviderAgain() = runBlocking {
        // The prefetched copy came from a redirect: its relative segments resolve against the edge.
        val fetched = window(5, 2).toByteArray()
        bodies["/edge/seg5.ts"] = "five"
        val r = relay(firstIndex = HlsRelay.Prefetched(fetched, server.url("/edge/live.m3u8").toString()))
        val first = text(r.pl())
        assertEquals(2, segIds(first).size)
        assertEquals(0, requestCount("/live/index.m3u8"))
        assertEquals("five", String(r.get(segIds(first)[0])!!.second))
        // Only the first answer is prefetched: a refresh asks the provider as usual.
        bodies["/live/index.m3u8"] = window(6, 2)
        now = 60_000
        r.pl()
        assertEquals(1, requestCount("/live/index.m3u8"))
        assertFalse(HlsRelay.Prefetched(fetched, "http://x.test/?token=s3cret").toString().contains("s3cret"))
    }

    private fun vod(count: Int, token: String = ""): String = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        for (i in 0 until count) append("#EXTINF:10,\nseg$i.ts$token\n")
        append("#EXT-X-ENDLIST\n")
    }

    private fun window(first: Int, count: Int, token: String = ""): String = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        for (i in first until first + count) append("#EXTINF:10,\nseg$i.ts$token\n")
    }

    private fun segIds(playlist: String) = playlist.lines().filter { it.startsWith("s-") }

    private fun requestCount(path: String) = seen.count { it.path!!.substringBefore('?') == path }

    private fun text(bytes: ByteArray?) = String(bytes!!, Charsets.UTF_8)

    private suspend fun HlsRelay.pl(id: String = "index"): ByteArray? = (playlist(id) as? HlsResult.Ok)?.bytes

    private suspend fun HlsRelay.get(id: String): Pair<String, ByteArray>? =
        (item(id) as? HlsResult.Ok)?.let { it.contentType to it.bytes }

    @Test
    fun rewritesSegmentsMapsAndKeysToIssuedIds() = runBlocking {
        val out = text(relay().pl())
        assertFalse(out, out.contains("example.test"))
        assertFalse(out, out.contains(server.hostName))
        assertFalse(out, out.contains(server.port.toString()))
        assertFalse(out, out.contains("secret"))
        assertFalse(out, out.contains("seg100"))
        val lines = out.lines()
        assertTrue(lines.contains("s-1"))
        assertTrue(lines.contains("s-2"))
        assertTrue(out, out.contains("URI=\"m-1\""))
        assertTrue(out, out.contains("URI=\"k-1\",IV=0x01"))
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:100"))
        assertTrue(out.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(out.contains("#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z"))
    }

    @Test
    fun issuedItemsAreServed() = runBlocking {
        val r = relay()
        r.pl()
        assertEquals("segment-100", String(r.get("s-1")!!.second))
        assertEquals("init", String(r.get("m-1")!!.second))
        assertEquals("0123456789abcdef", String(r.get("k-1")!!.second))
    }

    @Test
    fun segmentFetchedOnceForThreeClients() = runBlocking {
        val r = relay()
        r.pl()
        val before = seen.size
        val results = (1..3).map { async { r.get("s-1") } }.awaitAll()
        assertTrue(results.all { it != null && String(it.second) == "segment-100" })
        assertEquals(1, seen.size - before)
        r.get("s-1")
        assertEquals(1, seen.size - before)
    }

    @Test
    fun playlistCachedForHalfTargetDuration() = runBlocking {
        bodies["/live/index.m3u8"] = media.replace("TARGETDURATION:6", "TARGETDURATION:10")
        val r = relay()
        r.pl()
        now = 4_900
        r.pl()
        assertEquals(1, seen.size)
        now = 5_000
        r.pl()
        assertEquals(2, seen.size)
    }

    @Test
    fun upstreamRequestsAreSerial() = runBlocking {
        val r = relay()
        r.pl()
        val all = listOf(
            async { r.get("s-1") }, async { r.get("s-2") },
            async { r.get("m-1") }, async { r.get("k-1") },
            async { r.pl() },
        )
        all.awaitAll()
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun unknownSegmentIdIs404() = runBlocking {
        val r = relay()
        assertNull(r.get("s-1"))
        r.pl()
        assertNull(r.get("s-99"))
        assertNull(r.get("../etc/passwd"))
        assertNull(r.get("http://example.test/x"))
        assertNull(r.pl("p-9.m3u8"))
        assertNull(r.pl(server.url("/live/index.m3u8").toString()))
        assertNotNull(r.get("s-1"))
    }

    @Test
    fun byteRangeUnsupported() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n#EXT-X-BYTERANGE:100@0\nall.ts\n"
        assertNull(relay().pl())
    }

    @Test
    fun upstreamFailureIsNull() = runBlocking {
        bodies.remove("/live/index.m3u8")
        val r = relay()
        assertNull(r.pl())
        bodies["/live/index.m3u8"] = media
        assertNotNull(r.pl())
    }

    @Test
    fun sendsStreamUserAgentAndHeaders() = runBlocking {
        val r = relay()
        r.pl()
        r.get("s-1")
        assertEquals(2, seen.size)
        seen.forEach {
            assertEquals("TestAgent/1.0", it.getHeader("User-Agent"))
            assertEquals("yes", it.getHeader("X-Test"))
        }
    }

    @Test
    fun onlyAllowlistedTagsSurviveAndNoUrlLeaks() = runBlocking {
        bodies["/live/index.m3u8"] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:5
            # comment http://provider.example:8080/c?token=SECRET1
            #EXTVLCOPT:http-referrer=http://provider.example:8080/ref?token=SECRET2
            #EXT-X-SESSION-DATA:DATA-ID="x",VALUE="http://provider.example:8080/sd"
            #EXT-X-CUE-OUT:DURATION=30,X-URL=http://provider.example:8080/cue?t=SECRET3
            #EXT-X-DATERANGE:ID="a",X-ASSET-URI="http://provider.example/a?t=SECRET4"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="http://provider.example/k?t=SECRET5"
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="p.ts?t=SECRET6"
            #EXT-X-CONTENT-STEERING:SERVER-URI="http://provider.example/s?t=SECRET7"
            #EXTINF:6.0,http://provider.example:8080/title?t=SECRET8
            seg.ts?t=SECRET9
        """.trimIndent()
        val out = text(relay().pl())
        for (needle in listOf("provider.example", "SECRET", "VLC", "CUE", "DATERANGE", "SESSION", "STEERING", "PRELOAD", "comment", "title")) {
            assertFalse("$needle in $out", out.contains(needle))
        }
        assertTrue(out.contains("#EXTINF:6.0,\n"))
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:5"))
    }

    @Test
    fun variableSubstitutionIsUnsupported() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-DEFINE:NAME=\"t\",VALUE=\"x\"\n#EXTINF:6,\nseg100.ts\n"
        assertNull(relay().pl())
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg100.ts?{\$t}\n"
        assertNull(relay().pl())
    }

    @Test
    fun bomIsStrippedAndHeaderRequired() = runBlocking {
        bodies["/live/index.m3u8"] = "${0xFEFF.toChar()}$media"
        assertTrue(text(relay().pl()).startsWith("#EXTM3U\n"))
        bodies["/live/index.m3u8"] = "not a playlist\nseg100.ts\n"
        assertNull(relay().pl())
    }

    @Test
    fun unresolvableVariantDropsItsStreamInf() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nrtmp://provider.example/x\n#EXT-X-STREAM-INF:BANDWIDTH=2\nlow.m3u8\n"
        val out = text(relay().pl())
        assertFalse(out.contains("BANDWIDTH=1"))
        assertEquals(listOf("#EXT-X-STREAM-INF:BANDWIDTH=2", "p-1.m3u8"), out.lines().filter { it.isNotEmpty() }.drop(1))
    }

    @Test
    fun nonHttpKeyIsUnsupported() = runBlocking {
        bodies["/live/index.m3u8"] = media.replace("keys/a.key", "skd://abc")
        assertNull(relay().pl())
    }

    @Test
    fun longVodPlaylistWorksEndToEnd() = runBlocking {
        bodies["/live/index.m3u8"] = vod(400)
        for (i in 0 until 400) bodies["/live/seg$i.ts"] = "data-$i"
        val r = relay()
        val ids = segIds(text(r.pl()))
        assertEquals(400, ids.toSet().size)
        assertEquals("data-0", String(r.get(ids.first())!!.second))
        assertEquals("data-399", String(r.get(ids.last())!!.second))
    }

    @Test
    fun idsStayStableAcrossSlidingWindowAndRotatingTokens() = runBlocking {
        bodies["/live/index.m3u8"] = window(100, 300, "?t=a")
        val r = relay()
        val first = segIds(text(r.pl()))
        bodies["/live/index.m3u8"] = window(102, 300, "?t=b")
        now = 10_000
        val second = segIds(text(r.pl()))
        assertEquals(first.drop(2), second.take(298))
        assertEquals(300, second.toSet().size)
        bodies["/live/seg102.ts"] = "x"
        assertNotNull(r.get(first[2]))
        assertTrue(seen.last().path!!.endsWith("?t=b"))
    }

    @Test
    fun segmentCacheKeepsAtMostTwelve() = runBlocking {
        bodies["/live/index.m3u8"] = vod(14)
        for (i in 0 until 14) bodies["/live/seg$i.ts"] = "d$i"
        val r = relay()
        val ids = segIds(text(r.pl()))
        ids.forEach { r.get(it) }
        r.get(ids[13])
        assertEquals(1, requestCount("/live/seg13.ts"))
        r.get(ids[0])
        assertEquals(2, requestCount("/live/seg0.ts"))
        r.get(ids[3])
        assertEquals(1, requestCount("/live/seg3.ts"))
    }

    @Test
    fun segmentCacheHonoursByteBudget() = runBlocking {
        bodies["/live/index.m3u8"] = vod(4)
        for (i in 0 until 3) bodies["/live/seg$i.ts"] = "0123456789"
        bodies["/live/seg3.ts"] = "x".repeat(60)
        val r = relay(cacheBytes = 25)
        val ids = segIds(text(r.pl()))
        for (i in 0 until 3) r.get(ids[i])
        r.get(ids[0])
        assertEquals(2, requestCount("/live/seg0.ts"))
        r.get(ids[2])
        assertEquals(1, requestCount("/live/seg2.ts"))
        assertEquals(60, r.get(ids[3])!!.second.size)
        r.get(ids[3])
        assertEquals(2, requestCount("/live/seg3.ts"))
    }

    @Test
    fun cancelledWaiterDoesNotPoisonOthers() = runBlocking {
        bodyDelayMs["/live/seg100.ts"] = 300
        val r = relay()
        r.pl()
        val first = async { r.get("s-1") }
        val second = async { r.get("s-1") }
        kotlinx.coroutines.delay(100)
        first.cancel()
        assertEquals("segment-100", String(second.await()!!.second))
        assertEquals(1, requestCount("/live/seg100.ts"))
    }

    @Test
    fun cachedPlaylistDoesNotWaitForTheUpstreamLock() = runBlocking {
        bodyDelayMs["/live/seg100.ts"] = 1_500
        val r = relay()
        r.pl()
        val slow = async(kotlinx.coroutines.Dispatchers.IO) { r.get("s-1") }
        kotlinx.coroutines.delay(300)
        val started = System.nanoTime()
        assertNotNull(r.pl())
        assertTrue((System.nanoTime() - started) < 500_000_000L)
        slow.cancel()
    }

    @Test
    fun relativeUrisResolveAgainstTheRedirectedUrl() = runBlocking {
        redirects["/start.m3u8"] = "/other/index.m3u8"
        bodies["/other/index.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nrel.ts\n"
        bodies["/other/rel.ts"] = "redirected"
        val r = relay(server.url("/start.m3u8").toString())
        r.pl()
        assertEquals("redirected", String(r.get("s-1")!!.second))
    }

    @Test
    fun masterServesOneVariantWithItsRenditions() = runBlocking {
        bodies["/live/index.m3u8"] = """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",LANGUAGE="en",URI="audio/en.m3u8?token=SECRET"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",NAME="xx",URI="audio/xx.m3u8"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100,URI="iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000,RESOLUTION=1280x720,AUDIO="aud"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000,RESOLUTION=1920x1080,AUDIO="aud"
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000,RESOLUTION=3840x2160,AUDIO="aud"
            high/index.m3u8
        """.trimIndent()
        val out = text(relay().pl())
        assertTrue(out, out.contains("BANDWIDTH=5000"))
        assertFalse(out, out.contains("BANDWIDTH=2000"))
        assertFalse(out, out.contains("BANDWIDTH=9000"))
        assertFalse(out, out.contains("I-FRAME"))
        assertFalse(out, out.contains("GROUP-ID=\"other\""))
        assertTrue(out, out.contains("GROUP-ID=\"g-1\""))
        assertFalse(out, out.contains("\"aud\""))
        assertEquals(1, out.lines().count { it.startsWith("p-") && !it.contains("URI") })
        assertFalse(out, out.contains("SECRET"))
        assertFalse(out, out.contains(server.hostName))
    }

    @Test
    fun masterWithOnlyHugeVariantsPicksTheHighest() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=7000,RESOLUTION=3840x2160\na.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000,RESOLUTION=7680x4320\nb.m3u8\n"
        bodies["/live/b.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nb1.ts\n"
        val r = relay()
        val out = text(r.pl())
        assertTrue(out, out.contains("BANDWIDTH=9000"))
        assertNotNull(r.pl("p-1.m3u8"))
        assertEquals("/live/b.m3u8", seen.last().path)
    }

    @Test
    fun uriMatchingIsAnchoredAndUnknownAttributesAreDropped() = runBlocking {
        bodies["/live/index.m3u8"] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,X-URI="http://provider.example/k?t=SECRET1",URI="keys/a.key",X-TOKEN=SECRET2,IV=0x01
            #EXT-X-MAP:X-URI="http://provider.example/m?t=SECRET3",URI="init.mp4",X-TOKEN=SECRET4
            #EXTINF:6,
            seg100.ts
        """.trimIndent()
        val out = text(relay().pl())
        assertTrue(out, out.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"k-1\",IV=0x01\n"))
        assertTrue(out, out.contains("#EXT-X-MAP:URI=\"m-1\"\n"))
        assertFalse(out, out.contains("SECRET"))
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,X-TOKEN=SECRET5,CODECS=\"a,b\"\nlow.m3u8\n"
        val master = text(relay().pl())
        assertTrue(master, master.contains("#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"a,b\"\n"))
        assertFalse(master, master.contains("SECRET"))
    }

    @Test
    fun brokenKeyMapOrVariantMakesThePlaylistUnsupported() = runBlocking {
        bodies["/live/index.m3u8"] = media.replace("METHOD=AES-128", "METHOD=AES-128,KEYFORMAT=\"http://provider.example/f\"")
        assertEquals(HlsResult.Upstream(502), relay().playlist())
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"http://provider.example/c\"\nlow.m3u8\n"
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\np-1.m3u8\n", text(relay().pl()))
    }

    @Test
    fun mapWithByteRangeIsUnsupported() = runBlocking {
        bodies["/live/index.m3u8"] = media.replace("URI=\"init.mp4\"", "URI=\"init.mp4\",BYTERANGE=\"720@0\"")
        assertNull(relay().pl())
    }

    @Test
    fun segmentIdsOutliveAnUnchangedRefresh() = runBlocking {
        bodies["/live/index.m3u8"] = window(100, 2)
        for (i in 100..105) bodies["/live/seg$i.ts"] = "d$i"
        val r = relay()
        val first = segIds(text(r.pl()))
        bodies["/live/index.m3u8"] = window(101, 2)
        now = 10_000
        r.pl()
        now = 15_000
        r.pl()
        now = 19_000
        assertEquals("d100", String(r.get(first[0])!!.second))
    }

    @Test
    fun segmentsStopBeingServedAfterTheGracePeriod() = runBlocking {
        bodies["/live/index.m3u8"] = window(100, 2)
        for (i in 100..105) bodies["/live/seg$i.ts"] = "d$i"
        val r = relay()
        val first = segIds(text(r.pl()))
        bodies["/live/index.m3u8"] = window(102, 2)
        now = 10_000
        r.pl()
        assertNotNull(r.get(first[0]))
        now = 30_000
        r.pl()
        assertNull(r.get(first[0]))
        assertEquals(HlsResult.NotFound, r.item(first[0]))
    }

    @Test
    fun rotatingPathTokensKeepIdsAndPlaylistIdentity() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\ntok1abcdefghijklmnop12/v.m3u8\n"
        bodies["/live/tok1abcdefghijklmnop12/v.m3u8"] =
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:10,\nseg7.ts\n"
        bodies["/live/tok2abcdefghijklmnop34/v.m3u8"] =
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:10,\nseg7.ts\n"
        bodies["/live/tok1abcdefghijklmnop12/seg7.ts"] = "seven"
        val r = relay()
        val master = text(r.pl())
        val media1 = text(r.pl("p-1.m3u8"))
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\ntok2abcdefghijklmnop34/v.m3u8\n"
        now = 40_000
        assertEquals(master, text(r.pl()))
        val media2 = text(r.pl("p-1.m3u8"))
        assertEquals(media1, media2)
        assertEquals(1, seen.count { it.path == "/live/tok2abcdefghijklmnop34/v.m3u8" })
    }

    @Test
    fun upstreamStatusIsPassedThrough() = runBlocking {
        val r = relay()
        r.pl()
        statuses["/live/seg100.ts"] = 503
        assertEquals(HlsResult.Upstream(503), r.item("s-1"))
        statuses.remove("/live/seg100.ts")
        assertNotNull(r.get("s-1"))
        statuses["/live/index.m3u8"] = 429
        now = 100_000
        assertEquals(HlsResult.Upstream(429), r.playlist())
        assertEquals(HlsResult.NotFound, r.item("s-77"))
        assertEquals(HlsResult.NotFound, r.playlist("p-9.m3u8"))
    }

    @Test
    fun entryPlaylistAcceptsBothSpellings() = runBlocking {
        val r = relay()
        val a = r.pl("index")
        val b = r.pl("index.m3u8")
        assertNotNull(a)
        assertEquals(text(a), text(b))
        assertEquals(1, seen.size)
    }

    @Test
    fun releaseWaitsForTheUpstreamAndLaterCallsAreNotFound() = runBlocking {
        headersDelayMs["/live/seg100.ts"] = 5_000
        val r = relay()
        r.pl()
        val pending = async(kotlinx.coroutines.Dispatchers.IO) { r.item("s-1") }
        while (seen.none { it.path!!.contains("seg100") }) kotlinx.coroutines.delay(10)
        val started = System.nanoTime()
        r.release()
        assertTrue((System.nanoTime() - started) < 3_000_000_000L)
        assertEquals(0, inFlight.get())
        assertEquals(HlsResult.NotFound, pending.await())
        assertEquals(HlsResult.NotFound, r.item("s-1"))
        assertEquals(HlsResult.NotFound, r.playlist())
    }

    @Test
    fun closeDoesNotBlockAndLaterCallsAreNotFound() = runBlocking {
        val r = relay()
        r.pl()
        r.close()
        assertEquals(HlsResult.NotFound, r.item("s-1"))
        assertEquals(HlsResult.NotFound, r.playlist())
    }

    @Test
    fun mapsAndKeysOfEveryMediaPlaylistAreKept() = runBlocking {
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"a.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nv.m3u8\n"
        bodies["/live/v.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"vinit.mp4\"\n#EXTINF:6,\nv1.ts\n"
        bodies["/live/a.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"ainit.mp4\"\n#EXTINF:6,\na1.ts\n"
        bodies["/live/vinit.mp4"] = "vi"
        bodies["/live/ainit.mp4"] = "ai"
        val r = relay()
        val master = text(r.pl())
        val ids = master.lines().filter { it.startsWith("p-") }
        val audioId = Regex("URI=\"(p-\\d+\\.m3u8)\"").find(master)!!.groupValues[1]
        val videoId = ids.single()
        val mapId = Regex("m-\\d+")
        val video = text(r.pl(videoId))
        r.get(mapId.find(video)!!.value)
        val audio = text(r.pl(audioId))
        assertNotNull(r.get(mapId.find(audio)!!.value))
        r.get(mapId.find(video)!!.value)
        assertEquals(1, requestCount("/live/vinit.mp4"))
    }

    private val tagNames = setOf(
        "#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-DISCONTINUITY", "#EXT-X-ENDLIST", "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-START", "#EXT-X-GAP",
        "#EXT-X-BITRATE", "#EXTINF", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-MEDIA", "#EXT-X-STREAM-INF",
    )

    private fun assertWellFormed(out: String) {
        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals(out, "#EXTM3U", lines.first())
        assertEquals(out, 1, lines.count { it.startsWith("#EXTM3U") })
        var previous = ""
        for (line in lines) {
            if (line.startsWith("#")) {
                assertTrue("$line in $out", line.substringBefore(':') in tagNames)
                if (line.startsWith("#EXTINF")) assertTrue(line, Regex("#EXTINF:-?\\d+(\\.\\d+)?,").matches(line))
            } else {
                assertTrue("$line in $out", Regex("[psmk]-\\d+(\\.m3u8)?").matches(line))
                if (line.startsWith("s-")) assertTrue(out, previous.startsWith("#EXTINF"))
            }
            previous = line
        }
    }

    private suspend fun rewritten(body: String): String? {
        bodies["/live/index.m3u8"] = body
        return relay().pl()?.let { String(it, Charsets.UTF_8) }
    }

    private val leakNeedles = listOf("SECRET", "provider.example", "://", "{$")

    @Test
    fun noSecretSurvivesInMediaPlaylists() = runBlocking {
        val head = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1\n"
        val key = "METHOD=AES-128,URI=\"keys/a.key\""
        val cases = mapOf(
            "extinf title" to "#EXTINF:6.0,http://provider.example/SECRET\nseg.ts\n",
            "extinf tvg" to "#EXTINF:6.0 tvg-id=\"SECRET\",x\nseg.ts\n",
            "extinf tvg url" to "#EXTINF:-1 tvg-logo=\"http://provider.example/SECRET.png\" tvg-name=\"a\",b\nseg.ts\n",
            "extinf no comma" to "#EXTINF:6.0 SECRET\nseg.ts\n",
            "extinf junk" to "#EXTINF:SECRET,SECRET\nseg.ts\n",
            "start extra" to "#EXT-X-START:TIME-OFFSET=-3,SECRET=SECRET\n#EXTINF:6,\nseg.ts\n",
            "start url" to "#EXT-X-START:TIME-OFFSET=http://provider.example/SECRET\n#EXTINF:6,\nseg.ts\n",
            "pdt extra" to "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z SECRET\n#EXTINF:6,\nseg.ts\n",
            "playlist type" to "#EXT-X-PLAYLIST-TYPE:SECRET\n#EXTINF:6,\nseg.ts\n",
            "discontinuity suffix" to "#EXT-X-DISCONTINUITY:SECRET\n#EXTINF:6,\nseg.ts\n",
            "endlist suffix" to "#EXTINF:6,\nseg.ts\n#EXT-X-ENDLIST:SECRET\n",
            "gap" to "#EXT-X-GAP:SECRET\n#EXTINF:6,\nseg.ts\n",
            "bitrate" to "#EXT-X-BITRATE:SECRET\n#EXTINF:6,\nseg.ts\n",
            "disc seq" to "#EXT-X-DISCONTINUITY-SEQUENCE:SECRET\n#EXTINF:6,\nseg.ts\n",
            "independent" to "#EXT-X-INDEPENDENT-SEGMENTS:SECRET\n#EXTINF:6,\nseg.ts\n",
            "second extm3u" to "#EXTM3U SECRET\n#EXTINF:6,\nseg.ts\n",
            "key method" to "#EXT-X-KEY:METHOD=SECRET,URI=\"keys/a.key\"\n#EXTINF:6,\nseg.ts\n",
            "key iv" to "#EXT-X-KEY:$key,IV=SECRET\n#EXTINF:6,\nseg.ts\n",
            "key format" to "#EXT-X-KEY:$key,KEYFORMAT=\"SECRET\"\n#EXTINF:6,\nseg.ts\n",
            "key versions" to "#EXT-X-KEY:$key,KEYFORMATVERSIONS=\"SECRET\"\n#EXTINF:6,\nseg.ts\n",
            "key spaced" to "#EXT-X-KEY: METHOD = AES-128 , URI = \"keys/a.key\" , X = SECRET\n#EXTINF:6,\nseg.ts\n",
            "key none with uri" to "#EXT-X-KEY:METHOD=NONE,URI=\"http://provider.example/SECRET\"\n#EXTINF:6,\nseg.ts\n",
            "key quoted comma" to "#EXT-X-KEY:X=\"a,URI=SECRET\",$key\n#EXTINF:6,\nseg.ts\n",
            "map extras" to "#EXT-X-MAP:URI=\"init.mp4\",X=SECRET\n#EXTINF:6,\nseg.ts\n",
            "segment query" to "#EXTINF:6,\nseg.ts?token=SECRET\n",
            "segment path" to "#EXTINF:6,\nSECRET/seg.ts\n",
            "session" to "#EXT-X-SESSION-DATA:VALUE=\"SECRET\"\n#EXTINF:6,\nseg.ts\n",
            "cue" to "#EXT-X-CUE-OUT:SECRET\n#EXTINF:6,\nseg.ts\n",
            "lowercase" to "#ext-x-secret:SECRET\n#EXTINF:6,\nseg.ts\n",
            "comment" to "# SECRET\n#EXTINF:6,\nseg.ts\n",
        )
        for ((name, body) in cases) {
            val out = rewritten(head + body) ?: continue
            for (needle in leakNeedles) assertFalse("$name: $needle in $out", out.contains(needle))
            assertWellFormed(out)
        }
    }

    @Test
    fun extinfAttributesDoNotBreakThePlaylist() = runBlocking {
        val out = rewritten("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:-1 tvg-logo=\"http://provider.example/a.png\",Name\nseg.ts\n")!!
        assertTrue(out, out.contains("#EXTINF:-1,\n"))
        assertTrue(out.lines().contains("s-1"))
        assertWellFormed(out)
    }

    @Test
    fun strictValuesArePrintedOnlyWhenTheyMatch() = runBlocking {
        val out = rewritten(
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:9\n#EXT-X-PLAYLIST-TYPE:EVENT\n" +
                "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z\n#EXT-X-START:TIME-OFFSET=-3.5,PRECISE=YES\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/a.key\",IV=0x0A,KEYFORMAT=\"identity\",KEYFORMATVERSIONS=\"1/2\"\n" +
                "#EXT-X-DISCONTINUITY\n#EXTINF:6.0,\nseg.ts\n#EXT-X-ENDLIST\n",
        )!!
        assertTrue(out, out.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"))
        assertTrue(out, out.contains("#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z\n"))
        assertTrue(out, out.contains("#EXT-X-START:TIME-OFFSET=-3.5,PRECISE=YES\n"))
        assertTrue(out, out.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"k-1\",IV=0x0A,KEYFORMAT=\"identity\",KEYFORMATVERSIONS=\"1/2\"\n"))
        assertTrue(out, out.contains("#EXT-X-DISCONTINUITY\n"))
        assertTrue(out, out.contains("#EXT-X-ENDLIST\n"))
        assertWellFormed(out)
    }

    @Test
    fun structuralValuesThatFailMakeThePlaylistUnsupported() = runBlocking {
        for (bad in listOf("#EXT-X-TARGETDURATION:6,SECRET", "#EXT-X-MEDIA-SEQUENCE:SECRET")) {
            bodies["/live/index.m3u8"] = "#EXTM3U\n$bad\n#EXTINF:6,\nseg.ts\n"
            assertEquals(HlsResult.Upstream(502), relay().playlist())
        }
        bodies["/live/index.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=SECRET,URI=\"keys/a.key\"\n#EXTINF:6,\nseg.ts\n"
        assertEquals(HlsResult.Upstream(502), relay().playlist())
    }

    @Test
    fun noSecretSurvivesInMasterPlaylists() = runBlocking {
        val variant = "low/index.m3u8"
        val cases = mapOf(
            "attr" to "#EXT-X-STREAM-INF:BANDWIDTH=1000,X=SECRET\n$variant\n",
            "codecs url" to "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"http://provider.example/SECRET\"\n$variant\n",
            "bandwidth" to "#EXT-X-STREAM-INF:BANDWIDTH=SECRET,RESOLUTION=SECRET,FRAME-RATE=SECRET\n$variant\n",
            "resolution" to "#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=SECRET,FRAME-RATE=SECRET,VIDEO-RANGE=SECRET\n$variant\n",
            "audio group" to "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"SECRET\",NAME=\"en\",URI=\"a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"SECRET\"\n$variant\n",
            "media name" to "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"SECRET\",LANGUAGE=\"SECRET\",URI=\"a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"\n$variant\n",
            "media extras" to "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"x,URI=SECRET\",CHANNELS=\"secret SECRET\",DEFAULT=SECRET,URI=\"a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"\n$variant\n",
            "media type" to "#EXT-X-MEDIA:TYPE=SECRET,GROUP-ID=\"a\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"\n$variant\n",
            "independent" to "#EXT-X-INDEPENDENT-SEGMENTS:SECRET\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$variant\n",
            "version" to "#EXT-X-VERSION:3 SECRET\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$variant\n",
            "session" to "#EXT-X-SESSION-DATA:VALUE=\"SECRET\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$variant\n",
            "comment" to "# SECRET\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$variant\n",
            "no uri line" to "#EXT-X-STREAM-INF:BANDWIDTH=1000\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"SECRET\"\n",
        )
        for ((name, body) in cases) {
            val out = rewritten("#EXTM3U\n$body") ?: continue
            for (needle in leakNeedles) assertFalse("$name: $needle in $out", out.contains(needle))
            assertWellFormed(out)
            assertFalse("$name: empty STREAM-INF in $out", out.contains("STREAM-INF:\n"))
        }
    }

    @Test
    fun variantWithoutValidBandwidthIsExcludedAndGroupsAreRenamed() = runBlocking {
        val out = rewritten(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"secret-group\",NAME=\"Private Name\",LANGUAGE=\"en\",DEFAULT=YES,URI=\"a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=NOPE\nbad.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"secret-group\",CODECS=\"avc1.4d401f,mp4a.40.2\"\nlow.m3u8\n",
        )!!
        assertFalse(out, out.contains("secret"))
        assertFalse(out, out.contains("Private"))
        assertTrue(out, out.contains("NAME=\"Audio 1\""))
        assertTrue(out, out.contains("GROUP-ID=\"g-1\""))
        assertTrue(out, out.contains("AUDIO=\"g-1\""))
        assertTrue(out, out.contains("CODECS=\"avc1.4d401f,mp4a.40.2\""))
        assertEquals(1, out.lines().count { it.startsWith("#EXT-X-STREAM-INF") })
    }

    @Test
    fun cacheBudgetIsASixthOfTheHeapUpTo48Mb() {
        val mb = 1024L * 1024
        assertEquals(128 * mb / 6, hlsCacheBytes(128))
        assertEquals(32 * mb, hlsCacheBytes(192))
        assertEquals(48 * mb, hlsCacheBytes(288))
        assertEquals(48 * mb, hlsCacheBytes(512))
        assertEquals(0L, hlsCacheBytes(-1))
    }
}
