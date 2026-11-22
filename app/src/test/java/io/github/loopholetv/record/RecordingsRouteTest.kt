package io.github.loopholetv.record

import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.record.storage.RecordingStorage
import io.github.loopholetv.record.storage.StorageResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val server = LocalHttpServer(scope)

    /** Files by name; [read] counts the sizes asked for. */
    private class MemoryStorage : RecordingStorage {
        val files = HashMap<String, ByteArray>()
        var sizeCalls = 0
        override val id = "mem"
        override val label = "Memory"
        override suspend fun test() = StorageResult.Ok
        override suspend fun freeBytes(): Long? = null
        override suspend fun openAppend(name: String): OutputStream = throw UnsupportedOperationException()
        override suspend fun size(name: String): Long? {
            sizeCalls++
            return files[name]?.size?.toLong()
        }
        override suspend fun openRead(name: String, offset: Long): InputStream =
            ByteArrayInputStream(files.getValue(name), offset.toInt(), files.getValue(name).size - offset.toInt())
        override suspend fun delete(name: String) = files.remove(name) != null
    }

    private val storage = MemoryStorage()
    private val rows = ArrayList<RecordingEntity>()

    private fun row(id: Long, state: RecordingState, file: String? = "r$id.ts", bytes: Long = 10) = RecordingEntity(
        id = id, playlistId = 1, channelKey = "c", channelName = "News", epgChannelId = null, title = "Show $id",
        subTitle = null, episodeKey = null, description = null,
        start = Instant.ofEpochMilli(1_000_000), stop = Instant.ofEpochMilli(1_000_000 + 3_600_000),
        padBeforeMin = 1, padAfterMin = 5, state = state, reason = RecordingReason.NONE, fileName = file,
        storageId = "mem", bytes = bytes, startedAt = Instant.ofEpochMilli(1_000_000),
        endedAt = if (state == RecordingState.RECORDING) null else Instant.ofEpochMilli(1_000_000 + 1_800_000),
        createdAt = Instant.ofEpochMilli(0),
    )

    private val route = RecordingsRoute(
        recordings = { rows.toList() },
        recording = { id -> rows.firstOrNull { it.id == id } },
        storageFor = { if (it.storageId == "mem") storage else null },
        allowShare = { allow },
        token = "tok",
    )
    /** False is also what the app reports before settings have loaded. */
    @Volatile private var allow = true

    private val port: Int = run {
        route.install(server)
        server.start(listOf(InetAddress.getByName("127.0.0.1")))
    }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private class Reply(val code: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun get(path: String, range: String? = null): Reply {
        val b = Request.Builder().url("http://127.0.0.1:$port$path")
        if (range != null) b.header("Range", range)
        return http.newCall(b.build()).execute().use { r ->
            Reply(r.code, r.body.bytes(), r.headers.toMultimap().mapValues { it.value.first() })
        }
    }

    private val ten = ByteArray(10) { it.toByte() }

    @Test fun listRequiresToken() {
        rows += row(1, RecordingState.DONE)
        assertEquals(401, get("/r/nope/list").code)
        assertEquals(401, get("/r/nope/file/1").code)
        val ok = get("/r/tok/list")
        assertEquals(200, ok.code)
        assertEquals("application/json", ok.headers["content-type"]?.substringBefore(';'))
    }

    @Test fun listHoldsOnlyRecordingsThatHaveAFile() {
        rows += listOf(
            row(1, RecordingState.DONE), row(2, RecordingState.STOPPED), row(3, RecordingState.RECORDING),
            row(4, RecordingState.FAILED), row(5, RecordingState.SCHEDULED, file = null),
            row(6, RecordingState.FAILED, bytes = 0), row(7, RecordingState.MISSED),
        )
        val list = RemoteRecording.parseList(String(get("/r/tok/list").body))!!
        // A failure that wrote part of the programme can be played; one that wrote nothing can't.
        assertEquals(listOf(1L, 2L, 3L, 4L), list.map { it.id }.sorted())
        val live = list.first { it.id == 3L }
        assertTrue(live.recording)
        assertFalse(list.first { it.id == 1L }.recording)
        assertEquals("Show 1", list.first { it.id == 1L }.title)
        assertEquals("News", list.first { it.id == 1L }.channel)
        assertEquals(1_000_000L, list.first { it.id == 1L }.start)
        assertEquals(1_800_000L, list.first { it.id == 1L }.durationMs)
        assertEquals(10L, list.first { it.id == 1L }.bytes)
    }

    @Test fun fileServesWholeAndRanges() {
        rows += row(1, RecordingState.DONE)
        storage.files["r1.ts"] = ten

        val whole = get("/r/tok/file/1")
        assertEquals(200, whole.code)
        assertEquals("video/mp2t", whole.headers["content-type"])
        assertEquals("10", whole.headers["content-length"])
        assertEquals(ten.toList(), whole.body.toList())

        val tail = get("/r/tok/file/1", "bytes=3-")
        assertEquals(206, tail.code)
        assertEquals("bytes 3-9/10", tail.headers["content-range"])
        assertEquals("7", tail.headers["content-length"])
        assertEquals(ten.drop(3), tail.body.toList())

        val mid = get("/r/tok/file/1", "bytes=2-4")
        assertEquals(206, mid.code)
        assertEquals("bytes 2-4/10", mid.headers["content-range"])
        assertEquals(ten.slice(2..4), mid.body.toList())

        val clamped = get("/r/tok/file/1", "bytes=8-99")
        assertEquals("bytes 8-9/10", clamped.headers["content-range"])
        assertEquals(ten.slice(8..9), clamped.body.toList())
    }

    @Test fun aFailedRecordingWithPartOfTheProgrammeIsServed() {
        rows += row(1, RecordingState.FAILED)
        storage.files["r1.ts"] = ten
        val ok = get("/r/tok/file/1")
        assertEquals(200, ok.code)
        assertTrue(ok.body.contentEquals(ten))
    }

    @Test fun mp4HasItsOwnContentType() {
        rows += row(1, RecordingState.DONE, file = "r.mp4")
        storage.files["r.mp4"] = ten
        assertEquals("video/mp4", get("/r/tok/file/1").headers["content-type"])
    }

    @Test fun rangeBeyondSizeIs416() {
        rows += row(1, RecordingState.DONE)
        storage.files["r1.ts"] = ten
        val r = get("/r/tok/file/1", "bytes=10-")
        assertEquals(416, r.code)
        assertEquals("bytes */10", r.headers["content-range"])
        assertEquals(416, get("/r/tok/file/1", "bytes=50-60").code)
    }

    @Test fun badRangeIsServedWhole() {
        rows += row(1, RecordingState.DONE)
        storage.files["r1.ts"] = ten
        assertEquals(200, get("/r/tok/file/1", "bytes=-5").code)
        assertEquals(200, get("/r/tok/file/1", "bytes=4-2").code)
        assertEquals(200, get("/r/tok/file/1", "bytes=0-1,3-4").code)
    }

    @Test fun growingFileSnapshotLength() {
        rows += row(1, RecordingState.RECORDING)
        storage.files["r1.ts"] = ten
        assertEquals("10", get("/r/tok/file/1").headers["content-length"])
        storage.files["r1.ts"] = ByteArray(25)
        val second = get("/r/tok/file/1", "bytes=10-")
        assertEquals("bytes 10-24/25", second.headers["content-range"])
        assertEquals(15, second.body.size)
    }

    @Test fun sizeIsAskedOncePerRequest() {
        rows += row(1, RecordingState.DONE)
        storage.files["r1.ts"] = ten
        get("/r/tok/file/1", "bytes=2-")
        assertEquals(1, storage.sizeCalls)
    }

    @Test fun missingThingsAre404() {
        rows += row(1, RecordingState.DONE)
        rows += row(2, RecordingState.FAILED, bytes = 0)
        rows += row(3, RecordingState.DONE, file = null)
        assertEquals("row without a file on the storage", 404, get("/r/tok/file/1").code)
        assertEquals("not a servable state", 404, get("/r/tok/file/2").code)
        assertEquals("no file name", 404, get("/r/tok/file/3").code)
        assertEquals(404, get("/r/tok/file/99").code)
        assertEquals(404, get("/r/tok/file/abc").code)
        assertEquals(404, get("/r/tok/other").code)
    }

    @Test fun clientBuildsFileUrlsOnlyForLocalPeers() {
        val client = RecordingsClient(http)
        fun peer(host: String) = CastPeer("p", "n", host, 8123, CastStatus.IDLE, true, null, null, null, 0, rec = "t0k")
        assertEquals("http://192.168.1.20:8123/r/t0k/file/7", client.fileUrl(peer("192.168.1.20"), 7))
        assertNull(client.fileUrl(peer("example.com"), 7))
        assertNull(client.fileUrl(peer("192.168.1.20").copy(rec = null), 7))
    }

    private class Capture : io.github.loopholetv.cast.net.HttpResponse {
        var code = 0
        override val body = java.io.ByteArrayOutputStream()
        override fun start(code: Int, contentType: String?, length: Long?, headers: Map<String, String>) {
            this.code = code
        }
    }

    private fun lanRequest(path: String) = io.github.loopholetv.cast.net.HttpRequest(
        "GET", path, emptyMap(), emptyMap(), InetAddress.getByName("192.168.1.9"),
    )

    @Test fun sharingOffIs404ForLanPeersButLoopbackStillWorks() = runBlocking {
        rows += row(1, RecordingState.DONE)
        storage.files["r1.ts"] = ten
        allow = false
        listOf("/r/tok/list", "/r/tok/file/1").forEach {
            val res = Capture()
            route.handle(lanRequest(it), res)
            assertEquals(404, res.code)
        }
        assertEquals(200, get("/r/tok/list").code)
        assertEquals(200, get("/r/tok/file/1").code)
        allow = true
        val res = Capture()
        route.handle(lanRequest("/r/tok/list"), res)
        assertEquals(200, res.code)
    }

    @Test fun localFileUrlUsesLoopbackAndThePort() {
        assertEquals("http://127.0.0.1:$port/r/tok/file/4", RecordingsRoute({ emptyList() }, { null }, { null }, { port }, token = "tok").localFileUrl(4))
        assertNull(RecordingsRoute({ emptyList() }, { null }, { null }, { null }, token = "tok").localFileUrl(4))
    }

    @Test fun announcesTheTokenOnlyWhileARecordingCanBeServed() = runBlocking {
        val feed = kotlinx.coroutines.flow.MutableStateFlow<List<RecordingEntity>>(emptyList())
        val announced = route.announced(CoroutineScope(Dispatchers.Unconfined), feed)
        assertNull(announced.value)
        feed.value = listOf(row(1, RecordingState.FAILED, bytes = 0))
        assertNull(announced.value)
        feed.value = listOf(row(1, RecordingState.RECORDING))
        assertEquals("tok", announced.value)
        feed.value = emptyList()
        assertNull(announced.value)
    }

    @Test fun clientListsFromARunningRoute() = runBlocking {
        rows += row(1, RecordingState.DONE)
        val peer = CastPeer("p", "n", "127.0.0.1", port, CastStatus.IDLE, true, null, null, null, 0, rec = "tok")
        val list = RecordingsClient(http).list(peer)
        assertEquals(listOf(1L), list.map { it.id })
        assertEquals(emptyList<RemoteRecording>(), RecordingsClient(http).list(peer.copy(rec = "wrong")))
        assertEquals(emptyList<RemoteRecording>(), RecordingsClient(http).list(peer.copy(rec = null)))
    }
}
