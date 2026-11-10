package io.github.loopholetv.data.repo

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.db.loadAll
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val GENERIC = "Something went wrong while loading the playlist."

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: PlaylistRepository
    private val http = OkHttpClient()
    private val xtream = XtreamClient(http)
    private var clock = Instant.ofEpochMilli(1_000_000)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        repo = PlaylistRepository(db, http, xtream, now = { clock })
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    // --- helpers ---

    private fun res(path: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$path")!!.readBytes().decodeToString()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun url(path: String = "/list.m3u") = server.url(path).toString()

    private fun entry(name: String, url: String, attrs: String = "") =
        "#EXTINF:-1${if (attrs.isEmpty()) "" else " $attrs"},$name\n$url\n"

    private fun generated(count: Int, prefix: String = "c") = buildString {
        append("#EXTM3U\n")
        for (i in 1..count) append(entry("Channel $i", "http://stream.example.test/$prefix$i.ts", "group-title=\"G${i % 20}\""))
    }

    private suspend fun channels(playlistId: Long): List<ChannelEntity> = db.channelDao().channelsOf(playlistId)

    private suspend fun addFive(): Long {
        enqueue(res("import/five.m3u"))
        return successOf(repo.addM3u("Five", url()) {}).playlistId
    }

    /** Fails with the import error instead of an opaque ClassCastException when [result] isn't Success. */
    private fun successOf(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("import failed: ${result.error} ${result.message}")
    }

    private suspend fun totalChannels() = db.channelDao().groups().first().sumOf { it.count }

    private fun creds() = XtreamCredentials("http://${server.hostName}:${server.port}", "user1", "pass1")

    private fun xtreamServer(auth: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.queryParameter("action")) {
                    null -> res("xtream/$auth")
                    "get_live_categories" -> res("xtream/categories.json")
                    "get_live_streams" -> res("xtream/live_streams.json")
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
    }

    // --- M3U add ---

    @Test fun addM3uImportsChannelsAndGroups() = runTest {
        enqueue(res("import/five.m3u"))
        val r = repo.addM3u("Five", url()) {}

        val playlist = repo.playlists().first().single()
        assertEquals(ImportResult.Success(playlist.id, 5), r)
        assertEquals("Five", playlist.name)
        assertEquals(PlaylistType.M3U, playlist.type)
        assertEquals(url(), playlist.url)
        assertEquals("http://epg.example.test/guide.xml\nhttp://epg.example.test/extra.xml.gz", playlist.epgUrlsFromSource)
        assertEquals(clock, playlist.lastRefreshedAt)
        assertNull(playlist.lastError)

        assertEquals(
            listOf(Triple("Sports", 0, 2), Triple("News", 1, 2), Triple("Movies", 2, 1)),
            db.channelDao().groups().first().map { Triple(it.title, it.order, it.count) },
        )
        val sport = channels(playlist.id).first()
        assertEquals("http://stream.example.test/s1.ts", sport.key)
        assertEquals("http://stream.example.test/s1.ts", sport.streamUrl)
        assertEquals("Sport One", sport.name)
        assertEquals("s1.test", sport.tvgId)
        assertEquals("http://img.example.test/s1.png", sport.logo)
        assertEquals(1, sport.number)
        assertEquals(0, sport.sortOrder)
        assertNull(sport.epgChannelId)

        val page = db.channelDao().groupChannels(playlist.id, "News")
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false))
        assertEquals(listOf("News One", "News Two"), (page as PagingSource.LoadResult.Page).data.map { it.name })
        val all = db.channelDao().allChannels()
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false))
        assertEquals(listOf(1, 2, 3, 4, 5), (all as PagingSource.LoadResult.Page).data.map { it.number })
    }

    @Test fun numbersFromChnoOtherwisePosition() = runTest {
        enqueue(
            "#EXTM3U\n" +
                entry("A", "http://s.test/a.ts", "tvg-chno=\"10\"") +
                entry("B", "http://s.test/b.ts") +
                entry("C", "http://s.test/c.ts", "tvg-chno=\"20\"") +
                entry("D", "http://s.test/d.ts", "tvg-chno=\"20\"") +
                entry("E", "http://s.test/e.ts", "tvg-chno=\"abc\"") +
                entry("F", "http://s.test/f.ts", "tvg-chno=\"0\""),
        )
        val r = successOf(repo.addM3u("Numbers", url()) {})
        val byName = channels(r.playlistId).associate { it.name to it.number }
        // Unique chno 10 kept; the rest (no/invalid/duplicate chno) follow above the highest chno, in order.
        assertEquals(mapOf("A" to 10, "B" to 11, "C" to 12, "D" to 13, "E" to 14, "F" to 15), byName)
        assertEquals("A", db.channelDao().byNumber(10)?.name)
    }

    @Test fun partialChnoNeverCollidesWithFallbackNumbers() = runTest {
        enqueue("#EXTM3U\n" + entry("A", "http://s.test/a.ts", "tvg-chno=\"2\"") + entry("B", "http://s.test/b.ts"))
        val r = successOf(repo.addM3u("Partial", url()) {})
        assertEquals(mapOf("A" to 2, "B" to 3), channels(r.playlistId).associate { it.name to it.number })
        assertEquals("A", db.channelDao().byNumber(2)?.name)
        assertEquals("B", db.channelDao().byNumber(3)?.name)
    }

    @Test fun fallbackNumbersAreConsecutiveAcrossSeparateRuns() = runTest {
        enqueue(
            "#EXTM3U\n" +
                entry("X", "http://s.test/x.ts") +
                entry("Y", "http://s.test/y.ts", "tvg-chno=\"5\"") +
                entry("Z", "http://s.test/z.ts") +
                entry("W", "http://s.test/w.ts", "tvg-chno=\"7\"") +
                entry("V", "http://s.test/v.ts", "tvg-chno=\"7\"") +
                entry("U", "http://s.test/u.ts", "tvg-chno=\"1\""),
        )
        val r = successOf(repo.addM3u("Runs", url()) {})
        val numbers = channels(r.playlistId).associate { it.name to it.number }
        assertEquals(mapOf("X" to 6, "Y" to 5, "Z" to 7, "W" to 8, "V" to 9, "U" to 1), numbers)

        // Refresh with the same body must give the same numbers (ids and numbers stable).
        enqueue(
            "#EXTM3U\n" +
                entry("X", "http://s.test/x.ts") +
                entry("Y", "http://s.test/y.ts", "tvg-chno=\"5\"") +
                entry("Z", "http://s.test/z.ts") +
                entry("W", "http://s.test/w.ts", "tvg-chno=\"7\"") +
                entry("V", "http://s.test/v.ts", "tvg-chno=\"7\"") +
                entry("U", "http://s.test/u.ts", "tvg-chno=\"1\""),
        )
        repo.refresh(r.playlistId) {}
        assertEquals(numbers, channels(r.playlistId).associate { it.name to it.number })
    }

    @Test fun blankFieldsBecomeNullAndBlankNamesFallBack() = runTest {
        enqueue(
            "#EXTM3U\n" +
                entry("", "http://s.test/a.ts", "tvg-name=\"From Tvg\" tvg-logo=\"\" tvg-id=\" \" group-title=\"\" catchup=\"\"") +
                entry(" ", "http://s.test/skip.ts", "tvg-name=\"\"") +
                entry("B", "http://s.test/b.ts") +
                entry("Dup", "http://s.test/b.ts"),
        )
        val r = successOf(repo.addM3u("Blanks", url()) {})
        assertEquals(2, r.channelCount)
        val (a, b) = channels(r.playlistId)
        assertEquals("From Tvg", a.name)
        assertNull(a.logo)
        assertNull(a.tvgId)
        assertNull(a.catchupType)
        assertEquals("Uncategorised", a.groupTitle)
        assertEquals("B", b.name)
        assertEquals(2, b.number)
    }

    // --- Xtream add ---

    @Test fun addXtreamImportsWithLiveUrls() = runTest {
        xtreamServer("auth_ok.json")
        val c = creds()
        val r = repo.addXtream("Xt", c) {}

        val playlist = repo.playlists().first().single()
        assertEquals(ImportResult.Success(playlist.id, 4), r)
        assertEquals(PlaylistType.XTREAM, playlist.type)
        assertEquals(c.server, playlist.xtreamServer)
        assertEquals("user1", playlist.xtreamUser)
        assertEquals("pass1", playlist.xtreamPass)
        assertEquals(xtream.epgUrl(c), playlist.epgUrlsFromSource)

        val list = channels(playlist.id)
        assertEquals(listOf("101", "102", "103", "104"), list.map { it.key })
        list.forEach { assertEquals(xtream.liveUrl(c, it.key.toLong()), it.streamUrl) }
        val one = list[0]
        assertEquals("Channel One", one.name)
        assertEquals(5, one.number)
        assertEquals("one.test", one.tvgId)
        assertEquals("http://img.example.test/1.png", one.logo)
        assertEquals("News", one.groupTitle)
        assertEquals("xc", one.catchupType)
        assertEquals(3, one.catchupDays)
        assertEquals("Sports", list[1].groupTitle)
        assertNull(list[1].logo)
        assertNull(list[1].tvgId)
        assertEquals("Uncategorised", list[2].groupTitle)
        assertNull(list[2].catchupType)
        assertEquals(9, list[3].number)
    }

    @Test fun xtreamRefreshStoresArchiveInfo() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.queryParameter("action")) {
                    null -> """{"server_info":{"timezone":"Europe/Berlin"},
                        "user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}"""
                    "get_live_categories" -> res("xtream/categories.json")
                    "get_live_streams" -> res("xtream/live_streams.json")
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        val id = successOf(repo.addXtream("Xt", creds()) {}).playlistId
        val p = db.playlistDao().byId(id)!!
        assertEquals("Europe/Berlin", p.xtreamTimeZone)
        assertEquals("ts", p.xtreamArchiveExt)
    }

    @Test fun tvArchiveWithZeroDaysIsNotCatchup() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.queryParameter("action")) {
                    null -> res("xtream/auth_ok.json")
                    "get_live_categories" -> "[]"
                    "get_live_streams" ->
                        """[{"stream_id":1,"name":"A","tv_archive":1,"tv_archive_duration":0},
                            {"stream_id":2,"name":"B","tv_archive":1,"tv_archive_duration":"2"}]"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        val id = successOf(repo.addXtream("Xt", creds()) {}).playlistId
        val list = channels(id).associateBy { it.key }
        assertNull(list["1"]!!.catchupType)
        assertEquals("xc", list["2"]!!.catchupType)
        assertEquals(2, list["2"]!!.catchupDays)
    }

    @Test fun maxCatchupDaysIgnoresHiddenAndNull() = runTest {
        val p = db.addPlaylist()
        assertNull(db.channelDao().maxCatchupDays())
        db.insertChannels(
            channel(p, "a", 1).copy(catchupType = "xc", catchupDays = 3),
            channel(p, "b", 2).copy(catchupType = "shift", catchupDays = null),
            channel(p, "c", 3, visible = false).copy(catchupType = "xc", catchupDays = 7),
            channel(p, "d", 4),
        )
        assertEquals(3, db.channelDao().maxCatchupDays())
    }

    @Test fun xtreamBadLoginGivesAuthFailed() = runTest {
        xtreamServer("auth_rejected.json")
        val r = repo.addXtream("Xt", creds()) {}
        assertTrue("got $r", r is ImportResult.Failure)
        assertEquals(ImportError.AuthFailed, (r as ImportResult.Failure).error)
        assertTrue(repo.playlists().first().isEmpty())
    }

    // --- M3U failures ---

    @Test fun unreachableUrlGivesUnreachable() = runTest {
        val dead = MockWebServer().also { it.start() }
        val deadUrl = dead.url("/list.m3u").toString()
        dead.shutdown()
        val r = repo.addM3u("Dead", deadUrl) {}
        assertEquals(ImportError.Unreachable, (r as ImportResult.Failure).error)
        assertTrue(repo.playlists().first().isEmpty())
    }

    @Test fun htmlResponseGivesNotAPlaylist() = runTest {
        enqueue("\n  <!DOCTYPE html>\n<html><body>Login</body></html>\n")
        val r = repo.addM3u("Html", url()) {}
        assertEquals(ImportError.NotAPlaylist, (r as ImportResult.Failure).error)
    }

    @Test fun textWithoutEntriesGivesNotAPlaylist() = runTest {
        enqueue("{\"user_info\":{\"auth\":1}}")
        val r = repo.addM3u("Json", url()) {}
        assertEquals(ImportError.NotAPlaylist, (r as ImportResult.Failure).error)
    }

    @Test fun emptyPlaylistGivesEmptyPlaylist() = runTest {
        enqueue("#EXTM3U\n#EXTINF:-1,\nhttp://s.test/nameless.ts\n")
        val r = repo.addM3u("Empty", url()) {}
        assertEquals(ImportError.EmptyPlaylist, (r as ImportResult.Failure).error)
        assertTrue(repo.playlists().first().isEmpty())
    }

    @Test fun http401GivesAuthFailedAndOtherCodesGiveHttp() = runTest {
        enqueue("nope", 401)
        assertEquals(ImportError.AuthFailed, (repo.addM3u("A", url()) {} as ImportResult.Failure).error)
        enqueue("boom", 503)
        assertEquals(ImportError.Http(503), (repo.addM3u("B", url()) {} as ImportResult.Failure).error)
    }

    @Test fun errorMessageIsRedacted() = runTest {
        enqueue("missing", 404)
        val r = repo.addM3u("Secret", url("/live/u/p/x")) {}
        r as ImportResult.Failure
        assertEquals(ImportError.Http(404), r.error)
        assertTrue(r.message, r.message.contains("404"))
        assertFalse(r.message, r.message.contains("u/p"))

        enqueue("missing", 404)
        val q = repo.addM3u("Secret", url("/get.php?username=alice&password=hunter22&type=m3u")) {}
        q as ImportResult.Failure
        assertFalse(q.message, q.message.contains("alice"))
        assertFalse(q.message, q.message.contains("hunter22"))
    }

    @Test fun addM3uFailureLeavesNoPlaylistRow() = runTest {
        server.enqueue(
            MockResponse().setBody(generated(2_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val r = repo.addM3u("Broken", url()) {}
        assertEquals(ImportError.Unreachable, (r as ImportResult.Failure).error)
        assertTrue(repo.playlists().first().isEmpty())
        assertEquals(0, totalChannels())
    }

    // --- refresh ---

    @Test fun refreshKeepsIdsForUnchangedKeys() = runTest {
        val id = addFive()
        val before = channels(id).associate { it.key to it.id }

        enqueue(
            res("import/five.m3u").replace(",News Two", ",News Two Renamed") +
                entry("New One", "http://stream.example.test/new1.ts", "group-title=\"News\""),
        )
        clock = Instant.ofEpochMilli(2_000_000)
        val r = repo.refresh(id) {}

        assertEquals(ImportResult.Success(id, 6), r)
        val after = channels(id)
        before.forEach { (key, oldId) -> assertEquals(key, oldId, after.single { it.key == key }.id) }
        assertEquals("News Two Renamed", after.single { it.key == "http://stream.example.test/n2.ts" }.name)
        assertEquals(clock, repo.playlists().first().single().lastRefreshedAt)
    }

    @Test fun refreshRemovesVanishedChannels() = runTest {
        val id = addFive()
        enqueue(
            "#EXTM3U\n" +
                entry("Sport One", "http://stream.example.test/s1.ts", "group-title=\"Sports\"") +
                entry("Movie One", "http://stream.example.test/m1.ts", "group-title=\"Movies\"") +
                entry("News Two", "http://stream.example.test/n2.ts", "group-title=\"News\""),
        )
        val r = repo.refresh(id) {}
        assertEquals(ImportResult.Success(id, 3), r)
        assertEquals(
            listOf("http://stream.example.test/s1.ts", "http://stream.example.test/m1.ts", "http://stream.example.test/n2.ts"),
            channels(id).map { it.key },
        )
        assertEquals(
            listOf(Triple("Sports", 0, 1), Triple("Movies", 1, 1), Triple("News", 2, 1)),
            db.channelDao().groups().first().map { Triple(it.title, it.order, it.count) },
        )
    }

    @Test fun refreshFailureMidStreamKeepsOldChannels() = runTest {
        val id = addFive()
        val before = channels(id)
        val refreshedAt = repo.playlists().first().single().lastRefreshedAt

        server.enqueue(
            MockResponse().setBody(generated(2_000, prefix = "fresh"))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        clock = Instant.ofEpochMilli(3_000_000)
        val r = repo.refresh(id) { progress += it }

        assertEquals(ImportError.Unreachable, (r as ImportResult.Failure).error)
        assertTrue("some batches were written before the disconnect: $progress", progress.isNotEmpty())
        assertEquals(before, channels(id))
        val playlist = repo.playlists().first().single()
        assertNotNull(playlist.lastError)
        assertEquals(r.message, playlist.lastError)
        assertEquals(refreshedAt, playlist.lastRefreshedAt)
    }

    @Test fun shortPathCredentialsNeverReachStoredError() = runTest {
        val u = url("/u/johnsmith/letmein/m3u")
        enqueue(res("import/five.m3u"))
        val id = successOf(repo.addM3u("P", u) {}).playlistId
        enqueue("no", 403)
        val r = repo.refresh(id) {} as ImportResult.Failure
        assertEquals(ImportError.AuthFailed, r.error)
        val stored = repo.playlists().first().single().lastError.orEmpty()
        for (text in listOf(r.message, stored)) {
            assertFalse(text, "johnsmith" in text)
            assertFalse(text, "letmein" in text)
            assertTrue(text, "403" in text)
        }
        enqueue("no", 500)
        val r2 = repo.refresh(id) {} as ImportResult.Failure
        assertFalse(r2.message, "johnsmith" in r2.message || "letmein" in r2.message)
    }

    @Test fun m3uUrlWithoutSchemeGetsHttp() = runTest {
        enqueue(res("import/five.m3u"))
        val bare = url().removePrefix("http://")
        val r = repo.addM3u("Bare", bare) {}
        assertTrue("got $r", r is ImportResult.Success)
        assertEquals("http://$bare", repo.playlists().first().single().url)
    }

    @Test fun xtreamServerWithoutSchemeGetsHttp() = runTest {
        xtreamServer("auth_ok.json")
        val c = creds()
        val r = repo.addXtream("Xt", XtreamCredentials(c.server.removePrefix("http://"), c.username, c.password)) {}
        assertTrue("got $r", r is ImportResult.Success)
        assertEquals(c.server, repo.playlists().first().single().xtreamServer)
    }

    @Test fun withHttpSchemeRules() {
        assertEquals("http://host:80/a", withHttpScheme("host:80/a"))
        assertEquals("http://host", withHttpScheme("  host  "))
        assertEquals("HTTP://Host/a", withHttpScheme("HTTP://Host/a"))
        assertEquals("https://host/a", withHttpScheme("https://host/a"))
        assertEquals("HTTPS://host", withHttpScheme(" HTTPS://host"))
    }

    @Test fun refreshSuccessClearsLastError() = runTest {
        val id = addFive()
        enqueue("down", 500)
        assertEquals(ImportError.Http(500), (repo.refresh(id) {} as ImportResult.Failure).error)
        assertNotNull(repo.playlists().first().single().lastError)
        enqueue(res("import/five.m3u"))
        assertEquals(ImportResult.Success(id, 5), repo.refresh(id) {})
        assertNull(repo.playlists().first().single().lastError)
    }

    @Test fun deleteRemovesPlaylistAndChannels() = runTest {
        val id = addFive()
        repo.delete(id)
        assertTrue(repo.playlists().first().isEmpty())
        assertEquals(0, totalChannels())
    }

    // --- navigation ---

    @Test fun neighbourWrapsAround() = runTest {
        val id = addFive() // numbers 1..5; News = 2 and 5
        val dao = db.channelDao()
        val news = ChannelScope.Group(id, "News")
        suspend fun n(number: Int) = dao.byNumber(number)!!
        assertEquals(3, dao.neighbour(n(2), null, +1)?.number)
        assertEquals(1, dao.neighbour(n(5), null, +1)?.number)
        assertEquals(5, dao.neighbour(n(1), null, -1)?.number)
        assertEquals(2, dao.neighbour(n(5), news, +1)?.number)
        assertEquals(5, dao.neighbour(n(2), news, -1)?.number)
        assertEquals(5, dao.neighbour(n(2), news, +1)?.number)
        assertNull(dao.neighbour(n(1), ChannelScope.Group(id, "Nope"), +1))
        val first = n(1)
        assertEquals(first, dao.byId(first.id))
    }

    /**
     * Guide numbers are set explicitly (normally ChannelOverlay derives them), so this holds with
     * or without the overlay in the import path. Each case: every visible channel exactly once in
     * (guideNumber, playlistId, sortOrder, id) order, then the wrap, both directions.
     */
    @Test fun neighbourVisitsEqualNumbersAcrossPlaylists() = runTest {
        // Tied guide numbers across playlists (keyset tie-break on playlistId).
        val p1 = db.addPlaylist("One")
        val p2 = db.addPlaylist("Two")
        db.insertChannels(*(1..3).map { channel(p1, "a$it", it, groupTitle = "News") }.toTypedArray())
        db.insertChannels(*(1..3).map { channel(p2, "b$it", it, groupTitle = "News") }.toTypedArray())
        assertVisitsOnceInOrder(expected = (1..3).flatMap { n -> listOf(n to p1, n to p2) })

        // Groups are per playlist: the second playlist's "News" is another group.
        val dao = db.channelDao()
        val news = generateSequence(dao.byNumber(2)!!) { runBlockingNeighbour(it, ChannelScope.Group(p1, "News"), +1) }
            .take(4).toList()
        assertEquals(listOf(2 to p1, 3 to p1, 1 to p1, 2 to p1), news.map { it.number to it.playlistId })
    }

    @Test fun neighbourFollowsContinuedNumbering() = runTest {
        // The second playlist's own numbers 1..3 continue on as guide numbers 4..6.
        val p1 = db.addPlaylist("One")
        val p2 = db.addPlaylist("Two")
        db.insertChannels(*(1..3).map { channel(p1, "a$it", it) }.toTypedArray())
        db.insertChannels(*(1..3).map { channel(p2, "b$it", it, guideNumber = it + 3) }.toTypedArray())
        assertVisitsOnceInOrder(expected = (1..3).map { it to p1 } + (1..3).map { it to p2 })
        assertEquals(p2, db.channelDao().byNumber(4)?.playlistId)
    }

    /** [expected] is (number, playlistId) in zapping order; walks All channels both ways from its first entry. */
    private suspend fun assertVisitsOnceInOrder(expected: List<Pair<Int, Long>>) {
        val dao = db.channelDao()
        val all = dao.allChannels().loadAll()
        assertEquals(expected, all.map { it.number to it.playlistId })
        assertEquals(all.sortedWith(compareBy({ it.guideNumber }, { it.playlistId }, { it.sortOrder }, { it.id })), all)
        val start = all.first()
        val forward = generateSequence(start) { runBlockingNeighbour(it, null, +1) }.take(all.size + 1).toList()
        assertEquals(all + start, forward)
        assertEquals(all.size, forward.dropLast(1).map { it.id }.toSet().size)
        val backward = generateSequence(start) { runBlockingNeighbour(it, null, -1) }.take(all.size + 1).toList()
        assertEquals(listOf(start) + all.reversed(), backward)
    }

    @Test fun aroundGivesAWindowInZappingOrderWithoutWrapping() = runTest {
        val id = addFive() // numbers 1..5; News = 2 and 5
        val dao = db.channelDao()
        val news = ChannelScope.Group(id, "News")
        suspend fun n(number: Int) = dao.byNumber(number)!!
        assertEquals(listOf(1, 2, 3, 4), dao.around(n(2), null, 2).map { it.number })
        assertEquals(listOf(4, 5), dao.around(n(5), null, 1).map { it.number })
        assertEquals(listOf(2, 5), dao.around(n(5), news, 3).map { it.number })
        // A channel outside the group still sits between the group's channels around it.
        assertEquals(listOf(2, 3, 5), dao.around(n(3), news, 3).map { it.number })
    }

    private fun runBlockingNeighbour(c: ChannelEntity, group: ChannelScope.Group?, direction: Int) =
        kotlinx.coroutines.runBlocking { db.channelDao().neighbour(c, group, direction) }

    @Test fun refreshKeepsEpgChannelId() = runTest {
        val id = addFive()
        val sport = channels(id).first()
        db.channelDao().setEpgChannelId(sport.id, "matched.sport")
        enqueue(res("import/five.m3u"))
        assertEquals(ImportResult.Success(id, 5), repo.refresh(id) {})
        assertEquals("matched.sport", db.channelDao().byId(sport.id)?.epgChannelId)
    }

    @Test fun invalidAddressMessageDoesNotEchoInput() = runTest {
        val input = "ftp//admin:s3cretPass@host.example.test/list.m3u"
        val r = repo.addM3u("Bad", input) {} as ImportResult.Failure
        assertEquals(ImportError.Unreachable, r.error)
        assertFalse(r.message, r.message.contains(input))
        assertFalse(r.message, r.message.contains("s3cretPass"))
    }

    @Test fun headerOnlyGivesEmptyPlaylist() = runTest {
        enqueue("\uFEFF#EXTM3U url-tvg=\"http://epg.example.test/g.xml\"\n\n")
        assertEquals(ImportError.EmptyPlaylist, (repo.addM3u("Hdr", url()) {} as ImportResult.Failure).error)
    }

    @Test fun emptyBodyGivesEmptyPlaylist() = runTest {
        enqueue("")
        assertEquals(ImportError.EmptyPlaylist, (repo.addM3u("Nothing", url()) {} as ImportResult.Failure).error)
    }

    @Test fun concurrentRefreshesAreSerialised() = runTest {
        val id = addFive()
        val firstArrived = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) {
                    firstArrived.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
                return MockResponse().setBody(res("import/five.m3u"))
            }
        }
        val first = async(Dispatchers.Default) { repo.refresh(id) {} }
        assertTrue(firstArrived.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { repo.refresh(id) {} }
        Thread.sleep(300)
        assertEquals("second refresh must wait for the first before opening its request", 1, requests.get())
        releaseFirst.countDown()

        assertEquals(ImportResult.Success(id, 5), first.await())
        assertEquals(ImportResult.Success(id, 5), second.await())
        assertEquals(2, requests.get())
        assertEquals(5, totalChannels())
    }

    @Test fun unexpectedExceptionsBecomeGenericFailure() = runTest {
        // Inside the transaction (consumer side): a throwing progress callback.
        server.enqueue(MockResponse().setBody(generated(600)))
        val inside = repo.addM3u("Boom", url()) { throw IllegalStateException("boom http://h.test/live/u/p/1.ts") }
        inside as ImportResult.Failure
        assertEquals(ImportError.Unexpected, inside.error)
        assertEquals(GENERIC, inside.message)
        assertTrue(repo.playlists().first().isEmpty())

        // Outside the transaction (HTTP layer), on refresh: stored as lastError, old channels kept.
        val id = addFive()
        val broken = http.newBuilder().addInterceptor { throw IllegalStateException("interceptor bug") }.build()
        val brokenRepo = PlaylistRepository(db, broken, XtreamClient(broken), now = { clock })
        val r = brokenRepo.refresh(id) {} as ImportResult.Failure
        assertEquals(ImportError.Unexpected, r.error)
        assertEquals(GENERIC, r.message)
        assertEquals(GENERIC, repo.playlists().first().single().lastError)
        assertEquals(5, totalChannels())
    }

    // --- scale ---

    @Test fun importsInBatchesOf500() = runTest(timeout = 120.seconds) {
        server.enqueue(MockResponse().setBody(generated(20_000)))
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        val r = repo.addM3u("Big", url()) { progress += it }

        assertTrue("got $r", r is ImportResult.Success)
        assertEquals(20_000, successOf(r).channelCount)
        assertEquals(20_000, progress.last())
        assertEquals((1..40).map { it * 500 }, progress.toList())
        assertEquals(20_000, totalChannels())
    }
}
