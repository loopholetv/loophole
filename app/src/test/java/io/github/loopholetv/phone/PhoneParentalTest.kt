package io.github.loopholetv.phone

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.parental.PinHasher
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.repo.FavouritesRepository
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneParentalTest {
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

    @Volatile private var parental = ParentalSettings()
    @Volatile private var now = 1_000_000L
    private var hashCalls = 0

    private val phone = PhoneServer(
        scope = scope,
        server = http,
        pairing = pairing,
        store = store,
        lanAddress = { loopback },
        watchNetwork = { AutoCloseable { } },
        asset = { null },
        tvName = { "Living room" },
        parental = PhoneParental(
            ready = { },
            settings = { parental },
            matches = { pin ->
                hashCalls++
                PinHasher.matches(pin, parental.pinSalt!!, parental.pinHash!!)
            },
            clock = { now },
        ),
    )

    private lateinit var db: AppDatabase
    private lateinit var edits: ChannelEdits
    private val calls = CopyOnWriteArrayList<String>()
    private val stored = ConcurrentHashMap<Long, PlaylistEntity>()
    private var p = 0L
    private var port = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        edits = ChannelEdits(db, ChannelOverlay(db))
        p = runBlocking {
            val entity = PlaylistEntity(
                name = "P", type = PlaylistType.XTREAM, url = null, xtreamServer = "http://xt.example",
                xtreamUser = "alice", xtreamPass = "secret", epgUrlsFromSource = "", manualEpgUrl = null,
                lastRefreshedAt = null, lastError = null,
            )
            val id = db.playlistDao().insert(entity)
            stored[id] = entity.copy(id = id)
            db.channelDao().insertAll(
                listOf(
                    ch(id, "a", 1, "News", 0), ch(id, "b", 2, "News", 0), ch(id, "c", 3, "News", 0),
                    ch(id, "d", 1, "Sport", 1), ch(id, "e", 2, "Sport", 1), ch(id, "f", 1, "Other", 2),
                ),
            )
            id
        }
        ChannelsApi(db, edits, FavouritesRepository(db)).register(phone)
        PlaylistApi(
            jobs = PhoneJobs(scope),
            work = scope,
            playlists = { stored.values.toList() },
            channelCounts = { emptyMap() },
            playlist = { stored[it] },
            addM3u = { _, _, _ -> ImportResult.Success(9, 1) },
            addXtream = { _, _, _ -> ImportResult.Success(9, 1) },
            addStalker = { _, _, _ -> ImportResult.Success(9, 1) },
            refreshGuide = { _, _ -> },
            updateSource = { id, _, _ ->
                calls += "update $id"
                ImportResult.Success(id, 1)
            },
            refreshPlaylist = { id, _ -> ImportResult.Success(id, 1) },
            guideAfterRefresh = { false },
            setEnabled = { id, on -> calls += "enabled $id $on" },
            afterEnabled = { },
            delete = { id ->
                calls += "delete $id"
                stored.remove(id)
            },
        ).register(phone)
        phone.onForeground(true)
        port = runBlocking { withTimeout(5_000) { phone.address.first { it != null }!!.port } }
        pairing.open()
        post("/api/pair", """{"code":"${pairing.code.value}"}""").use { assertEquals(200, it.code) }
    }

    @After fun tearDown() {
        phone.onForeground(false)
        http.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        db.close()
    }

    private fun ch(playlist: Long, key: String, number: Int, group: String, order: Int) = ChannelEntity(
        playlistId = playlist, key = key, number = number, name = "Name $key", streamUrl = "http://s.example.test/$key",
        logo = null, groupTitle = group, groupOrder = order, sortOrder = number, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = null,
    )

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun get(path: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { it.code to it.body.string() }

    private fun post(path: String, body: String) = client.newCall(
        Request.Builder().url(url(path)).post(body.toRequestBody("application/json".toMediaType()))
            .header("X-Loophole", "1").build(),
    ).execute()

    private fun postFor(path: String, body: String = "{}"): Pair<Int, String> = post(path, body).use { it.code to it.body.string() }

    @Suppress("UNCHECKED_CAST")
    private fun keys(): List<Any?> =
        ((anyJson.fromJson(get("/api/channels?playlist=$p").second) as Map<String, Any?>)["rows"] as List<Map<String, Any?>>).map { it["key"] }

    @Suppress("UNCHECKED_CAST")
    private fun groupTitles(): List<Any?> =
        (anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>).map { it["title"] }

    private fun setPin(settingsLock: SettingsLock = SettingsLock.OFF, minutes: Int = 15) {
        val salt = PinHasher.newSalt()
        parental = ParentalSettings(PinHasher.hash("1234", salt), salt, minutes, settingsLock)
    }

    private fun lock() = runBlocking {
        edits.setLocked(ChannelRef(p, "c"), true)
        edits.setGroupLocked(p, "Sport", true)
    }

    private fun enterPin() = postFor("/api/pin", """{"pin":"1234"}""")

    private val deleteUrl get() = "/api/playlists/$p/delete"

    @Test fun lockedItemsNeverListed() {
        lock()
        setPin()
        for (tvUnlocked in listOf(false, true)) {
            runBlocking { ChannelOverlay(db).setLockState(unlocked = tvUnlocked) }
            assertEquals(listOf("a", "b", "f"), keys())
            assertEquals(listOf("News", "Other"), groupTitles())
            assertFalse(get("/api/channels?playlist=$p").second.contains("locked"))
            assertFalse(get("/api/groups?playlist=$p").second.contains("locked"))
            assertEquals(listOf<Any?>(), run {
                @Suppress("UNCHECKED_CAST")
                ((anyJson.fromJson(get("/api/channels?playlist=$p&group=Sport").second) as Map<String, Any?>)["rows"] as List<Any?>)
            })
        }
        // The group count leaves out the locked channel.
        assertTrue(get("/api/groups?playlist=$p").second.contains("\"count\":2"))
    }

    @Test fun editLockedChannel404() {
        lock()
        setPin()
        val notFound = 404 to """{"error":"not_found"}"""
        assertEquals(notFound, postFor("/api/channels/rename", """{"playlist":$p,"key":"c","name":"X"}"""))
        assertEquals(notFound, postFor("/api/channels/hidden", """{"playlist":$p,"key":"d","hidden":true}"""))
        assertEquals(notFound, postFor("/api/groups/hidden", """{"playlist":$p,"title":"Sport","hidden":true}"""))
        assertEquals(200, postFor("/api/channels/hidden", """{"playlist":$p,"key":"a","hidden":true}""").first)
    }

    @Test fun pinRequiredWhenSettingsLockOn() {
        setPin(SettingsLock.ALL)
        val need = 401 to """{"pin":true}"""
        assertEquals(need, get("/api/channels?playlist=$p"))
        assertEquals(need, get("/api/playlists"))
        assertEquals(need, postFor("/api/groups/hidden", """{"playlist":$p,"title":"News","hidden":true}"""))
        assertEquals(403 to """{"ok":false}""", postFor("/api/pin", """{"pin":"0000"}"""))
        assertEquals(need, get("/api/playlists"))
        assertEquals(200 to """{"ok":true}""", enterPin())
        assertEquals(200, get("/api/playlists").first)
        assertEquals(listOf<Any?>("a", "b", "c", "d", "e", "f"), keys())
        // The page still loads the status without a PIN.
        assertEquals(200, get("/api/status").first)
    }

    @Test fun pinUnlocksSessionNotTv() {
        setPin(SettingsLock.ALL, minutes = 5)
        assertEquals(200 to """{"ok":true}""", enterPin())
        assertFalse(runBlocking { db.lockDao().unlocked().first() })
        assertEquals(200, get("/api/playlists").first)
        now += 4 * 60_000L
        assertEquals(200, get("/api/playlists").first)
        now += 2 * 60_000L
        assertEquals(401 to """{"pin":true}""", get("/api/playlists"))
    }

    @Test fun untilLeaveMeansFifteenMinutesOnThePhone() {
        setPin(SettingsLock.ALL, minutes = 0)
        enterPin()
        now += 14 * 60_000L
        assertEquals(200, get("/api/playlists").first)
        now += 2 * 60_000L
        assertEquals(401, get("/api/playlists").first)
    }

    @Test fun wrongPinLimitPerSession() {
        setPin(SettingsLock.ALL)
        repeat(4) { assertEquals(403 to """{"ok":false}""", postFor("/api/pin", """{"pin":"0000"}""")) }
        val (code, body) = postFor("/api/pin", """{"pin":"0000"}""")
        assertEquals(429, code)
        assertEquals("""{"wait":30}""", body)
        // The right PIN is refused during the wait, then accepted after it.
        assertEquals(429, enterPin().first)
        now += 31_000L
        assertEquals(200, enterPin().first)
    }

    @Test fun malformedPinIsAWrongTryWithoutHashing() {
        setPin(SettingsLock.ALL)
        for (bad in listOf("", "12345", "١٢٣٤")) {
            assertEquals(403, postFor("/api/pin", """{"pin":"$bad"}""").first)
        }
        assertEquals(403, postFor("/api/pin", """{"pin":1234}""").first)
        assertEquals(0, hashCalls)
        // The fifth in a row trips the limit.
        assertEquals(429, postFor("/api/pin", """{"pin":"12"}""").first)
    }

    @Test fun deletePlaylistNeedsPinWhenPinSet() {
        setPin(SettingsLock.OFF)
        assertEquals(401 to """{"pin":true}""", postFor(deleteUrl))
        val edit = """{"mode":"xtream","server":"http://other.example","user":"alice"}"""
        assertEquals(401 to """{"pin":true}""", postFor("/api/playlists/$p", edit))
        assertTrue(calls.isEmpty())
        assertEquals(200, enterPin().first)
        assertEquals(200 to """{"ok":true}""", postFor(deleteUrl))
        runBlocking { withTimeout(5_000) { while (calls.isEmpty()) delay(10) } }
        assertEquals(listOf("delete $p"), calls.toList())
    }

    @Test fun unchangedSourceEditNeedsNoPin() {
        setPin(SettingsLock.OFF)
        val same = """{"mode":"xtream","server":"http://xt.example","user":"alice"}"""
        assertEquals(202, postFor("/api/playlists/$p", same).first)
    }

    @Test fun disablePlaylistNeedsNoPin() {
        setPin(SettingsLock.OFF)
        assertEquals(200, postFor("/api/playlists/$p/enabled", """{"enabled":false}""").first)
        assertEquals(202, postFor("/api/playlists", """{"mode":"m3u","name":"New","url":"http://x.example/a.m3u"}""").first)
    }

    @Test fun noPinNoChange() {
        lock()
        parental = ParentalSettings(settingsLock = SettingsLock.ALL)
        assertEquals(200, get("/api/playlists").first)
        assertEquals(200, postFor("/api/channels/hidden", """{"playlist":$p,"key":"a","hidden":true}""").first)
        assertEquals(200 to """{"ok":true}""", postFor(deleteUrl))
        assertEquals(listOf("a", "b", "f"), keys())
    }

    @Test fun groupMoveCountsOnlyListedGroups() {
        lock() // Sport is locked and sits between News and Other.
        setPin()
        assertEquals(200, postFor("/api/groups/move", """{"playlist":$p,"title":"News","to":1}""").first)
        assertEquals(listOf<Any?>("Other", "News"), groupTitles())
        runBlocking { edits.setGroupLocked(p, "Sport", false) }
        assertEquals(listOf<Any?>("Sport", "Other", "News"), groupTitles())
    }

    @Test fun channelMoveCountsOnlyListedChannels() {
        runBlocking { edits.setLocked(ChannelRef(p, "a"), true) }
        setPin()
        assertEquals(200, postFor("/api/channels/move", """{"playlist":$p,"key":"b","to":1}""").first)
        assertEquals(listOf<Any?>("c", "b"), keysIn("News"))
        runBlocking { edits.setLocked(ChannelRef(p, "a"), false) }
        assertEquals(listOf<Any?>("a", "c", "b"), keysIn("News"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun keysIn(group: String): List<Any?> =
        ((anyJson.fromJson(get("/api/channels?playlist=$p&group=$group").second) as Map<String, Any?>)["rows"] as List<Map<String, Any?>>).map { it["key"] }

    @Test fun wrongTriesAreCountedAcrossPhones() {
        setPin(SettingsLock.ALL)
        val other = client.newBuilder().cookieJar(MemoryCookieJar()).build()
        pairing.open()
        other.newCall(
            Request.Builder().url(url("/api/pair")).header("X-Loophole", "1")
                .post("""{"code":"${pairing.code.value}"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { assertEquals(200, it.code) }
        fun wrongOn(c: OkHttpClient) = c.newCall(
            Request.Builder().url(url("/api/pin")).header("X-Loophole", "1")
                .post("""{"pin":"0000"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.code }
        assertEquals(403, wrongOn(client))
        assertEquals(403, wrongOn(client))
        assertEquals(403, wrongOn(other))
        assertEquals(403, wrongOn(other))
        assertEquals(429, wrongOn(client))
        // The other phone is held too, even with the right PIN.
        assertEquals(429, other.newCall(
            Request.Builder().url(url("/api/pin")).header("X-Loophole", "1")
                .post("""{"pin":"1234"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.code })
    }

    @Test fun changingThePinEndsPhoneUnlocks() {
        setPin(SettingsLock.ALL)
        assertEquals(200, enterPin().first)
        assertEquals(200, get("/api/playlists").first)
        setPin(SettingsLock.ALL)
        assertEquals(401 to """{"pin":true}""", get("/api/playlists"))
    }

    @Test fun removingThePinEndsPhoneUnlocks() {
        setPin(SettingsLock.ALL)
        assertEquals(200, enterPin().first)
        assertEquals(200, get("/api/playlists").first)
        parental = ParentalSettings()
        assertEquals(200, get("/api/playlists").first)
        setPin(SettingsLock.ALL)
        assertEquals(401 to """{"pin":true}""", get("/api/playlists"))
    }
}

private val anyJson = Moshi.Builder().build().adapter(Any::class.java)
