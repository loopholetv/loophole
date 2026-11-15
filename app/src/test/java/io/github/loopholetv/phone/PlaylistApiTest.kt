package io.github.loopholetv.phone

import com.squareup.moshi.Moshi
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.ui.onboarding.INVALID_MAC
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistApiTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = InMemoryPairingStore()
    private val pairing = PhonePairing(store, System::currentTimeMillis)
    private val http = LocalHttpServer(scope, maxPerRemote = 4)
    private val client = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val phone = PhoneServer(
        scope = scope,
        server = http,
        pairing = pairing,
        store = store,
        lanAddress = { loopback },
        watchNetwork = { AutoCloseable { } },
        asset = { null },
        tvName = { "Living room" },
    )
    private val jobs = PhoneJobs(scope)

    private val stored = java.util.concurrent.ConcurrentSkipListMap(
        mapOf(
            1L to PlaylistEntity(
                id = 1, name = "Provider", type = PlaylistType.XTREAM, url = null,
                xtreamServer = "http://xt.example", xtreamUser = "alice", xtreamPass = "xtream-secret",
                epgUrlsFromSource = "", manualEpgUrl = null,
                lastRefreshedAt = Instant.ofEpochMilli(1_700_000_000_000),
                lastError = "HTTP 500 from http://xt.example/get.php?username=alice&password=***",
            ),
            2L to PlaylistEntity(
                id = 2, name = "Portal", type = PlaylistType.STALKER, url = "http://portal.example/c/",
                xtreamServer = null, xtreamUser = null, xtreamPass = null,
                epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = "The portal refused this MAC address or login.",
                enabled = false, mac = "00:1A:79:12:34:56", portalLogin = "bob", portalPassword = "portal-secret",
                portalSerial = "SN1",
            ),
            3L to PlaylistEntity(
                id = 3, name = "Saved file", type = PlaylistType.M3U_FILE, url = "content://files/list.m3u",
                xtreamServer = null, xtreamUser = null, xtreamPass = null,
                epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
            ),
        ),
    )

    private val calls = CopyOnWriteArrayList<String>()
    private val edits = CopyOnWriteArrayList<Pair<Long, SourceEdit>>()
    @Volatile private var addGate = CompletableDeferred(Unit)
    @Volatile private var guideGate = CompletableDeferred(Unit)
    @Volatile private var refreshGate = CompletableDeferred(Unit)
    @Volatile private var afterEnabledGate = CompletableDeferred(Unit)

    /** Stands in for the playlist repository's lock, which every load holds and a delete takes. */
    private val repoLock = Mutex()

    private val api = PlaylistApi(
        jobs = jobs,
        work = scope,
        playlists = { stored.values.toList() },
        channelCounts = { mapOf(1L to 120) },
        playlist = { stored[it] },
        addM3u = { name, url, progress ->
            calls += "addM3u $name $url"
            progress(5)
            addGate.await()
            stored[9] = PlaylistEntity(
                id = 9, name = name, type = PlaylistType.M3U, url = url,
                xtreamServer = null, xtreamUser = null, xtreamPass = null,
                epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
            )
            ImportResult.Success(9, 5)
        },
        addXtream = { _, _: XtreamCredentials, _ -> ImportResult.Failure(ImportError.AuthFailed, "raw http://alice:pw@x") },
        addStalker = { _, _: PortalConfig, _ -> ImportResult.Success(10, 1) },
        refreshGuide = { id, progress ->
            calls += "guide $id"
            progress(10)
            guideGate.await()
            throw IllegalStateException("guide failed")
        },
        updateSource = { id, edit, _ ->
            edits += id to edit
            ImportResult.Success(id, 1)
        },
        refreshPlaylist = { id, _ ->
            calls += "refresh $id"
            refreshGate.await()
            ImportResult.Success(id, 1)
        },
        guideAfterRefresh = { false },
        setEnabled = { id, on -> calls += "enabled $id $on" },
        afterEnabled = { id ->
            afterEnabledGate.await()
            calls += "afterEnabled $id"
        },
        delete = { id ->
            repoLock.withLock {
                calls += "delete $id"
                stored.remove(id)
            }
        },
    )

    private var port = 0

    @Before fun setUp() {
        api.register(phone)
        phone.onForeground(true)
        port = runBlocking { withTimeout(5_000) { phone.address.first { it != null }!!.port } }
        pairing.open()
        post("/api/pair", """{"code":"${pairing.code.value}"}""").use { assertEquals(200, it.code) }
    }

    @After fun tearDown() {
        addGate.complete(Unit)
        guideGate.complete(Unit)
        refreshGate.complete(Unit)
        afterEnabledGate.complete(Unit)
        phone.onForeground(false)
        http.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun get(path: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { it.code to it.body.string() }

    private fun post(path: String, body: String) = client.newCall(
        Request.Builder()
            .url(url(path))
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("X-Loophole", "1")
            .build(),
    ).execute()

    private fun postFor(path: String, body: String = "{}"): Pair<Int, String> =
        post(path, body).use { it.code to it.body.string() }

    private fun obj(body: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return anyJson.fromJson(body) as Map<String, Any?>
    }

    /** A JSON number as a Long; null when absent or null. */
    private fun Map<String, Any?>.num(key: String): Long? = (this[key] as Number?)?.toLong()

    /** Polls `/api/job` until [until] holds. */
    private fun awaitJob(until: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            val job = obj(get("/api/job").second)
            if (until(job)) return job
            check(System.currentTimeMillis() < end) { "job never reached the state: $job" }
            Thread.sleep(10)
        }
    }

    private fun awaitEdits(n: Int) = runBlocking { withTimeout(5_000) { while (edits.size < n) delay(10) } }
    private fun awaitCalls(n: Int) = runBlocking { withTimeout(5_000) { while (calls.size < n) delay(10) } }

    @Test fun listHasNoSecrets() {
        val (code, body) = get("/api/playlists")
        assertEquals(200, code)
        assertFalse(body.contains("xtream-secret"))
        assertFalse(body.contains("portal-secret"))
        @Suppress("UNCHECKED_CAST")
        val list = anyJson.fromJson(body) as List<Map<String, Any?>>
        assertEquals(3, list.size)
        val first = list[0]
        assertEquals(1L, first.num("id"))
        assertEquals("Provider", first["name"])
        assertEquals("xtream", first["kind"])
        assertEquals(true, first["enabled"])
        assertEquals(1_700_000_000_000, first.num("lastRefreshedAt"))
        // The stored message is raw: only fixed copy goes out.
        assertEquals("Last update failed", first["error"])
        assertFalse(body.contains("HTTP 500"))
        assertFalse(body.contains("get.php"))
        assertEquals(120L, first.num("channels"))
        val portal = list[1]
        assertEquals("portal", portal["kind"])
        assertEquals(false, portal["enabled"])
        assertTrue(portal.containsKey("lastRefreshedAt") && portal["lastRefreshedAt"] == null)
        assertEquals("The portal refused this MAC address or login.", portal["error"])
        assertEquals(0L, portal.num("channels"))
        assertEquals("file", list[2]["kind"])
        assertTrue(list[2].containsKey("error") && list[2]["error"] == null)
    }

    @Test fun editFormHidesPasswords() {
        val (code, body) = get("/api/playlists/2")
        assertEquals(200, code)
        assertFalse(body.contains("portal-secret"))
        val form = obj(body)
        assertFalse(form.containsKey("pass"))
        assertFalse(form.containsKey("portalPass"))
        assertEquals("portal", form["mode"])
        assertEquals("Portal", form["name"])
        assertEquals("http://portal.example/c/", form["portalUrl"])
        assertEquals("00:1A:79:12:34:56", form["mac"])
        assertEquals("bob", form["portalLogin"])
        assertEquals("SN1", form["serial"])
        assertEquals("", form["deviceId"])
        assertEquals(true, form["hasPortalPass"])
        assertEquals(false, form["hasPass"])

        val (_, xtreamBody) = get("/api/playlists/1")
        assertFalse(xtreamBody.contains("xtream-secret"))
        val xtream = obj(xtreamBody)
        assertEquals("xtream", xtream["mode"])
        assertEquals("http://xt.example", xtream["server"])
        assertEquals("alice", xtream["user"])
        assertEquals(true, xtream["hasPass"])

        assertEquals(409 to """{"error":"tv_only"}""", get("/api/playlists/3"))
        assertEquals(404 to """{"error":"not_found"}""", get("/api/playlists/99"))
        assertEquals(404 to """{"error":"not_found"}""", get("/api/playlists/abc"))
        assertEquals(404 to """{"error":"not_found"}""", get("/api/playlists/1/extra"))
        assertEquals(404 to """{"error":"not_found"}""", get("/api/job/1"))
    }

    @Test fun editWithoutPassKeepsStored() {
        val (code, body) = postFor("/api/playlists/1", """{"mode":"xtream","name":"","server":" xt.example/ ","user":"alice"}""")
        assertEquals(202, code)
        val id = obj(body).num("job")
        val job = awaitJob { it["phase"] == "done" }
        assertEquals(id, job.num("id"))
        assertEquals("edit", job["kind"])
        assertEquals(1L, job.num("playlistId"))
        assertEquals("Provider", job["name"])
        assertEquals(1L to SourceEdit.Xtream(XtreamCredentials("http://xt.example", "alice", "xtream-secret")), edits.single())

        // A null portal password keeps the stored one too; a typed one replaces it.
        val portal = """"mode":"portal","portalUrl":"http://portal.example/c/","mac":"00-1a-79-12-34-56","portalLogin":"bob""""
        assertEquals(202, postFor("/api/playlists/2", """{$portal,"portalPass":null}""").first)
        awaitEdits(2)
        assertEquals("portal-secret", (edits[1].second as SourceEdit.Portal).cfg.password)
        assertEquals(202, postFor("/api/playlists/2", """{$portal,"portalPass":"typed"}""").first)
        awaitEdits(3)
        assertEquals("typed", (edits[2].second as SourceEdit.Portal).cfg.password)
    }

    @Test fun addRunsAddThenGuide() {
        addGate = CompletableDeferred()
        guideGate = CompletableDeferred()
        val (code, body) = postFor("/api/playlists", """{"mode":"m3u","name":"Mine","url":"list.example/a.m3u"}""")
        assertEquals(202, code)
        val id = obj(body).num("job")
        val channels = awaitJob { it["phase"] == "channels" && it.num("count") == 5L }
        assertEquals(id, channels.num("id"))
        assertEquals("add", channels["kind"])
        assertEquals("Mine", channels["name"])
        assertTrue(channels.containsKey("playlistId") && channels["playlistId"] == null)
        assertEquals(0L, channels.num("queued"))
        addGate.complete(Unit)
        awaitJob { it["phase"] == "guide" && it.num("count") == 10L }
        guideGate.complete(Unit)
        // A guide error still ends done, as on the TV.
        val done = awaitJob { it["phase"] == "done" }
        assertEquals(9L, done.num("playlistId"))
        assertEquals(listOf("addM3u Mine http://list.example/a.m3u", "guide 9"), calls.toList())
    }

    @Test fun addFailureShowsFixedCopy() {
        assertEquals(202, postFor("/api/playlists", """{"mode":"xtream","server":"x.example","user":"a","pass":"pw"}""").first)
        val failed = awaitJob { it["phase"] == "failed" }
        assertEquals("The server rejected the username or password.", failed["message"])
        assertFalse(get("/api/job").second.contains("alice:pw"))
    }

    @Test fun incompleteIs400() {
        assertEquals(400 to """{"error":"incomplete"}""", postFor("/api/playlists", """{"mode":"xtream","server":"x.example","user":"a"}"""))
        assertEquals(400 to """{"error":"incomplete"}""", postFor("/api/playlists", """{"mode":"m3u","url":"  "}"""))
        assertEquals(400 to """{"error":"bad_request"}""", postFor("/api/playlists", "not json"))
        assertEquals(400 to """{"error":"bad_request"}""", postFor("/api/playlists", """{"mode":"other"}"""))
        assertEquals(200 to """{"queued":0}""", get("/api/job"))
    }

    @Test fun invalidMacIs400WithCopy() {
        val (code, body) = postFor("/api/playlists", """{"mode":"portal","portalUrl":"http://p.example/c/","mac":"00:1A:79:12"}""")
        assertEquals(400, code)
        val json = obj(body)
        assertEquals("invalid_mac", json["error"])
        assertEquals(INVALID_MAC, json["message"])
    }

    @Test fun fileModeIsTvOnly() {
        val file = """{"mode":"file","fileLocation":"/sdcard/a.m3u","fileName":"a.m3u"}"""
        assertEquals(400 to """{"error":"tv_only"}""", postFor("/api/playlists", file))
        assertEquals(400 to """{"error":"tv_only"}""", postFor("/api/playlists/1", file))
        // A saved file playlist can't be edited from the phone either.
        assertEquals(409 to """{"error":"tv_only"}""", postFor("/api/playlists/3", """{"mode":"m3u","url":"http://a.example"}"""))
        assertTrue(edits.isEmpty())
    }

    @Test fun deleteDuringLoadIs409() {
        refreshGate = CompletableDeferred()
        val (code, body) = postFor("/api/playlists/1/refresh")
        assertEquals(202, code)
        assertTrue(obj(body).containsKey("job"))
        awaitJob { it["kind"] == "refresh" && it["phase"] == "channels" }
        val busy = """{"error":"busy","message":"Busy loading Provider"}"""
        assertEquals(409 to busy, postFor("/api/playlists/1/delete"))
        assertEquals(409 to busy, postFor("/api/playlists/1/enabled", """{"enabled":false}"""))
        assertEquals(409 to busy, postFor("/api/playlists/1", """{"mode":"m3u","url":"http://a.example"}"""))
        // A second refresh queues behind the first instead.
        assertEquals(202, postFor("/api/playlists/1/refresh").first)
        assertEquals(1L, obj(get("/api/job").second).num("queued"))
        refreshGate.complete(Unit)
        awaitJob { it["phase"] == "done" && it.num("queued") == 0L }
        runBlocking { withTimeout(5_000) { while (jobs.isBusy(1)) delay(10) } }
        assertEquals(200, postFor("/api/playlists/1/delete").first)
        awaitCalls(3)
        assertEquals(listOf("refresh 1", "refresh 1", "delete 1"), calls.toList())
        assertEquals(404 to """{"error":"not_found"}""", postFor("/api/playlists/1/delete"))
    }

    @Test fun changedXtreamLoginNeedsThePassword() {
        // A new server or user without a password: the stored one is never sent there.
        assertEquals(
            400 to """{"error":"incomplete"}""",
            postFor("/api/playlists/1", """{"mode":"xtream","server":"http://other.example","user":"alice"}"""),
        )
        assertEquals(
            400 to """{"error":"incomplete"}""",
            postFor("/api/playlists/1", """{"mode":"xtream","server":"http://xt.example","user":"mallory","pass":null}"""),
        )
        assertTrue(edits.isEmpty())
        // A retyped password is used as typed.
        assertEquals(202, postFor("/api/playlists/1", """{"mode":"xtream","server":"http://other.example","user":"alice","pass":"new"}""").first)
        awaitEdits(1)
        assertEquals("new", (edits[0].second as SourceEdit.Xtream).creds.password)
    }

    @Test fun changedPortalDropsStoredPassword() {
        val base = """"mode":"portal","mac":"00:1A:79:12:34:56","portalLogin":"bob""""
        assertEquals(202, postFor("/api/playlists/2", """{$base,"portalUrl":"http://other.example/c/"}""").first)
        awaitEdits(1)
        assertEquals(null, (edits[0].second as SourceEdit.Portal).cfg.password)
        // Another MAC or login on the same portal drops it too.
        assertEquals(202, postFor("/api/playlists/2", """{"mode":"portal","portalUrl":"http://portal.example/c/","mac":"00:1A:79:00:00:01","portalLogin":"bob"}""").first)
        awaitEdits(2)
        assertEquals(null, (edits[1].second as SourceEdit.Portal).cfg.password)
        assertEquals(202, postFor("/api/playlists/2", """{"mode":"portal","portalUrl":"http://portal.example/c/","mac":"00:1A:79:12:34:56"}""").first)
        awaitEdits(3)
        assertEquals(null, (edits[2].second as SourceEdit.Portal).cfg.password)
        assertFalse(edits.any { (it.second as SourceEdit.Portal).cfg.password == "portal-secret" })
    }

    @Test fun newPlaylistIsBusyWhileItsGuideLoads() {
        guideGate = CompletableDeferred()
        assertEquals(202, postFor("/api/playlists", """{"mode":"m3u","name":"Mine","url":"http://list.example/a.m3u"}""").first)
        val guide = awaitJob { it["phase"] == "guide" }
        assertEquals(9L, guide.num("playlistId"))
        val busy = """{"error":"busy","message":"Busy loading Mine"}"""
        assertEquals(409 to busy, postFor("/api/playlists/9/delete"))
        assertEquals(409 to busy, postFor("/api/playlists/9/enabled", """{"enabled":false}"""))
        guideGate.complete(Unit)
        awaitJob { it["phase"] == "done" }
        runBlocking { withTimeout(5_000) { while (jobs.isBusy(9)) delay(10) } }
        assertEquals(200, postFor("/api/playlists/9/delete").first)
    }

    @Test fun toggleEnabledCallsChannelEdits() {
        assertEquals(200, postFor("/api/playlists/2/enabled", """{"enabled":true}""").first)
        assertEquals(200, postFor("/api/playlists/1/enabled", """{"enabled":false}""").first)
        assertEquals(400 to """{"error":"bad_request"}""", postFor("/api/playlists/1/enabled", """{"enabled":"yes"}"""))
        assertEquals(404 to """{"error":"not_found"}""", postFor("/api/playlists/1/other"))
        awaitCalls(3)
        // Turning on also runs the after-turn-on fill; turning off doesn't.
        assertEquals(listOf("enabled 2 true", "enabled 1 false"), calls.filter { it.startsWith("enabled") })
        assertEquals(listOf("afterEnabled 2"), calls.filter { it.startsWith("afterEnabled") })
    }

    @Test fun deleteAndTurnOnDontWaitForALoad() {
        // A load the phone didn't start (the TV's "Update all", say) holds the repository's lock.
        runBlocking { repoLock.lock() }
        afterEnabledGate = CompletableDeferred()
        val started = System.nanoTime()
        assertEquals(200 to """{"ok":true}""", postFor("/api/playlists/1/delete"))
        assertEquals(200 to """{"enabled":true}""", postFor("/api/playlists/2/enabled", """{"enabled":true}"""))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        awaitCalls(1)
        assertEquals(listOf("enabled 2 true"), calls.toList())
        assertTrue(stored.containsKey(1))
        // Once the load ends, the delete lands and the list no longer has the playlist.
        repoLock.unlock()
        afterEnabledGate.complete(Unit)
        awaitCalls(3)
        assertFalse(stored.containsKey(1))
        val ids = (anyJson.fromJson(get("/api/playlists").second) as List<*>).map { ((it as Map<*, *>)["id"] as Number).toLong() }
        assertEquals(listOf(2L, 3L), ids)
        assertEquals(setOf("enabled 2 true", "delete 1", "afterEnabled 2"), calls.toSet())
    }

    private companion object {
        val anyJson = Moshi.Builder().build().adapter(Any::class.java)
    }
}
