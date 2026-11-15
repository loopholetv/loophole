package io.github.loopholetv.phone

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.repo.FavouritesRepository
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class ChannelsApiTest {
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

    private lateinit var db: AppDatabase
    private lateinit var favourites: FavouritesRepository
    private var p = 0L
    private var port = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        favourites = FavouritesRepository(db)
        p = runBlocking {
            val id = db.playlistDao().insert(
                io.github.loopholetv.data.db.PlaylistEntity(
                    name = "P", type = io.github.loopholetv.data.db.PlaylistType.M3U, url = null, xtreamServer = null,
                    xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
                    lastRefreshedAt = null, lastError = null,
                ),
            )
            db.channelDao().insertAll(
                listOf(
                    ch(id, "a", 1, "News", 0), ch(id, "b", 2, "News", 0), ch(id, "c", 3, "News", 0),
                    ch(id, "d", 1, "Sport", 1), ch(id, "e", 2, "Sport", 1),
                ),
            )
            id
        }
        ChannelsApi(db, ChannelEdits(db, ChannelOverlay(db)), favourites).register(phone)
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

    private fun postFor(path: String, body: String): Pair<Int, String> = post(path, body).use { it.code to it.body.string() }

    @Suppress("UNCHECKED_CAST")
    private fun rows(path: String): List<Map<String, Any?>> =
        (anyJson.fromJson(get(path).second) as Map<String, Any?>)["rows"] as List<Map<String, Any?>>

    private fun keys(path: String) = rows(path).map { it["key"] }

    private fun channel(key: String) = runBlocking { db.overlayDao().channelByKey(p, key)!! }

    @Test fun listsChannelsAndGroups() {
        assertEquals(listOf("a", "b", "c", "d", "e"), keys("/api/channels?playlist=$p"))
        assertEquals(listOf("d", "e"), keys("/api/channels?playlist=$p&group=Sport"))
        assertEquals(listOf("a", "b"), keys("/api/channels?playlist=$p&limit=2"))
        @Suppress("UNCHECKED_CAST")
        val groups = anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>
        assertEquals(listOf("News", "Sport"), groups.map { it["title"] })
        assertEquals(3L, (groups[0]["count"] as Number).toLong())
        assertEquals(false, groups[0]["hidden"])
    }

    @Test fun morePagesAreFlagged() {
        val (_, body) = get("/api/channels?playlist=$p&limit=2")
        assertTrue(body.contains("\"more\":true"))
        assertTrue(get("/api/channels?playlist=$p&limit=2&offset=4").second.contains("\"more\":false"))
    }

    @Test fun limitIsClamped() {
        assertEquals(1, rows("/api/channels?playlist=$p&limit=0").size)
        assertEquals(1, rows("/api/channels?playlist=$p&limit=-5").size)
        assertEquals(5, rows("/api/channels?playlist=$p&limit=100000").size)
        assertEquals(5, rows("/api/channels?playlist=$p").size)
    }

    @Test fun renameShowsOnTv() {
        assertEquals(200, postFor("/api/channels/rename", """{"playlist":$p,"key":"a","name":"Morning News"}""").first)
        assertEquals("Morning News", channel("a").customName)
        assertEquals("Morning News", rows("/api/channels?playlist=$p&q=morning").single()["name"])
        assertEquals("Name a", rows("/api/channels?playlist=$p&q=morning").single()["sourceName"])
        assertFalse(rows("/api/channels?playlist=$p&q=morning").single().containsKey("logo"))
        assertEquals(200, postFor("/api/channels/rename", """{"playlist":$p,"key":"a","name":"  "}""").first)
        assertEquals(null, channel("a").customName)
    }

    @Test fun longNamesAreCutAsOnTheTv() {
        // Code points, not chars: an emoji is never cut in half.
        val long = "\uD83D\uDCFA".repeat(150) + "x".repeat(150)
        val cut = "\uD83D\uDCFA".repeat(150) + "x".repeat(50)
        assertEquals(200, postFor("/api/channels/rename", """{"playlist":$p,"key":"a","name":"$long"}""").first)
        assertEquals(cut, channel("a").customName)
        assertEquals(200, postFor("/api/groups/rename", """{"playlist":$p,"title":"News","name":"$long"}""").first)
        @Suppress("UNCHECKED_CAST")
        val groups = anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>
        assertEquals(cut, groups.first { it["title"] == "News" }["label"])
    }

    @Test fun favouriteIsIdempotent() {
        val body = """{"playlist":$p,"key":"b","favourite":true}"""
        assertEquals(200, postFor("/api/channels/favourite", body).first)
        assertEquals(200, postFor("/api/channels/favourite", body).first)
        assertTrue(runBlocking { favourites.isFavourite(channel("b")) })
        assertEquals(true, rows("/api/channels?playlist=$p&q=Name b").single()["favourite"])
        val off = """{"playlist":$p,"key":"b","favourite":false}"""
        postFor("/api/channels/favourite", off)
        postFor("/api/channels/favourite", off)
        assertFalse(runBlocking { favourites.isFavourite(channel("b")) })
    }

    @Test fun hideChannelKeepsItListed() {
        assertEquals(200, postFor("/api/channels/hidden", """{"playlist":$p,"key":"c","hidden":true}""").first)
        assertFalse(channel("c").visible)
        assertEquals(true, rows("/api/channels?playlist=$p").first { it["key"] == "c" }["hidden"])
    }

    @Test fun hideGroupHidesItsChannels() {
        assertEquals(200, postFor("/api/groups/hidden", """{"playlist":$p,"title":"Sport","hidden":true}""").first)
        assertFalse(channel("d").visible)
        assertFalse(channel("e").visible)
        assertTrue(channel("a").visible)
        @Suppress("UNCHECKED_CAST")
        val groups = anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>
        assertEquals(true, groups.first { it["title"] == "Sport" }["hidden"])
    }

    @Test fun renameGroup() {
        assertEquals(200, postFor("/api/groups/rename", """{"playlist":$p,"title":"News","name":"Headlines"}""").first)
        @Suppress("UNCHECKED_CAST")
        val groups = anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>
        assertEquals("Headlines", groups.first { it["title"] == "News" }["label"])
        assertEquals(true, groups.first { it["title"] == "News" }["renamed"])
    }

    @Test fun moveUsesMoveTo() {
        assertEquals(200, postFor("/api/channels/move", """{"playlist":$p,"key":"a","to":2}""").first)
        assertEquals(listOf("b", "c", "a"), keys("/api/channels?playlist=$p&group=News"))
        assertEquals(200, postFor("/api/groups/move", """{"playlist":$p,"title":"Sport","to":0}""").first)
        @Suppress("UNCHECKED_CAST")
        val groups = anyJson.fromJson(get("/api/groups?playlist=$p").second) as List<Map<String, Any?>>
        assertEquals(listOf("Sport", "News"), groups.map { it["title"] })
    }

    @Test fun unknownChannelIs404() {
        val missing = 404 to """{"error":"not_found"}"""
        assertEquals(missing, postFor("/api/channels/rename", """{"playlist":$p,"key":"zzz","name":"x"}"""))
        assertEquals(missing, postFor("/api/channels/hidden", """{"playlist":$p,"key":"zzz","hidden":true}"""))
        assertEquals(missing, postFor("/api/channels/favourite", """{"playlist":$p,"key":"zzz","favourite":true}"""))
        assertEquals(missing, postFor("/api/channels/move", """{"playlist":$p,"key":"zzz","to":0}"""))
        assertEquals(missing, postFor("/api/groups/rename", """{"playlist":$p,"title":"zzz","name":"x"}"""))
        assertEquals(missing, postFor("/api/groups/hidden", """{"playlist":$p,"title":"zzz","hidden":true}"""))
        assertEquals(missing, postFor("/api/groups/move", """{"playlist":$p,"title":"zzz","to":0}"""))
        assertEquals(missing, postFor("/api/channels/rename", """{"playlist":999,"key":"a","name":"x"}"""))
        assertEquals(missing, get("/api/channels?playlist=999"))
        assertEquals(missing, get("/api/groups?playlist=999"))
    }

    @Test fun unknownSuffixesAre404AndBadBodiesAre400() {
        assertEquals(404, get("/api/channelsX?playlist=$p").first)
        assertEquals(404 to """{"error":"not_found"}""", get("/api/channels/rename"))
        assertEquals(404 to """{"error":"not_found"}""", postFor("/api/channels/other", "{}"))
        assertEquals(404 to """{"error":"not_found"}""", postFor("/api/groups/rename/x", "{}"))
        assertEquals(400 to """{"error":"bad_request"}""", postFor("/api/channels/hidden", """{"playlist":$p,"key":"a","hidden":"yes"}"""))
        assertEquals(400 to """{"error":"bad_request"}""", postFor("/api/channels/move", "not json"))
        assertEquals(400 to """{"error":"bad_request"}""", get("/api/channels?playlist=abc"))
        assertEquals(400 to """{"error":"bad_request"}""", get("/api/channels"))
    }

    private companion object {
        val anyJson = Moshi.Builder().build().adapter(Any::class.java)
    }
}
