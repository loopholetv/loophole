package io.github.loopholetv.data.db

import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VodLibraryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: VodLibraryDao
    private var p = 0L

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        dao = db.vodLibraryDao()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    private suspend fun film(key: String, category: String = "C") = db.vodWriteDao().upsertFilms(
        listOf(FilmEntity(p, key, key, category, null, "http://s.test/$key.mp4", null, null, null, 0, at(0))),
    )

    private suspend fun progress(key: String, positionMs: Long, lastWatched: Long, watched: Boolean = false) =
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, p, key, positionMs, 100_000, watched, at(lastWatched)))

    @Test fun myListOrdersNewestFirstAndHidesHiddenCategory() = runTest {
        film("A", "Hidden"); film("B", "Shown")
        dao.add(MyListEntity(TitleKind.FILM, p, "A", at(1)))
        dao.add(MyListEntity(TitleKind.FILM, p, "B", at(2)))
        assertEquals(listOf("B", "A"), dao.myListFilms().first().map { it.key })

        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Hidden", hidden = true))
        assertEquals(listOf("B"), dao.myListFilms().first().map { it.key })
    }

    @Test fun myListShowsJoinVisibleShowsNewestFirst() = runTest {
        db.vodWriteDao().insertShows(
            listOf("S1", "S2").map { ShowEntity(p, it, it, "G", "Cat", null, at(0), 0) },
        )
        dao.add(MyListEntity(TitleKind.SHOW, p, "S1", at(1)))
        dao.add(MyListEntity(TitleKind.SHOW, p, "S2", at(2)))
        dao.add(MyListEntity(TitleKind.SHOW, p, "gone", at(3)))
        assertEquals(listOf("S2", "S1"), dao.myListShows().first().map { it.key })
    }

    @Test fun addRemoveAndObserve() = runTest {
        assertEquals(false, dao.observeInList(TitleKind.FILM, p, "A").first())
        dao.add(MyListEntity(TitleKind.FILM, p, "A", at(1)))
        assertEquals(true, dao.observeInList(TitleKind.FILM, p, "A").first())
        assertEquals(listOf(KeyRef(p, "A")), dao.observeListKeys(TitleKind.FILM).first())
        assertEquals(emptyList<KeyRef>(), dao.observeListKeys(TitleKind.SHOW).first())
        dao.remove(TitleKind.FILM, p, "A")
        assertEquals(false, dao.observeInList(TitleKind.FILM, p, "A").first())
    }

    @Test fun continueFilmsSkipsShortAndDismissedAndComesBackAfterNewPlay() = runTest {
        film("short"); film("long")
        progress("short", 10_000, 100)
        assertEquals(emptyList<String>(), dao.continueFilms(30_000, 20).first().map { it.card.key })

        progress("long", 60_000, 200)
        assertEquals(listOf("long"), dao.continueFilms(30_000, 20).first().map { it.card.key })
        assertEquals(60_000L, dao.continueFilms(30_000, 20).first().single().positionMs)

        dao.dismiss(ContinueDismissalEntity(TitleKind.FILM, p, "long", at(200)))
        assertEquals(emptyList<String>(), dao.continueFilms(30_000, 20).first().map { it.card.key })
        assertEquals(at(200), dao.dismissedAt(TitleKind.FILM, p, "long"))
        assertEquals(1, dao.observeDismissals(TitleKind.FILM).first().size)

        progress("long", 70_000, 300)
        assertEquals(listOf("long"), dao.continueFilms(30_000, 20).first().map { it.card.key })
    }

    @Test fun continueFilmsCountsRewatchOfWatchedFilm() = runTest {
        film("seen")
        progress("seen", 50_000, 100, watched = true)
        assertEquals(listOf("seen"), dao.continueFilms(30_000, 20).first().map { it.card.key })
    }

    @Test fun continueFilmsLimitAndOrder() = runTest {
        for (i in 1..25) { film("f$i"); progress("f$i", 60_000, i * 10L) }
        val keys = dao.continueFilms(30_000, 20).first().map { it.card.key }
        assertEquals(20, keys.size)
        assertEquals("f25", keys.first())
        assertEquals("f6", keys.last())
    }

    @Test fun recentEpisodeProgressJoinsVisibleShows() = runTest {
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, "S", "S", "G", "Cat", null, at(0), 0)))
        db.vodWriteDao().upsertEpisodes(
            listOf(EpisodeEntity(p, "e1", "S", "G", "E1", 1, 1, null, "u", null, null, null, 0, at(0))),
        )
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, p, "e1", 1000, null, false, at(500)))
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, p, "orphan", 1000, null, false, at(600)))
        assertEquals(listOf(EpisodeProgressRow(p, "S", at(500))), dao.recentEpisodeProgress(10).first())
    }

    @Test fun deleteForPlaylistRemovesListAndDismissals() = runTest {
        val other = db.addPlaylist("Other")
        dao.add(MyListEntity(TitleKind.FILM, p, "A", at(1)))
        dao.dismiss(ContinueDismissalEntity(TitleKind.FILM, p, "A", at(1)))
        dao.add(MyListEntity(TitleKind.FILM, other, "A", at(1)))
        dao.dismiss(ContinueDismissalEntity(TitleKind.SHOW, other, "A", at(1)))

        dao.deleteForPlaylist(p)

        assertEquals(1, db.rowCount("vod_my_list"))
        assertEquals(1, db.rowCount("vod_continue_dismissed"))
        assertNull(dao.dismissedAt(TitleKind.FILM, p, "A"))
    }

    @Test fun deleteOrphansKeepsMatchedAndRecent() = runTest {
        film("kept")
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, "show", "show", "G", null, null, at(0), 0)))
        for ((kind, key) in listOf(TitleKind.FILM to "kept", TitleKind.SHOW to "show", TitleKind.FILM to "old", TitleKind.FILM to "recent")) {
            val t = if (key == "recent") 900L else 100L
            dao.add(MyListEntity(kind, p, key, at(t)))
            dao.dismiss(ContinueDismissalEntity(kind, p, key, at(t)))
        }
        // A film key does not protect a show key of the same text.
        dao.add(MyListEntity(TitleKind.SHOW, p, "kept", at(100)))

        dao.deleteOrphans(at(500))

        assertEquals(listOf("kept", "recent"), dao.observeDismissals(TitleKind.FILM).first().map { it.key }.sorted())
        assertEquals(listOf("show"), dao.observeDismissals(TitleKind.SHOW).first().map { it.key })
        assertEquals(3, db.rowCount("vod_my_list"))
    }

    @Test fun rekeyFilmMovesBothTablesOnlyForFilms() = runTest {
        dao.add(MyListEntity(TitleKind.FILM, p, "old", at(1)))
        dao.dismiss(ContinueDismissalEntity(TitleKind.FILM, p, "old", at(1)))
        dao.add(MyListEntity(TitleKind.SHOW, p, "old", at(1)))

        dao.rekeyFilm(p, "old", "new")

        assertEquals(listOf(KeyRef(p, "new")), dao.observeListKeys(TitleKind.FILM).first())
        assertEquals(listOf(KeyRef(p, "old")), dao.observeListKeys(TitleKind.SHOW).first())
        assertEquals(at(1), dao.dismissedAt(TitleKind.FILM, p, "new"))
    }
}
