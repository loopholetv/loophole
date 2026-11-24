package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.MyListEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An Xtream playlist's films and series, fetched after its live import has committed. */
@RunWith(RobolectricTestRunner::class)
class XtreamPlaylistVodTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: PlaylistRepository
    private val http = OkHttpClient()
    private val xtream = XtreamClient(http)
    private val stops = RefreshStops()
    private val added = CopyOnWriteArrayList<Long>()
    private var clock = Instant.ofEpochMilli(1_000_000)

    /** Action (or path, for requests without one) to response; anything else is a 404. */
    private val bodies = ConcurrentHashMap<String, () -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val u = request.requestUrl!!
                    val key = u.queryParameter("action") ?: if (u.encodedPath == "/player_api.php") "auth" else u.encodedPath
                    return bodies[key]?.invoke() ?: MockResponse().setResponseCode(404)
                }
            }
            it.start()
        }
        repo = PlaylistRepository(db, http, xtream, now = { clock }, stops = stops, onAdded = { added += it })
        answer("auth", res("auth_ok.json"))
        answer("get_live_categories", res("categories.json"))
        answer("get_live_streams", res("live_streams.json"))
        vod(films = listOf(1, 2), shows = listOf(3))
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    // --- helpers ---

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/xtream/$name")!!.readBytes().decodeToString()

    private fun answer(key: String, body: String) {
        bodies[key] = { MockResponse().setBody(body) }
    }

    private fun answer(key: String, response: () -> MockResponse) {
        bodies[key] = response
    }

    private fun vod(films: List<Int>, shows: List<Int>) {
        answer("get_vod_categories", """[{"category_id":"1","category_name":"Action"}]""")
        answer("get_vod_streams", films.joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Film $it","category_id":"1"}""" })
        answer("get_series_categories", """[{"category_id":"2","category_name":"Drama"}]""")
        answer("get_series", shows.joinToString(",", "[", "]") { """{"series_id":$it,"name":"Show $it","category_id":"2"}""" })
    }

    private fun noLive() = answer("get_live_streams", "[]")

    private fun unreachableVod() {
        for (action in listOf("get_vod_streams", "get_series")) {
            answer(action) { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START) }
        }
    }

    private fun creds(pass: String = "pass1") = XtreamCredentials("http://${server.hostName}:${server.port}", "user1", pass)

    private fun successOf(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("import failed: ${result.error} ${result.message}")
        ImportResult.Stopped -> throw AssertionError("import stopped")
    }

    private suspend fun addXtream(): Long = successOf(repo.addXtream("Xt", creds()) {}).playlistId

    private suspend fun playlist(id: Long): PlaylistEntity = db.playlistDao().byId(id)!!
    private suspend fun filmKeys(id: Long) = db.xtreamVodDao().xtreamFilmKeys(id).toSet()
    private suspend fun showKeys(id: Long) = db.xtreamVodDao().xtreamShowKeys(id).toSet()
    private suspend fun channelCount(id: Long) = db.channelDao().channelsOf(id).size

    private fun actions() = requests.mapNotNull { it.requestUrl?.queryParameter("action") }
    private fun vodActions() = actions().filter { it.startsWith("get_vod_") || it.startsWith("get_series") }

    private suspend fun saveProgressAndList(id: Long) {
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, id, "xm:1", 5_000, 100_000, false, clock))
        db.vodLibraryDao().add(MyListEntity(TitleKind.SHOW, id, "xs:3", clock))
    }

    private suspend fun assertProgressAndListKept(id: Long) {
        assertNotNull(db.vodProgressDao().get(VodKind.MOVIE, id, "xm:1"))
        assertTrue(db.vodLibraryDao().observeInList(TitleKind.SHOW, id, "xs:3").first())
    }

    /** Holds [action]'s replies until the returned latch is released; [arrived] opens on the first. */
    private fun hold(action: String, arrived: CountDownLatch): CountDownLatch {
        val release = CountDownLatch(1)
        val reply = bodies.getValue(action)
        answer(action) {
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            reply()
        }
        return release
    }

    // --- add and refresh ---

    @Test fun xtreamAddImportsLiveThenFilmsAndShows() = runTest {
        val r = repo.addXtream("Xt", creds()) {}
        val id = successOf(r).playlistId

        assertEquals(ImportResult.Success(id, 4), r)
        assertEquals(4, channelCount(id))
        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(setOf("xs:3"), showKeys(id))
        assertNull(playlist(id).vodError)
        val a = actions()
        assertTrue("VOD comes after the live list: $a", a.indexOf("get_live_streams") < a.indexOf("get_vod_categories"))
        assertEquals(listOf("get_vod_categories", "get_vod_streams", "get_series_categories", "get_series"), vodActions())
        assertEquals(listOf(id), added.toList())
    }

    @Test fun liveCommittedBeforeVodStarts() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = hold("get_vod_categories", arrived)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val add = scope.async { repo.addXtream("Xt", creds()) {} }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            // Read from another coroutine while the VOD part waits: a still-open import transaction would block this.
            val seen = withTimeout(5_000) {
                scope.async { db.channelDao().groups().first().sumOf { it.count } }.await()
            }
            assertEquals(4, seen)
            release.countDown()
            val id = successOf(withTimeout(10_000) { add.await() }).playlistId
            assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun vodFailureKeepsLiveSuccessAndSetsVodError() = runTest {
        val id = addXtream()
        answer("get_vod_streams") { MockResponse().setResponseCode(500) }

        val r = repo.refresh(id) {}

        assertEquals(ImportResult.Success(id, 4), r)
        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(setOf("xs:3"), showKeys(id))
        assertEquals(VOD_NOT_UPDATED, playlist(id).vodError)
        assertNull(playlist(id).lastError)
    }

    @Test fun aSuccessfulVodUpdateIsRecordedAndOffClearsIt() = runTest {
        answer("get_series") { MockResponse().setResponseCode(500) }
        val id = addXtream()
        assertNull("a failed update isn't a successful one", playlist(id).vodUpdatedAt)

        vod(films = emptyList(), shows = emptyList())
        clock = clock.plusSeconds(60)
        successOf(repo.refresh(id) {})
        assertEquals("nothing listed still counts as updated", clock, playlist(id).vodUpdatedAt)

        repo.setXtreamVod(id, false)
        assertNull(playlist(id).vodUpdatedAt)
    }

    @Test fun nextGoodVodUpdateClearsVodError() = runTest {
        val id = addXtream()
        answer("get_series") { MockResponse().setResponseCode(500) }
        successOf(repo.refresh(id) {})
        assertEquals(VOD_NOT_UPDATED, playlist(id).vodError)

        vod(films = listOf(2), shows = listOf(4))
        successOf(repo.refresh(id) {})

        assertNull(playlist(id).vodError)
        assertEquals(setOf("xm:2"), filmKeys(id))
        assertEquals(setOf("xs:4"), showKeys(id))
    }

    @Test fun vodOnlyAccountAddsAndRefreshes() = runTest {
        noLive()
        val r = repo.addXtream("Xt", creds()) {}
        val id = successOf(r).playlistId
        assertEquals(ImportResult.Success(id, 0), r)
        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(listOf(id), added.toList())

        vod(films = listOf(1), shows = listOf(3, 5))
        clock = clock.plusSeconds(60)
        assertEquals(ImportResult.Success(id, 0), repo.refresh(id) {})

        assertEquals(setOf("xm:1"), filmKeys(id))
        assertEquals(setOf("xs:3", "xs:5"), showKeys(id))
        val row = playlist(id)
        assertEquals(clock, row.lastRefreshedAt)
        assertEquals(xtream.epgUrl(creds()), row.epgUrlsFromSource)
        assertNotNull(row.accountStatus)
        assertNull(row.lastError)
    }

    @Test fun emptyLiveListKeepsExistingChannelsButUpdatesVod() = runTest {
        val id = addXtream()
        noLive()
        vod(films = listOf(2, 5), shows = listOf(3))
        clock = clock.plusSeconds(60)

        val r = repo.refresh(id) {}

        // The live part counts as failed, as an empty playlist does; the films and series still update.
        assertEquals(ImportError.EmptyPlaylist, (r as ImportResult.Failure).error)
        assertEquals(4, channelCount(id))
        assertEquals(setOf("xm:2", "xm:5"), filmKeys(id))
        assertEquals(setOf("xs:3"), showKeys(id))
        assertEquals(r.message, playlist(id).lastError)
        assertNull(playlist(id).vodError)
    }

    @Test fun emptyLiveListWithFailedVodKeepsChannelsAndSetsVodError() = runTest {
        val id = addXtream()
        noLive()
        answer("get_series") { MockResponse().setResponseCode(500) }

        val r = repo.refresh(id) {}

        assertTrue(r is ImportResult.Failure)
        assertEquals(4, channelCount(id))
        assertEquals(setOf("xs:3"), showKeys(id))
        assertEquals(VOD_NOT_UPDATED, playlist(id).vodError)
    }

    @Test fun vodOnlyAddWithBothListsUnreachableReportsTheNetworkError() = runTest {
        noLive()
        unreachableVod()

        val r = repo.addXtream("Xt", creds()) {}

        assertEquals(ImportError.Unreachable, (r as ImportResult.Failure).error)
        assertTrue(r.message, r.message.startsWith("Could not reach the server"))
        assertFalse(r.message.contains("pass1"))
        assertTrue(repo.playlists().first().isEmpty())
    }

    @Test fun vodOnlyRefreshWithBothListsUnreachableReportsTheNetworkError() = runTest {
        noLive()
        val id = successOf(repo.addXtream("Xt", creds()) {}).playlistId
        unreachableVod()

        val r = repo.refresh(id) {}

        assertEquals(ImportError.Unreachable, (r as ImportResult.Failure).error)
        assertEquals(r.message, playlist(id).lastError)
        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
    }

    @Test fun noLiveNoVodRefreshIsEmptyPlaylistAndKeepsOldChannels() = runTest {
        val id = addXtream()
        noLive()
        vod(films = emptyList(), shows = emptyList())

        val r = repo.refresh(id) {}

        assertEquals(ImportError.EmptyPlaylist, (r as ImportResult.Failure).error)
        assertEquals(4, channelCount(id))
        assertEquals("an empty answer with no live channels isn't trusted", setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(r.message, playlist(id).lastError)
    }

    @Test fun noLiveNoVodAddLeavesNoPlaylist() = runTest {
        noLive()
        vod(films = emptyList(), shows = emptyList())

        val r = repo.addXtream("Xt", creds()) {}

        assertEquals(ImportError.EmptyPlaylist, (r as ImportResult.Failure).error)
        assertTrue(repo.playlists().first().isEmpty())
        assertTrue(added.isEmpty())
    }

    @Test fun authStyleVodListDeletesNothing() = runTest {
        val id = addXtream()
        answer("get_vod_streams", """{"user_info":{"auth":0}}""")

        successOf(repo.refresh(id) {})

        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(VOD_NOT_UPDATED, playlist(id).vodError)
    }

    // --- the Off setting ---

    @Test fun vodOffSkipsRequestsAndRemovesRows() = runTest {
        val id = addXtream()
        saveProgressAndList(id)
        db.playlistDao().setXtreamVod(id, false)
        db.playlistDao().setVodError(id, VOD_NOT_UPDATED)
        requests.clear()

        assertEquals(ImportResult.Success(id, 4), repo.refresh(id) {})

        assertEquals(emptyList<String>(), vodActions())
        assertTrue(filmKeys(id).isEmpty())
        assertTrue(showKeys(id).isEmpty())
        assertNull(playlist(id).vodError)
        assertProgressAndListKept(id)
    }

    @Test fun vodOffWithNoLiveChannelsIsEmptyPlaylist() = runTest {
        val id = addXtream()
        db.playlistDao().setXtreamVod(id, false)
        noLive()
        requests.clear()

        val r = repo.refresh(id) {}

        assertEquals(ImportError.EmptyPlaylist, (r as ImportResult.Failure).error)
        assertEquals(emptyList<String>(), vodActions())
        assertEquals(4, channelCount(id))
    }

    @Test fun setXtreamVodOffRemovesAtOnce() = runTest {
        val id = addXtream()
        saveProgressAndList(id)
        db.xtreamVodDao().upsertProviderDetails(ProviderDetailsEntity(id, "xm:1", "Plot", null, null, null, null, null, null, clock))
        db.playlistDao().setVodError(id, VOD_NOT_UPDATED)

        repo.setXtreamVod(id, false)

        assertFalse(playlist(id).xtreamVod)
        assertNull(playlist(id).vodError)
        assertTrue(filmKeys(id).isEmpty())
        assertTrue(showKeys(id).isEmpty())
        assertNull(db.xtreamVodDao().providerDetailsNow(id, "xm:1"))
        assertProgressAndListKept(id)

        requests.clear()
        repo.setXtreamVod(id, true)
        assertTrue(playlist(id).xtreamVod)
        assertTrue("On fetches at the next update, not now", requests.isEmpty())
        successOf(repo.refresh(id) {})
        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
    }

    @Test fun turningOffDuringRefreshLeavesNothing() = runBlocking {
        val id = addXtream()
        val arrived = CountDownLatch(1)
        val release = hold("get_vod_categories", arrived)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val refresh = scope.async { repo.refresh(id) {} }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            val off = scope.async { repo.setXtreamVod(id, false) }
            Thread.sleep(300)
            assertTrue("Off waits for the running refresh", playlist(id).xtreamVod)
            release.countDown()

            assertEquals(ImportResult.Success(id, 4), withTimeout(10_000) { refresh.await() })
            withTimeout(10_000) { off.await() }
            assertFalse(playlist(id).xtreamVod)
            assertTrue(filmKeys(id).isEmpty())
            assertTrue(showKeys(id).isEmpty())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun stoppedDuringVodKeepsLiveAndDeletesNothing() = runBlocking {
        val id = addXtream()
        answer("get_live_streams", """[{"stream_id":101,"name":"Only one"}]""")
        vod(films = emptyList(), shows = emptyList())
        val arrived = CountDownLatch(1)
        val release = hold("get_vod_categories", arrived)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val refresh = scope.async { repo.refresh(id) {} }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            withTimeout(5_000) { repo.exclusive { } }

            assertEquals(ImportResult.Stopped, withTimeout(10_000) { refresh.await() })
            assertEquals("the committed live update stays", 1, channelCount(id))
            assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
            assertEquals(setOf("xs:3"), showKeys(id))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    // --- source edits ---

    @Test fun switchingToM3uRemovesXtreamRows() = runTest {
        val id = addXtream()
        saveProgressAndList(id)
        // The M3U film has the Xtream film's name and group: its progress must not move across.
        answer(
            "/list.m3u",
            "#EXTM3U\n#EXTINF:-1 group-title=\"News\",News\nhttp://s.test/news.ts\n" +
                "#EXTINF:-1 tvg-type=\"movie\" group-title=\"Action\",Film 1\nhttp://s.test/movie/f1.mp4\n",
        )

        successOf(repo.updateSource(id, SourceEdit.M3uUrl(server.url("/list.m3u").toString())) {})

        assertTrue(filmKeys(id).isEmpty())
        assertTrue(showKeys(id).isEmpty())
        assertEquals(listOf("http://s.test/movie/f1.mp4"), db.vodWriteDao().filmKeys(id).map { it.key })
        assertNull(db.vodProgressDao().get(VodKind.MOVIE, id, "http://s.test/movie/f1.mp4"))
        assertProgressAndListKept(id)
    }

    @Test fun switchingToXtreamRemovesM3uVod() = runTest {
        answer(
            "/list.m3u",
            "#EXTM3U\n#EXTINF:-1 group-title=\"News\",News\nhttp://s.test/news.ts\n" +
                "#EXTINF:-1 tvg-type=\"movie\" group-title=\"Action\",Film 1\nhttp://s.test/movie/f1.mp4\n" +
                "#EXTINF:-1 tvg-type=\"series\" group-title=\"Show\",Show S01E01\nhttp://s.test/series/e1.mp4\n",
        )
        val id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        assertEquals(1, db.vodWriteDao().filmKeys(id).size)

        successOf(repo.updateSource(id, SourceEdit.Xtream(creds())) {})

        assertEquals(setOf("xm:1", "xm:2"), db.vodWriteDao().filmKeys(id).map { it.key }.toSet())
        assertTrue(db.vodWriteDao().episodeKeys(id).isEmpty())
        assertEquals(listOf("xs:3"), db.xtreamVodDao().xtreamShowKeys(id))
        assertEquals("only the Xtream show is left", 1, db.rowCount("vod_shows"))
    }

    @Test fun passwordChangeKeepsVodKeys() = runTest {
        val id = addXtream()
        saveProgressAndList(id)

        successOf(repo.updateSource(id, SourceEdit.Xtream(creds(pass = "pass2"))) {})

        assertEquals(setOf("xm:1", "xm:2"), filmKeys(id))
        assertEquals(setOf("xs:3"), showKeys(id))
        assertProgressAndListKept(id)
        assertEquals("pass2", playlist(id).xtreamPass)
    }
}
