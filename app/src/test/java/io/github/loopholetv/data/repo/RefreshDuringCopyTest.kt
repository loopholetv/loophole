package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A copied setup arriving while this TV updates its playlists or guide: the old updates stop, and
 * the update of the copied playlists that follows starts at once instead of waiting behind them.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshDuringCopyTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var playlists: PlaylistRepository
    private lateinit var epg: EpgRepository
    private lateinit var coordinator: RefreshCoordinator
    private val http = OkHttpClient()
    private var clock: Instant = Instant.parse("2026-11-07T10:00:00Z")
    private val paths = ConcurrentLinkedQueue<String>()

    /** Once set, the old playlist's provider answers only after a minute. */
    @Volatile private var slowOldPlaylist = false
    private val oldPlaylistAsked = CountDownLatch(1)
    private val oldGuideAsked = CountDownLatch(1)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                return when (path) {
                    "/old.m3u" -> MockResponse().setBody(m3u("/old.xml")).also {
                        if (slowOldPlaylist) {
                            oldPlaylistAsked.countDown()
                            it.setHeadersDelay(60, TimeUnit.SECONDS)
                        }
                    }
                    // The old guide is large: it takes a minute.
                    "/old.xml" -> MockResponse().setBody(GUIDE).setHeadersDelay(60, TimeUnit.SECONDS)
                        .also { oldGuideAsked.countDown() }
                    "/new.m3u" -> MockResponse().setBody(m3u("/new.xml"))
                    "/new.xml" -> MockResponse().setBody(GUIDE)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val stops = RefreshStops()
        playlists = PlaylistRepository(db, http, XtreamClient(http), now = { clock }, stops = stops)
        epg = EpgRepository(db, http, now = { clock }, stops = stops)
        coordinator = RefreshCoordinator(
            playlists = { playlists.playlists().first() },
            epgStatus = { epg.status(it).first() },
            refreshPlaylistFn = { id, p -> playlists.refresh(id, p) },
            refreshEpgFn = { id, p -> epg.refresh(id, p) },
            settings = { AppSettings() },
            clock = { clock },
        )
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun m3u(guidePath: String) =
        "#EXTM3U url-tvg=\"${server.url(guidePath)}\"\n#EXTINF:-1 tvg-id=\"s1.test\",Sport One\nhttp://stream.example.test/s1.ts\n"

    /** An old playlist, last updated a day ago (so an app start updates it and its guide). */
    private suspend fun oldPlaylist(): Long {
        val id = (playlists.addM3u("Old", server.url("/old.m3u").toString()) {} as ImportResult.Success).playlistId
        clock = clock.plus(Duration.ofDays(1))
        return id
    }

    /** What a copied setup does inside the playlist lock: every playlist is replaced. */
    private suspend fun replaceWithCopiedSetup(oldId: Long) {
        db.playlistDao().delete(oldId)
        db.playlistDao().insert(
            PlaylistEntity(
                name = "New", type = PlaylistType.M3U, url = server.url("/new.m3u").toString(),
                xtreamServer = null, xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "",
                manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
            ),
        )
    }

    private fun assertCopiedSetupUpdated(results: Map<Long, ImportResult>) {
        assertEquals(1, results.size)
        assertTrue("the copied playlist was updated", results.values.single() is ImportResult.Success)
        assertTrue("and then its guide", "/new.xml" in paths)
    }

    @Test fun aCopyDuringTheStartUpPlaylistUpdateSkipsTheOldGuide() = runBlocking {
        val oldId = oldPlaylist()
        slowOldPlaylist = true
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val startup = scope.async { coordinator.runStartup() }
            assertTrue(oldPlaylistAsked.await(10, TimeUnit.SECONDS))

            // The copy takes a moment to stage its files and commit; the old guide would be asked for meanwhile.
            withTimeout(10_000) {
                playlists.exclusive {
                    oldGuideAsked.await(2, TimeUnit.SECONDS)
                    replaceWithCopiedSetup(oldId)
                }
            }
            val results = withTimeout(10_000) { coordinator.refreshAll() }

            assertCopiedSetupUpdated(results)
            assertFalse("the replaced playlist's guide was never downloaded", "/old.xml" in paths)
            withTimeout(10_000) { startup.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test fun aCopyDuringAGuideUpdateStopsItAndTheCopiedSetupUpdatesAtOnce() = runBlocking {
        val oldId = oldPlaylist()
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val guide = scope.async { coordinator.refreshEpg(oldId) }
            assertTrue(oldGuideAsked.await(10, TimeUnit.SECONDS))

            withTimeout(10_000) { playlists.exclusive { replaceWithCopiedSetup(oldId) } }
            val results = withTimeout(10_000) { coordinator.refreshAll() }

            assertCopiedSetupUpdated(results)
            withTimeout(10_000) { guide.await() }
            assertTrue("the stopped guide update left nothing behind", db.programmeDao().pendingGenerations().isEmpty())
            assertEquals(1, paths.count { it == "/old.xml" })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aGuideUpdateAskedForDuringACopyReturnsAtOnce() = runBlocking {
        val oldId = oldPlaylist()
        withTimeout(10_000) {
            playlists.exclusive {
                epg.refresh(oldId) {}
            }
        }
        assertFalse("/old.xml" in paths)
        assertEquals("a stopped guide update stores no status", null, epg.status(oldId).first())
    }

    private companion object {
        const val GUIDE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n" +
            "<channel id=\"s1.test\"><display-name>Sport One</display-name></channel>\n</tv>\n"
    }
}
