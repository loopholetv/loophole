package io.github.loopholetv.data.db

import io.github.loopholetv.data.tmdb.Genre
import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.RatingMin
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VodFilterDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: VodFilterDao
    private var a = 0L
    private var b = 0L

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        dao = db.vodFilterDao()
        a = db.addPlaylist("A")
        b = db.addPlaylist("B")
    }

    @After fun tearDown() = db.close()

    private suspend fun film(p: Long, key: String, category: String = "C", position: Int = 0, seen: Long = 0) =
        db.vodWriteDao().upsertFilms(
            listOf(FilmEntity(p, key, key, category, null, "http://s.test/$key.mp4", null, null, null, position, at(seen))),
        )

    private suspend fun show(p: Long, key: String, group: String = "G", position: Int = 0, seen: Long = 0) =
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, key, key, group, "Cat", null, at(seen), position)))

    private suspend fun episode(p: Long, key: String, showKey: String) = db.vodWriteDao().upsertEpisodes(
        listOf(EpisodeEntity(p, key, showKey, "G", key, 1, 1, null, "http://s.test/$key.mp4", null, null, null, 0, at(0))),
    )

    /** Matches a title to a TMDB title with these details. */
    private suspend fun details(
        p: Long,
        kind: TitleKind,
        key: String,
        id: Long,
        title: String,
        year: Int? = 2000,
        rating: Double? = null,
        runtime: Int? = null,
        genres: List<Int> = emptyList(),
    ) {
        val tmdbKind = if (kind == TitleKind.FILM) TmdbKind.MOVIE else TmdbKind.TV
        db.matchTitle(p, kind, key, id, title = null)
        db.tmdbDao().upsertTitle(
            TmdbTitleEntity(tmdbKind, id, title, null, year, null, null, null, rating, null, runtime, null, null, "en-US", Instant.EPOCH),
            genres,
        )
    }

    private suspend fun films(f: VodFilter): List<String> = db.filmGrid(f).loadAll().map { it.key }

    private suspend fun shows(f: VodFilter): List<String> = db.showGrid(f).loadAll().map { it.key }

    private suspend fun count(kind: TitleKind, f: VodFilter): Int = dao.count(filterQuery(kind, f, countOnly = true)).first()

    @Test fun filterByGenreAnyOf() = runTest {
        film(a, "action"); film(a, "drama"); film(a, "comedy"); film(a, "both")
        details(a, TitleKind.FILM, "action", 1, "Action", genres = listOf(28))
        details(a, TitleKind.FILM, "drama", 2, "Drama", genres = listOf(18))
        details(a, TitleKind.FILM, "comedy", 3, "Comedy", genres = listOf(35))
        details(a, TitleKind.FILM, "both", 4, "Both", genres = listOf(28, 18))
        val f = VodFilter(genres = setOf(28, 18), sort = VodSort.TITLE)
        assertEquals(listOf("action", "both", "drama"), films(f))
        assertEquals(3, count(TitleKind.FILM, f))
    }

    @Test fun filterByDecadeIncludingBefore1970() = runTest {
        film(a, "old"); film(a, "y1970"); film(a, "y1979"); film(a, "y1985"); film(a, "y2001"); film(a, "noYear")
        details(a, TitleKind.FILM, "old", 1, "Old", year = 1955)
        details(a, TitleKind.FILM, "y1970", 2, "Y1970", year = 1970)
        details(a, TitleKind.FILM, "y1979", 3, "Y1979", year = 1979)
        details(a, TitleKind.FILM, "y1985", 4, "Y1985", year = 1985)
        details(a, TitleKind.FILM, "y2001", 5, "Y2001", year = 2001)
        details(a, TitleKind.FILM, "noYear", 6, "NoYear", year = null)
        assertEquals(listOf("old", "y1970", "y1979"), films(VodFilter(decades = setOf(0, 1970), sort = VodSort.TITLE)))
        assertEquals(listOf("y2001"), films(VodFilter(decades = setOf(2000))))
    }

    @Test fun ratingAndRuntimeBandsBoundaries() = runTest {
        film(a, "r89"); film(a, "r90"); film(a, "r120"); film(a, "r121")
        details(a, TitleKind.FILM, "r89", 1, "A", rating = 5.9, runtime = 89)
        details(a, TitleKind.FILM, "r90", 2, "B", rating = 6.0, runtime = 90)
        details(a, TitleKind.FILM, "r120", 3, "C", rating = 7.0, runtime = 120)
        details(a, TitleKind.FILM, "r121", 4, "D", rating = 8.0, runtime = 121)
        val byTitle = VodFilter(sort = VodSort.TITLE)
        assertEquals(listOf("r89"), films(byTitle.copy(runtime = RuntimeBand.UNDER_90)))
        assertEquals(listOf("r90", "r120"), films(byTitle.copy(runtime = RuntimeBand.FROM_90_TO_120)))
        assertEquals(listOf("r121"), films(byTitle.copy(runtime = RuntimeBand.OVER_120)))
        assertEquals(listOf("r90", "r120", "r121"), films(byTitle.copy(rating = RatingMin.SIX)))
        assertEquals(listOf("r120", "r121"), films(byTitle.copy(rating = RatingMin.SEVEN)))
        assertEquals(listOf("r121"), films(byTitle.copy(rating = RatingMin.EIGHT)))
        // The sorts, each with its direction and the unknown values last.
        assertEquals(listOf("r121", "r120", "r90", "r89"), films(VodFilter(sort = VodSort.RATING)))
        assertEquals(listOf("r89", "r90", "r120", "r121"), films(VodFilter(sort = VodSort.RUNTIME)))
    }

    @Test fun sortOnlyIncludesUnmatchedLast() = runTest {
        film(a, "zMatched"); film(a, "aUnmatched"); film(a, "mMatched"); film(a, "rejected")
        details(a, TitleKind.FILM, "zMatched", 1, "Zulu", year = 1990)
        details(a, TitleKind.FILM, "mMatched", 2, "Mike", year = 2010)
        db.matchTitle(a, TitleKind.FILM, "rejected", 3, null, state = MatchState.REJECTED)
        assertEquals(listOf("mMatched", "zMatched", "aUnmatched", "rejected"), films(VodFilter(sort = VodSort.TITLE)))
        assertEquals(listOf("mMatched", "zMatched", "aUnmatched", "rejected"), films(VodFilter(sort = VodSort.YEAR)))
        assertEquals(4, count(TitleKind.FILM, VodFilter(sort = VodSort.TITLE)))
    }

    @Test fun filterExcludesUnmatched() = runTest {
        film(a, "matched"); film(a, "unmatched")
        details(a, TitleKind.FILM, "matched", 1, "Matched", year = 1995)
        show(a, "s1"); show(a, "s2")
        details(a, TitleKind.SHOW, "s1", 10, "Show", year = 1995)
        val f = VodFilter(decades = setOf(1990))
        assertEquals(listOf("matched"), films(f))
        assertEquals(listOf("s1"), shows(f))
        assertEquals(1, count(TitleKind.SHOW, f))
    }

    @Test fun duplicateAcrossPlaylistsAppearsOnceAndPrefersWatchedCopy() = runTest {
        film(a, "heatA", position = 5); film(b, "heatB", position = 0); film(a, "other", position = 1)
        details(a, TitleKind.FILM, "heatA", 949, "Heat")
        details(b, TitleKind.FILM, "heatB", 949, "Heat")
        details(a, TitleKind.FILM, "other", 1, "Other")
        // No progress: the first playlist's copy.
        assertEquals(listOf("heatA", "other"), films(VodFilter(sort = VodSort.TITLE)))
        assertEquals(listOf("heatA", "other"), films(VodFilter(decades = setOf(2000), sort = VodSort.TITLE)))
        // The copy with watch progress wins.
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, b, "heatB", 1_000, 100_000, false, at(10)))
        assertEquals(listOf("heatB", "other"), films(VodFilter(sort = VodSort.TITLE)))
        // The latest progress wins when both have some.
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, a, "heatA", 1_000, 100_000, false, at(20)))
        assertEquals(listOf("heatA", "other"), films(VodFilter(sort = VodSort.TITLE)))

        // Shows: any episode's progress counts.
        show(a, "officeA"); show(b, "officeB")
        episode(b, "e1", "officeB")
        details(a, TitleKind.SHOW, "officeA", 2316, "The Office")
        details(b, TitleKind.SHOW, "officeB", 2316, "The Office")
        assertEquals(listOf("officeA"), shows(VodFilter(sort = VodSort.TITLE)))
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, b, "e1", 1_000, 100_000, false, at(5)))
        assertEquals(listOf("officeB"), shows(VodFilter(sort = VodSort.TITLE)))

        // Same position in the same playlist: still exactly one copy.
        film(a, "twinA", position = 7); film(a, "twinB", position = 7)
        details(a, TitleKind.FILM, "twinA", 77, "Twin")
        details(a, TitleKind.FILM, "twinB", 77, "Twin")
        assertEquals(listOf("heatA", "other", "twinA"), films(VodFilter(sort = VodSort.TITLE)))
    }

    @Test fun countMatchesDedupedRows() = runTest {
        film(a, "heatA"); film(b, "heatB"); film(a, "x"); film(b, "y")
        details(a, TitleKind.FILM, "heatA", 949, "Heat")
        details(b, TitleKind.FILM, "heatB", 949, "Heat")
        details(a, TitleKind.FILM, "x", 1, "X")
        val sortOnly = VodFilter(sort = VodSort.TITLE)
        assertEquals(films(sortOnly).size, count(TitleKind.FILM, sortOnly))
        assertEquals(3, count(TitleKind.FILM, sortOnly))
        val filtered = VodFilter(decades = setOf(2000))
        assertEquals(2, count(TitleKind.FILM, filtered))
        assertEquals(films(filtered).size, count(TitleKind.FILM, filtered))
    }

    @Test fun hiddenCategoriesStayHidden() = runTest {
        film(a, "hiddenCat", category = "Hidden"); film(a, "hiddenOne"); film(a, "shown")
        // A hidden copy, even a watched one, doesn't take a visible copy's place.
        film(a, "heatHidden", category = "Hidden"); film(b, "heatShown")
        details(a, TitleKind.FILM, "heatHidden", 949, "Heat")
        details(b, TitleKind.FILM, "heatShown", 949, "Heat")
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, a, "heatHidden", 1_000, 100_000, false, at(10)))
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Hidden", hidden = true))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "hiddenOne", hidden = true))
        assertEquals(listOf("heatShown", "shown"), films(VodFilter(sort = VodSort.TITLE)))

        // A turned-off playlist's shows and a hidden group's shows stay out too.
        show(a, "hiddenGroupShow", group = "HG"); show(b, "offShow"); show(a, "visibleShow")
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "HG", hidden = true))
        db.playlistDao().setEnabled(b, false)
        assertEquals(listOf("visibleShow"), shows(VodFilter(sort = VodSort.TITLE)))
        assertEquals(1, count(TitleKind.SHOW, VodFilter(sort = VodSort.TITLE)))
    }

    @Test fun recentTiesFallBackToTitle() = runTest {
        film(a, "k1", seen = 5); film(a, "k2", seen = 5); film(a, "k3", seen = 9); film(a, "k4", seen = 5)
        details(a, TitleKind.FILM, "k1", 1, "charlie")
        details(a, TitleKind.FILM, "k2", 2, "Alpha")
        details(a, TitleKind.FILM, "k3", 3, "Zed")
        details(a, TitleKind.FILM, "k4", 4, "bravo")
        assertEquals(listOf("k3", "k2", "k4", "k1"), films(VodFilter(sort = VodSort.RECENT, rating = RatingMin.ANY, decades = setOf(2000))))
        assertEquals(listOf("k3", "k2", "k4", "k1"), films(VodFilter(sort = VodSort.RECENT)))
    }

    @Test fun genresPresentOnlyVisibleMatched() = runTest {
        db.tmdbDao().replaceGenres(
            TmdbKind.MOVIE,
            listOf(
                TmdbGenreEntity(TmdbKind.MOVIE, 28, "Action", "en-US"),
                TmdbGenreEntity(TmdbKind.MOVIE, 18, "Drama", "en-US"),
                TmdbGenreEntity(TmdbKind.MOVIE, 35, "Comedy", "en-US"),
                TmdbGenreEntity(TmdbKind.MOVIE, 99, "Documentary", "en-US"),
            ),
        )
        film(a, "visible"); film(a, "hidden", category = "Hidden"); film(a, "rejected")
        details(a, TitleKind.FILM, "visible", 1, "V", genres = listOf(28, 18))
        details(a, TitleKind.FILM, "hidden", 2, "H", genres = listOf(35))
        details(a, TitleKind.FILM, "rejected", 3, "R", genres = listOf(99))
        db.matchTitle(a, TitleKind.FILM, "rejected", 3, null, state = MatchState.REJECTED)
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Hidden", hidden = true))
        assertEquals(listOf(Genre(28, "Action"), Genre(18, "Drama")), dao.genresPresent(TitleKind.FILM).first())
        assertEquals(emptyList<Genre>(), dao.genresPresent(TitleKind.SHOW).first())
    }
}
