package io.github.loopholetv.record

import io.github.loopholetv.player.ResolvedStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsRecorderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val out = ByteArrayOutputStream()
    private val formats = mutableListOf<String>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val playlists = ConcurrentHashMap<String, MutableList<String>>()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val requestHeaders = ConcurrentHashMap<String, String?>()

    @Before fun start() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    val n = hits.getOrPut(path) { AtomicInteger() }.getAndIncrement()
                    requestHeaders[path] = request.getHeader("X-Test")
                    playlists[path]?.let { list ->
                        if (list[minOf(n, list.size - 1)] == "404") return MockResponse().setResponseCode(404)
                        return MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody(list[minOf(n, list.size - 1)])
                    }
                    files[path]?.let { return MockResponse().setBody(Buffer().write(it)) }
                    return MockResponse().setResponseCode(404)
                }
            }
            it.start()
        }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun seg(i: Int) = ByteArray(1000) { (i * 31 + it).toByte() }.also { it[0] = 0x47 }

    private fun media(first: Int, last: Int, end: Boolean = false, extra: String = "", prefix: String = "s") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:$first\n$extra")
        for (i in first..last) append("#EXTINF:4.0,\n$prefix$i.ts\n")
        if (end) append("#EXT-X-ENDLIST\n")
    }

    private fun recorder(): Recorder = Recorder(client, { 1L }) { }

    private suspend fun run(url: String = "/live.m3u8") = recorder().record(
        open = { ResolvedStream(server.url(url).toString(), "UA", mapOf("X-Test" to "yes")) },
        until = { Long.MAX_VALUE },
        openSink = { RecordSink(out) { } },
        onFormat = { formats += it },
    )

    @Test fun appendsNewSegmentsOnce() = runTest {
        playlists["/live.m3u8"] = mutableListOf(media(0, 1), media(0, 2), media(1, 3), media(2, 4, end = true))
        for (i in 0..4) files["/s$i.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(5000, 0), result)
        val expected = (0..4).map { seg(it) }.reduce { a, b -> a + b }
        assertTrue(expected.contentEquals(out.toByteArray()))
        for (i in 0..4) assertEquals("segment $i", 1, hits["/s$i.ts"]!!.get())
        assertEquals(listOf("ts"), formats)
        assertEquals("yes", requestHeaders["/s0.ts"])
    }

    @Test fun skippedSequenceCountsAGap() = runTest {
        playlists["/live.m3u8"] = mutableListOf(media(0, 1), media(4, 5, end = true))
        for (i in 0..5) files["/s$i.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(4000, 1), result)
    }

    @Test fun segmentsWithoutSequenceTagAreDedupedByUri() = runTest {
        fun pl(vararg names: String, end: Boolean = false) = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
            names.joinToString("") { "#EXTINF:4.0,\n$it\n" } + if (end) "#EXT-X-ENDLIST\n" else ""
        playlists["/live.m3u8"] = mutableListOf(pl("a.ts", "b.ts"), pl("b.ts", "c.ts", end = true))
        for ((i, n) in listOf("a", "b", "c").withIndex()) files["/$n.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(3000, 0), result)
        assertTrue((seg(0) + seg(1) + seg(2)).contentEquals(out.toByteArray()))
    }

    @Test fun masterPicksHighestBandwidth() = runTest {
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhigh/index.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1500000\nmid/index.m3u8\n",
        )
        playlists["/high/index.m3u8"] = mutableListOf(media(0, 1, end = true, prefix = "h"))
        playlists["/low/index.m3u8"] = mutableListOf(media(0, 1, end = true, prefix = "l"))
        playlists["/mid/index.m3u8"] = mutableListOf(media(0, 1, end = true, prefix = "m"))
        files["/high/h0.ts"] = seg(0)
        files["/high/h1.ts"] = seg(1)
        val result = run()
        assertEquals(RecordOutcome.Finished(2000, 0), result)
        assertTrue(hits["/low/index.m3u8"] == null && hits["/mid/index.m3u8"] == null)
        assertTrue((seg(0) + seg(1)).contentEquals(out.toByteArray()))
    }

    @Test fun fmp4WritesInitOnceAndUsesMp4() = runTest {
        val map = "#EXT-X-MAP:URI=\"init.mp4\"\n"
        playlists["/live.m3u8"] = mutableListOf(media(0, 1, extra = map), media(0, 2, end = true, extra = map))
        files["/init.mp4"] = byteArrayOf(0, 0, 0, 8) + "ftyp".toByteArray()
        for (i in 0..2) files["/s$i.ts"] = seg(i)
        val result = run()
        result as RecordOutcome.Finished
        assertEquals(listOf("mp4"), formats)
        assertEquals(1, hits["/init.mp4"]!!.get())
        val init = files["/init.mp4"]!!
        assertTrue((init + seg(0) + seg(1) + seg(2)).contentEquals(out.toByteArray()))
        assertEquals(init.size + 3000L, result.bytes)
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }

    @Test fun aes128SegmentsAreDecrypted() = runTest {
        val key = ByteArray(16) { (it + 7).toByte() }
        val explicitIv = ByteArray(16) { (it * 3).toByte() }
        files["/key.bin"] = key
        // Segment 5 uses the media sequence as IV; segment 6 an explicit IV.
        val seqIv = ByteArray(16).also { it[15] = 5 }
        files["/s5.ts"] = encrypt(seg(5), key, seqIv)
        files["/s6.ts"] = encrypt(seg(6), key, explicitIv)
        val hex = explicitIv.joinToString("") { "%02x".format(it) }
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:5\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4.0,\ns5.ts\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x$hex\n#EXTINF:4.0,\ns6.ts\n#EXT-X-ENDLIST\n",
        )
        val result = run()
        assertEquals(RecordOutcome.Finished(2000, 0), result)
        assertTrue((seg(5) + seg(6)).contentEquals(out.toByteArray()))
        assertEquals("yes", requestHeaders["/key.bin"])
    }

    @Test fun sampleAesIsFailedNotRecorded() = runTest {
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4.0,\ns0.ts\n#EXT-X-ENDLIST\n",
        )
        files["/s0.ts"] = seg(0)
        val result = run()
        assertTrue(result.toString(), result is RecordOutcome.Failed)
        assertEquals(0, out.size())
    }

    private fun plain(vararg names: String, end: Boolean = false) = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
        names.joinToString("") { "#EXTINF:4.0,\n$it\n" } + if (end) "#EXT-X-ENDLIST\n" else ""

    private fun segs(vararg i: Int) = i.map { seg(it) }.reduce { a, b -> a + b }

    @Test fun firstWindowLargerThanTheCapIsWrittenInOrder() = runTest {
        playlists["/live.m3u8"] = mutableListOf(
            plain(*(0..14).map { "u$it.ts" }.toTypedArray()),
            plain(*(0..15).map { "u$it.ts" }.toTypedArray(), end = true),
        )
        for (i in 0..15) files["/u$i.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(11_000, 0), result)
        assertTrue(segs(*(5..15).toList().toIntArray()).contentEquals(out.toByteArray()))
        for (i in 0..4) assertTrue(hits["/u$i.ts"] == null)
    }

    @Test fun sequenceRestartWithLongWindowIsCapped() = runTest {
        playlists["/live.m3u8"] = mutableListOf(media(50, 52), media(0, 29, end = true))
        for (i in 0..52) files["/s$i.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(13_000, 1), result)
        assertTrue(segs(50, 51, 52, *(20..29).toList().toIntArray()).contentEquals(out.toByteArray()))
    }

    @Test fun oversizedPlaylistIsRejected() = runTest {
        val big = "#EXTM3U\n" + "#EXT-X-COMMENT:" + "x".repeat(2 * 1024 * 1024) + "\n" + plain("s0.ts", end = true)
        playlists["/live.m3u8"] = mutableListOf(big)
        files["/s0.ts"] = seg(0)
        val result = run()
        assertTrue(result.toString(), result is RecordOutcome.Failed)
        assertEquals(0, out.size())
        assertTrue(hits["/s0.ts"] == null)
    }

    @Test fun oversizedKeyIsRejectedAndSegmentSkipped() = runTest {
        files["/key.bin"] = ByteArray(100)
        files["/s0.ts"] = seg(0)
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4.0,\ns0.ts\n#EXT-X-ENDLIST\n",
        )
        val result = run()
        assertEquals(RecordOutcome.Finished(0, 1), result)
        assertEquals(0, out.size())
    }

    @Test fun largeEncryptedSegmentIsDecryptedThroughTheStreamingPath() = runTest {
        val key = ByteArray(16) { (it + 1).toByte() }
        val plain = ByteArray(300_000) { (it * 7).toByte() }
        files["/key.bin"] = key
        files["/s0.ts"] = encrypt(plain, key, ByteArray(16))
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4.0,\ns0.ts\n#EXT-X-ENDLIST\n",
        )
        val result = run()
        assertEquals(RecordOutcome.Finished(300_000, 0), result)
        assertTrue(plain.contentEquals(out.toByteArray()))
    }

    @Test fun nonHttpKeyUriIsNotFollowed() = runTest {
        files["/s0.ts"] = seg(0)
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"file:///etc/passwd\"\n#EXTINF:4.0,\ns0.ts\n#EXT-X-ENDLIST\n",
        )
        val result = run()
        assertTrue(result.toString(), result is RecordOutcome.Failed)
        assertEquals(0, out.size())
        assertTrue(hits["/s0.ts"] == null)
    }

    @Test fun malformedIvSkipsTheSegment() = runTest {
        files["/key.bin"] = ByteArray(16)
        files["/s0.ts"] = seg(0)
        playlists["/live.m3u8"] = mutableListOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0xZZ\n#EXTINF:4.0,\ns0.ts\n#EXT-X-ENDLIST\n",
        )
        val result = run()
        assertEquals(RecordOutcome.Finished(0, 1), result)
        assertTrue(hits["/s0.ts"] == null)
    }

    @Test fun reopeningAfterALostPlaylistCountsNoGapWhenNothingWasMissed() = runTest {
        playlists["/live.m3u8"] = mutableListOf(media(0, 1), "404", "404", "404", media(2, 3, end = true))
        for (i in 0..3) files["/s$i.ts"] = seg(i)
        val result = run()
        assertEquals(RecordOutcome.Finished(4000, 0), result)
        assertTrue(segs(0, 1, 2, 3).contentEquals(out.toByteArray()))
    }
}
