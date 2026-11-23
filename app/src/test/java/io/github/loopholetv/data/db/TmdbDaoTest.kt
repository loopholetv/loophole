package io.github.loopholetv.data.db

import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.TitleKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class TmdbDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TmdbDao
    private var p = 0L

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        dao = db.tmdbDao()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    private fun title(id: Long, kind: TmdbKind = TmdbKind.MOVIE) = TmdbTitleEntity(
        kind, id, "T$id", null, 2020, null, null, null, 7.5, 10, 100, null, "A, B", "en-US", at(0),
    )

    private fun match(
        key: String, fp: String = key, state: MatchState = MatchState.MATCHED, id: Long? = 1,
        kind: TmdbKind? = TmdbKind.MOVIE, lookedUp: Long = 0, lastSeen: Long = 0, pl: Long = p,
        tk: TitleKind = TitleKind.FILM,
    ) = VodMatchEntity(pl, tk, key, fp, state, if (id == null) null else kind, id, null, key, null, at(lookedUp), at(lastSeen))

    @Test fun rekeyFilmMovesMatch() = runTest {
        dao.upsertMatch(match("old"))
        db.vodLibraryDao().rekeyFilm(p, "old", "new")
        assertNull(dao.match(p, TitleKind.FILM, "old"))
        assertNotNull(dao.match(p, TitleKind.FILM, "new"))
    }

    @Test fun deleteForPlaylistRemovesMatches() = runTest {
        val other = db.addPlaylist("Q")
        dao.upsertMatch(match("a"))
        dao.upsertMatch(match("a", pl = other))
        db.vodLibraryDao().deleteForPlaylist(p)
        assertNull(dao.match(p, TitleKind.FILM, "a"))
        assertNotNull(dao.match(other, TitleKind.FILM, "a"))
    }

    @Test fun rejectClearsTmdbId() = runTest {
        dao.upsertMatch(match("a"))
        dao.reject(p, TitleKind.FILM, "a", at(9))
        val m = dao.match(p, TitleKind.FILM, "a")!!
        assertEquals(MatchState.REJECTED, m.state)
        assertNull(m.tmdbKind)
        assertNull(m.tmdbId)
        assertEquals(at(9), m.lookedUpAt)
    }

    @Test fun rejectAlsoRejectsOtherCopiesWithTheSameWrongMatch() = runTest {
        val other = db.addPlaylist("Q")
        dao.upsertMatch(match("a", fp = "heat", id = 1))
        dao.upsertMatch(match("a", fp = "heat", id = 1, pl = other))
        dao.upsertMatch(match("b", fp = "heat", id = 1))
        dao.upsertMatch(match("c", fp = "heat", id = 2))
        dao.upsertMatch(match("d", fp = "ronin", id = 1))
        dao.upsertMatch(match("e", fp = "heat", id = 1, tk = TitleKind.SHOW, kind = TmdbKind.TV))

        dao.reject(p, TitleKind.FILM, "a", at(9))

        for ((pl, key) in listOf(p to "a", other to "a", p to "b")) {
            val m = dao.match(pl, TitleKind.FILM, key)!!
            assertEquals("$pl/$key", MatchState.REJECTED, m.state)
            assertNull(m.tmdbId)
            assertEquals(at(9), m.lookedUpAt)
        }
        assertEquals(2L, dao.match(p, TitleKind.FILM, "c")!!.tmdbId)
        assertEquals(1L, dao.match(p, TitleKind.FILM, "d")!!.tmdbId)
        assertEquals(MatchState.MATCHED, dao.match(p, TitleKind.SHOW, "e")!!.state)
    }

    @Test fun matchByFingerprintReturnsNewest() = runTest {
        dao.upsertMatch(match("a", fp = "f", id = 1, lookedUp = 10))
        dao.upsertMatch(match("b", fp = "f", id = 2, lookedUp = 30))
        dao.upsertMatch(match("c", fp = "f", id = 3, lookedUp = 20))
        assertEquals(2L, dao.matchByFingerprint("f")!!.tmdbId)
        assertNull(dao.matchByFingerprint("none"))
    }

    @Test fun pruneRemovesOnlyStale() = runTest {
        dao.upsertMatch(match("old", lastSeen = 5))
        dao.upsertMatch(match("new", lastSeen = 50))
        dao.pruneUnseen(at(10))
        assertNull(dao.match(p, TitleKind.FILM, "old"))
        assertNotNull(dao.match(p, TitleKind.FILM, "new"))
    }

    @Test fun touchSeenChunksLargeKeyLists() = runTest {
        val keys = (1..1200).map { "k$it" }
        keys.forEach { dao.upsertMatch(match(it, lastSeen = 1)) }
        dao.touchSeen(p, TitleKind.FILM, keys, at(99), staleBefore = at(50))
        assertEquals(at(99), dao.match(p, TitleKind.FILM, "k1")!!.lastSeenAt)
        assertEquals(at(99), dao.match(p, TitleKind.FILM, "k1200")!!.lastSeenAt)
    }

    @Test fun touchSeenLeavesRowsSeenRecently() = runTest {
        dao.upsertMatch(match("old", lastSeen = 1))
        dao.upsertMatch(match("recent", lastSeen = 95))
        dao.touchSeen(p, TitleKind.FILM, listOf("old", "recent"), at(99), staleBefore = at(90))
        assertEquals(at(99), dao.match(p, TitleKind.FILM, "old")!!.lastSeenAt)
        assertEquals(at(95), dao.match(p, TitleKind.FILM, "recent")!!.lastSeenAt)
    }

    @Test fun orphanTitlesDeleted() = runTest {
        dao.upsertGenres(listOf(TmdbGenreEntity(TmdbKind.MOVIE, 1, "Drama", "en-US")))
        dao.upsertTitle(title(1), listOf(1))
        dao.upsertTitle(title(2), listOf(1))
        dao.upsertTitle(title(3, TmdbKind.TV), listOf(1))
        dao.upsertEpisodes(3, listOf(TmdbEpisodeEntity(3, 1, 1, "E", null)))
        dao.upsertTitle(title(4, TmdbKind.TV), emptyList())
        dao.upsertEpisodes(4, listOf(TmdbEpisodeEntity(4, 1, 1, "E", null)))
        dao.upsertMatch(match("a", id = 1))
        dao.upsertMatch(match("b", id = 4, kind = TmdbKind.TV, tk = TitleKind.SHOW))
        dao.upsertMatch(match("c", state = MatchState.REJECTED, id = null))

        dao.deleteOrphanTitles()

        assertNotNull(dao.title(TmdbKind.MOVIE, 1).first())
        assertNull(dao.title(TmdbKind.MOVIE, 2).first())
        assertNull(dao.title(TmdbKind.TV, 3).first())
        assertNotNull(dao.title(TmdbKind.TV, 4).first())
        assertEquals(emptyList<TmdbEpisodeEntity>(), dao.episodes(3).first())
        assertEquals(1, dao.episodes(4).first().size)
    }

    @Test fun upsertTitleReplacesGenresAndMatchedTitleJoins() = runTest {
        dao.upsertGenres(
            listOf(
                TmdbGenreEntity(TmdbKind.MOVIE, 1, "Drama", "en-US"),
                TmdbGenreEntity(TmdbKind.MOVIE, 2, "Comedy", "en-US"),
            ),
        )
        dao.upsertTitle(title(1), listOf(1, 2))
        dao.upsertTitle(title(1), listOf(2))
        assertEquals(listOf("Comedy"), dao.genresOf(TmdbKind.MOVIE, 1).first())
        dao.upsertMatch(match("a", id = 1))
        dao.upsertMatch(match("n", state = MatchState.NOT_FOUND, id = null))
        assertEquals(1L, dao.matchedTitle(p, TitleKind.FILM, "a").first()!!.tmdbId)
        assertNull(dao.matchedTitle(p, TitleKind.FILM, "n").first())
    }

    @Test fun upsertEpisodesReplacesShowEpisodes() = runTest {
        dao.upsertEpisodes(5, listOf(TmdbEpisodeEntity(5, 1, 1, "a", null), TmdbEpisodeEntity(5, 1, 2, "b", null)))
        dao.upsertEpisodes(5, listOf(TmdbEpisodeEntity(5, 2, 1, "c", null)))
        assertEquals(listOf("c"), dao.episodes(5).first().map { it.title })
    }
}
