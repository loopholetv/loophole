package io.github.loopholetv.data.db

import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class XtreamVodDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: XtreamVodDao
    private var p = 0L
    private var other = 0L

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        dao = db.xtreamVodDao()
        p = db.addXtreamPlaylist()
        other = db.addXtreamPlaylist(name = "Other")
    }

    @After fun tearDown() = db.close()

    private fun film(playlist: Long, key: String) =
        FilmEntity(playlist, key, key, "C", null, "http://s.test/$key", null, null, null, 0, at(0))

    private fun episode(playlist: Long, key: String, showKey: String) =
        EpisodeEntity(playlist, key, showKey, "G", key, 1, 1, null, "http://s.test/$key", null, null, null, 0, at(0))

    private fun show(playlist: Long, key: String, source: ShowSource) =
        ShowEntity(playlist, key, key, "G", "C", null, at(0), 0, source = source)

    private fun details(playlist: Long, key: String) =
        ProviderDetailsEntity(playlist, key, "Plot", "Cast", "Drama", 7.5, 2001, 95, null, at(1))

    /** One playlist with both an Xtream and an M3U film, show and episode, plus details, progress and My list. */
    private suspend fun seed(playlist: Long) {
        val write = db.vodWriteDao()
        write.upsertFilms(listOf(film(playlist, "xm:1"), film(playlist, "http://m3u.test/film.mp4")))
        write.upsertEpisodes(
            listOf(episode(playlist, "xe:5", "xs:9"), episode(playlist, "http://m3u.test/ep.mp4", "Show")),
        )
        write.insertShows(listOf(show(playlist, "xs:9", ShowSource.XTREAM), show(playlist, "Show", ShowSource.M3U)))
        dao.upsertProviderDetails(details(playlist, "xm:1"))
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, playlist, "xm:1", 10, 100, false, at(2)))
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, playlist, "xe:5", 10, 100, false, at(2)))
        db.vodLibraryDao().add(MyListEntity(TitleKind.FILM, playlist, "xm:1", at(3)))
        db.vodLibraryDao().add(MyListEntity(TitleKind.SHOW, playlist, "xs:9", at(3)))
    }

    private suspend fun keys(table: String, playlist: Long): Set<String> = withContext(Dispatchers.IO) {
        db.query("SELECT `key` FROM $table WHERE playlistId = ?", arrayOf<Any>(playlist)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    @Test fun removeAllKeepsM3uRowsProgressAndMyList() = runTest {
        seed(p); seed(other)

        dao.removeAll(p)

        assertEquals(setOf("http://m3u.test/film.mp4"), keys("vod_films", p))
        assertEquals(setOf("http://m3u.test/ep.mp4"), keys("vod_episodes", p))
        assertEquals(setOf("Show"), keys("vod_shows", p))
        assertEquals(emptySet<String>(), keys("vod_provider_details", p))
        assertEquals(setOf("xm:1", "xe:5"), keys("vod_progress", p))
        assertEquals(setOf("xm:1", "xs:9"), keys("vod_my_list", p))
        // The other playlist is untouched.
        assertEquals(setOf("xm:1", "http://m3u.test/film.mp4"), keys("vod_films", other))
        assertEquals(setOf("xs:9", "Show"), keys("vod_shows", other))
        assertEquals(setOf("xm:1"), keys("vod_provider_details", other))
    }

    @Test fun removeM3uVodKeepsXtreamRows() = runTest {
        seed(p); seed(other)

        dao.removeM3uVod(p)

        assertEquals(setOf("xm:1"), keys("vod_films", p))
        assertEquals(setOf("xe:5"), keys("vod_episodes", p))
        assertEquals(setOf("xs:9"), keys("vod_shows", p))
        assertEquals(setOf("xm:1"), keys("vod_provider_details", p))
        assertEquals(setOf("xm:1", "http://m3u.test/film.mp4"), keys("vod_films", other))
        assertEquals(setOf("xs:9", "Show"), keys("vod_shows", other))
    }

    @Test fun deleteDetailsWithoutFilmDropsOnlyGoneFilms() = runTest {
        seed(p); seed(other)
        dao.upsertProviderDetails(details(p, "xm:2"))

        dao.deleteDetailsWithoutFilm(p)

        assertEquals(setOf("xm:1"), keys("vod_provider_details", p))
        db.vodWriteDao().deleteFilms(other, listOf("xm:1"))
        dao.deleteDetailsWithoutFilm(p)
        assertEquals(setOf("xm:1"), keys("vod_provider_details", other))
    }

    @Test fun xtreamEpisodeProgressKeptWhileShowsListed() = runTest {
        val progress = db.vodProgressDao()
        db.vodWriteDao().insertShows(listOf(show(p, "xs:9", ShowSource.XTREAM), show(other, "Show", ShowSource.M3U)))
        // Old progress whose episode rows aren't stored right now.
        progress.upsert(VodProgressEntity(VodKind.EPISODE, p, "xe:5", 10, 100, false, at(2)))
        progress.upsert(VodProgressEntity(VodKind.EPISODE, p, "http://m3u.test/gone.mp4", 10, 100, false, at(2)))
        progress.upsert(VodProgressEntity(VodKind.MOVIE, p, "xm:1", 10, 100, false, at(2)))
        // The other playlist has only an M3U show, so its Xtream episode progress isn't protected.
        progress.upsert(VodProgressEntity(VodKind.EPISODE, other, "xe:6", 10, 100, false, at(2)))

        progress.deleteOrphans(at(1_000))

        assertNotNull(progress.get(VodKind.EPISODE, p, "xe:5"))
        assertNull(progress.get(VodKind.EPISODE, p, "http://m3u.test/gone.mp4"))
        assertNull(progress.get(VodKind.MOVIE, p, "xm:1"))
        assertNull(progress.get(VodKind.EPISODE, other, "xe:6"))

        dao.removeAll(p)
        progress.deleteOrphans(at(1_000))
        assertNull(progress.get(VodKind.EPISODE, p, "xe:5"))
    }
}
