package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VodImportTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: PlaylistRepository
    private val http = OkHttpClient()
    private var clock = Instant.ofEpochMilli(1_000_000)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        repo = PlaylistRepository(db, http, XtreamClient(http), now = { clock })
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun res(path: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$path")!!.readBytes().decodeToString()

    private fun enqueue(body: String) = server.enqueue(MockResponse().setBody(body))

    private suspend fun add(body: String): Long {
        enqueue(body)
        return success(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
    }

    private suspend fun refresh(id: Long, body: String) {
        enqueue(body)
        success(repo.refresh(id) {})
    }

    private fun success(r: ImportResult): ImportResult.Success = when (r) {
        is ImportResult.Success -> r
        is ImportResult.Failure -> throw AssertionError("import failed: ${r.error} ${r.message}")
        ImportResult.Stopped -> throw AssertionError("import stopped")
    }

    private fun movie(name: String, url: String, group: String = "Action") =
        "#EXTINF:-1 tvg-type=\"movie\" group-title=\"$group\",$name\n$url\n"

    private suspend fun filmKeys(id: Long) = db.vodWriteDao().filmKeys(id).map { it.key }.sorted()

    @Test fun routesTaggedEntries() = runTest {
        val id = add(res("vod.m3u"))

        assertEquals(4, db.channelDao().channelsOf(id).size)
        assertEquals(listOf("http://s.test/movie/m1.mp4", "http://s.test/movie/m2.mp4"), filmKeys(id))
        assertEquals(4, db.vodWriteDao().episodeKeys(id).size)
        assertEquals(1, db.rowCount("vod_shows"))

        val episodes = db.vodWriteDao().episodesForShows(id)
        assertEquals(setOf("g:Alpha"), episodes.map { it.showKey }.toSet())
        assertEquals(listOf(3, 4, 5, 6), episodes.map { it.position }.sorted())
        val show = db.openHelper.readableDatabase.query("SELECT name, logo, position FROM vod_shows").use {
            it.moveToFirst()
            Triple(it.getString(0), it.getString(1), it.getInt(2))
        }
        // The first episode (by position) that has a logo.
        assertEquals(Triple("Alpha", "http://img.test/a21.png", 3), show)
    }

    @Test fun liveOnlyPlaylistUnchanged() = runTest {
        val id = add(res("basic.m3u"))

        assertEquals(4, db.channelDao().channelsOf(id).size)
        assertEquals(0, db.rowCount("vod_films"))
        assertEquals(0, db.rowCount("vod_episodes"))
        assertEquals(0, db.rowCount("vod_shows"))
    }

    @Test fun vodOnlyPlaylistImports() = runTest {
        enqueue("#EXTM3U\n" + movie("A", "http://s.test/a.mp4") + movie("B", "http://s.test/b.mp4"))
        val r = repo.addM3u("P", server.url("/list.m3u").toString()) {}

        r as ImportResult.Success
        assertEquals(0, r.channelCount)
        assertEquals(2, db.rowCount("vod_films"))
    }

    @Test fun firstImportCountsAsOld() = runTest {
        val body = res("vod.m3u")
        val id = add(body)
        val first = db.vodWriteDao().filmKeys(id) + db.vodWriteDao().episodeKeys(id)
        assertEquals(6, first.size)
        assertTrue(first.all { it.firstSeenAt == Instant.EPOCH })

        clock = Instant.ofEpochMilli(9_000_000)
        refresh(id, body + movie("Fresh", "http://s.test/movie/fresh.mp4"))

        val films = db.vodWriteDao().filmKeys(id).associate { it.key to it.firstSeenAt }
        assertEquals(3, films.size)
        assertEquals(clock, films["http://s.test/movie/fresh.mp4"])
        assertEquals(Instant.EPOCH, films["http://s.test/movie/m1.mp4"])
        assertTrue(db.vodWriteDao().episodeKeys(id).all { it.firstSeenAt == Instant.EPOCH })
    }

    @Test fun tokenChangeKeepsKey() = runTest {
        val body = res("vod.m3u")
        val id = add(body)
        val before = db.vodWriteDao().filmKeys(id).sortedBy { it.key }

        clock = Instant.ofEpochMilli(9_000_000)
        refresh(id, body.replace("token=a", "token=b"))

        assertEquals(before, db.vodWriteDao().filmKeys(id).sortedBy { it.key })
        val url = db.openHelper.readableDatabase.query("SELECT streamUrl FROM vod_films WHERE `key` = 'http://s.test/movie/m1.mp4'")
            .use { it.moveToFirst(); it.getString(0) }
        assertEquals("http://s.test/movie/m1.mp4?token=b", url)
    }

    @Test fun failedRefreshKeepsVod() = runTest {
        val body = res("vod.m3u")
        val id = add(body)
        val films = db.vodWriteDao().filmKeys(id).sortedBy { it.key }
        val episodes = db.vodWriteDao().episodeKeys(id).sortedBy { it.key }
        val showRows = suspend { db.openHelper.readableDatabase.query("SELECT `key`, firstSeenAt FROM vod_shows ORDER BY `key`").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toList() } }
        val shows = showRows()

        // The mock server cuts the body part-way. Reading it directly shows how many films arrive before
        // the cut, so the test proves that more than one batch is already written when the refresh fails.
        clock = Instant.ofEpochMilli(9_000_000)
        val big = body + (1..3_000).joinToString("") { movie("Bulk $it", "http://s.test/bulk/$it.mp4", "Bulk") }
        server.enqueue(MockResponse().setBody(big).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val received = StringBuilder()
        try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { resp ->
                    resp.body.source().inputStream().bufferedReader().forEachLine { received.appendLine(it) }
                }
            }
        } catch (_: IOException) {
            // expected: the body is cut short
        }
        val filmsBeforeCut = Regex("tvg-type=\"movie\"").findAll(received).count()
        assertTrue("only $filmsBeforeCut films before the cut", filmsBeforeCut > 2 * BATCH_SIZE)

        server.enqueue(MockResponse().setBody(big).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val r = repo.refresh(id) {}

        assertTrue(r is ImportResult.Failure)
        assertEquals(films, db.vodWriteDao().filmKeys(id).sortedBy { it.key })
        assertEquals(episodes, db.vodWriteDao().episodeKeys(id).sortedBy { it.key })
        assertEquals(shows, showRows())
    }

    @Test fun removedEntryDeleted() = runTest {
        val body = res("vod.m3u")
        val id = add(body)
        seedProgress(id, "http://s.test/movie/m2.mp4")

        refresh(id, body.replace(Regex("#EXTINF[^\n]*Film Two\n[^\n]*\n"), ""))

        assertEquals(listOf("http://s.test/movie/m1.mp4"), filmKeys(id))
        assertEquals(1, db.rowCount("vod_progress"))
    }

    @Test fun hiddenChannelStaysHidden() = runTest {
        val body = res("vod.m3u")
        // An update from before films had their own tables: the movie is a channel and is hidden.
        val id = db.addPlaylistRow()
        val oldKey = "http://s.test/movie/m1.mp4?token=a"
        db.channelPrefsDao().upsert(ChannelPrefsEntity(id, oldKey, hidden = true))

        refresh(id, body)
        assertEquals(true, db.channelPrefsDao().get(id, "http://s.test/movie/m1.mp4")?.hidden)

        refresh(id, body)
        assertEquals(true, db.channelPrefsDao().get(id, "http://s.test/movie/m1.mp4")?.hidden)
        assertNull(db.channelPrefsDao().get(id, "http://s.test/movie/m2.mp4"))
    }

    @Test fun existingChannelRowsOfFilmsRemoved() = runTest {
        val id = db.addPlaylistRow()
        db.channelDao().insertAll(
            listOf(channel(id, "http://s.test/movie/m1.mp4?token=a", 1), channel(id, "http://s.test/live/one.ts?token=a", 2)),
        )

        refresh(id, res("vod.m3u"))

        val keys = db.channelDao().channelsOf(id).map { it.key }
        assertTrue(keys.none { it.contains("/movie/") })
        assertTrue(keys.contains("http://s.test/live/one.ts?token=a"))
        assertNotNull(db.vodWriteDao().filmKeys(id).singleOrNull { it.key.endsWith("m1.mp4") })
    }

    @Test fun deletingPlaylistDeletesProgress() = runTest {
        val id = add(res("vod.m3u"))
        seedProgress(id, "http://s.test/movie/m1.mp4")

        repo.delete(id)

        assertEquals(0, db.rowCount("vod_progress"))
        assertEquals(0, db.rowCount("vod_films"))
    }

    @Test fun oldProgressOfRemovedItemsIsDropped() = runTest {
        val body = res("vod.m3u")
        val id = add(body)
        val old = clock.minusSeconds(91L * 24 * 3600).toEpochMilli()
        val recent = clock.minusSeconds(3L * 24 * 3600).toEpochMilli()
        fun progress(key: String, at: Long) = seedProgress(id, key, at)
        progress("http://gone.test/old.mp4", old)
        progress("http://gone.test/recent.mp4", recent)
        progress("http://s.test/movie/m1.mp4", old)

        refresh(id, body)

        assertEquals(2, db.rowCount("vod_progress"))
    }

    @Test fun progressFollowsItemAcrossAddressChange() = runTest {
        val id = add(res("vod.m3u"))
        seedProgress(id, "http://s.test/movie/m1.mp4")

        enqueue(res("vod.m3u").replace("http://s.test/", "http://new.test/"))
        success(repo.updateSource(id, SourceEdit.M3uUrl(server.url("/list.m3u").toString())) {})

        val key = db.openHelper.readableDatabase.query("SELECT `key` FROM vod_progress").use { it.moveToFirst(); it.getString(0) }
        assertEquals("http://new.test/movie/m1.mp4", key)
    }

    @Test fun addressChangeDoesNotMakeEverythingRecentlyAdded() = runTest {
        val id = add(res("vod.m3u"))

        clock = Instant.ofEpochMilli(9_000_000)
        enqueue(res("vod.m3u").replace("http://s.test/", "http://new.test/"))
        success(repo.updateSource(id, SourceEdit.M3uUrl(server.url("/list.m3u").toString())) {})

        val all = db.vodWriteDao().filmKeys(id) + db.vodWriteDao().episodeKeys(id)
        assertEquals(6, all.size)
        assertTrue(all.all { it.key.startsWith("http://new.test/") && it.firstSeenAt == Instant.EPOCH })
    }

    @Test fun hiddenChannelKeyedByItsQueryStaysHidden() = runTest {
        val id = db.addPlaylistRow()
        db.channelPrefsDao().upsert(ChannelPrefsEntity(id, "http://s.test/movie.php?id=2", hidden = true))

        refresh(id, movie("One", "http://s.test/movie.php?id=1") + movie("Two", "http://s.test/movie.php?id=2"))

        assertEquals(listOf("http://s.test/movie.php", "http://s.test/movie.php?id=2"), filmKeys(id))
        assertEquals(true, db.channelPrefsDao().get(id, "http://s.test/movie.php?id=2")?.hidden)
        assertNull(db.channelPrefsDao().get(id, "http://s.test/movie.php"))
    }

    private fun seedProgress(id: Long, key: String, at: Long = clock.toEpochMilli()) =
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO vod_progress(kind, playlistId, `key`, positionMs, durationMs, watched, lastWatchedAt) " +
                "VALUES ('MOVIE', $id, '$key', 5000, 100000, 0, $at)",
        )

    private suspend fun AppDatabase.addPlaylistRow(): Long =
        playlistDao().insert(
            io.github.loopholetv.data.db.PlaylistEntity(
                name = "P", type = io.github.loopholetv.data.db.PlaylistType.M3U,
                url = server.url("/list.m3u").toString(), xtreamServer = null, xtreamUser = null,
                xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
            ),
        )
}
