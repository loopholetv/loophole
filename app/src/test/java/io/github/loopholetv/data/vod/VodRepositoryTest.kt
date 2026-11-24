package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.TmdbEpisodeEntity
import io.github.loopholetv.data.db.matchTitle
import io.github.loopholetv.data.db.searchPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import androidx.paging.testing.asSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class VodRepositoryTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var repo: VodRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = VodRepository(db) { now }
    }

    @After fun tearDown() = db.close()

    private fun film(p: Long, key: String, category: String = "Action", position: Int = 0, seen: Instant = Instant.EPOCH, name: String = key) =
        FilmEntity(p, key, name, category, null, "http://s.test/$key.mp4", "ua", "ref", null, position, seen)

    private fun show(p: Long, key: String, name: String = key, group: String = "Drama", category: String? = group, seen: Instant = Instant.EPOCH) =
        ShowEntity(p, key, name, group, category, null, seen, 0)

    private fun episode(p: Long, key: String, showKey: String, season: Int?, episode: Int?, position: Int = 0) =
        EpisodeEntity(p, key, showKey, "Drama", "E $key", season, episode, null, "http://s.test/$key.mp4", null, null, null, position, Instant.EPOCH)

    private suspend fun insertFilms(vararg rows: FilmEntity) = db.vodWriteDao().upsertFilms(rows.toList())
    private suspend fun insertShows(vararg rows: ShowEntity) = db.vodWriteDao().insertShows(rows.toList())

    @Test fun mergesCategoriesAcrossPlaylists() = runTest {
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        insertFilms(film(a, "f1"), film(a, "f2"), film(b, "f3"))

        assertEquals(listOf(CategoryRow("Action", 3)), repo.filmCategories().first())
    }

    @Test fun recentWindow() = runTest {
        val a = db.addPlaylist()
        insertFilms(
            film(a, "old"),
            film(a, "mid", seen = now.minus(Duration.ofDays(20))),
            film(a, "new", seen = now.minus(Duration.ofDays(2))),
        )

        assertEquals(listOf("new"), repo.recentFilms().first().map { it.key })
    }

    @Test fun hiddenGroupFiltered() = runTest {
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        insertFilms(film(a, "fa"), film(b, "fb"))
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Action", hidden = true))

        assertEquals(listOf(CategoryRow("Action", 1)), repo.filmCategories().first())
        assertEquals(listOf("fb"), (repo.browseFilms("Action")).map { it.key })
        assertEquals(listOf(HiddenCategory("Action", listOf(a))), repo.hiddenCategories().first())
    }

    @Test fun hiddenChannelKeyFiltered() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1"), film(a, "f2"))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "f1", hidden = true))

        assertEquals(listOf("f2"), repo.browseFilms("Action").map { it.key })
        assertNull(repo.film(a, "f1").first())
    }

    @Test fun hiddenEpisodeLeftOutOfTheShowPage() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s"))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "e1", "s", 1, 1), episode(a, "e2", "s", 1, 2)))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "e1", hidden = true))

        val detail = repo.show(a, "s").first()!!
        assertEquals(listOf("e2"), detail.seasons.flatMap { s -> s.episodes.map { it.key } })
    }

    @Test fun allShowsSortIgnoresCase() = runTest {
        val a = db.addPlaylist()
        insertShows(
            show(a, "s1", name = "beta", category = null),
            show(a, "s2", name = "Alpha", category = null),
            show(a, "s3", name = "Charlie", category = null),
        )

        assertEquals(listOf("Alpha", "beta", "Charlie"), repo.browseShows(null).map { it.name })
    }

    @Test fun everyCategoryHiddenStillCountsForTheRail() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", category = "Action"))
        insertShows(show(a, "s1", group = "Drama"))
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Action", hidden = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Drama", hidden = true))

        assertEquals(emptyList<CategoryRow>(), repo.filmCategories().first())
        assertTrue(repo.hasFilms.first())
        assertTrue(repo.hasShows.first())
    }

    @Test fun filmsHiddenOneByOneDoNotCountForTheRail() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1"))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "f1", hidden = true))

        assertFalse(repo.hasFilms.first())
    }

    @Test fun disabledPlaylistHidden() = runTest {
        val a = db.addPlaylist("A")
        insertFilms(film(a, "f1", name = "Alpha film"))
        insertShows(show(a, "s1", name = "Alpha show"))
        assertTrue(repo.hasFilms.first())
        assertTrue(repo.hasShows.first())

        db.playlistDao().setEnabled(a, false)

        assertFalse(repo.hasFilms.first())
        assertFalse(repo.hasShows.first())
        assertEquals(emptyList<CategoryRow>(), repo.filmCategories().first())
        assertEquals(emptyList<FilmCard>(), repo.browseFilms("Action"))
        assertEquals(emptyList<ShowCard>(), repo.browseShows(null))
        assertEquals(emptyList<FilmCard>(), repo.searchFilms(searchPattern("alpha")!!, 10))
        assertEquals(emptyList<ShowCard>(), repo.searchShows(searchPattern("alpha")!!, 10))
    }

    @Test fun duplicateShowNamesLabelled() = runTest {
        val a = db.addPlaylist("Alpha")
        val b = db.addPlaylist("Beta")
        insertShows(show(a, "s1", name = "Same", category = null), show(b, "s2", name = "Same", category = null), show(a, "s3", name = "Unique", category = null))

        val byKey = repo.browseShows(null).associateBy { it.key }
        assertEquals("Alpha", byKey.getValue("s1").playlistName)
        assertEquals("Beta", byKey.getValue("s2").playlistName)
        assertNull(byKey.getValue("s3").playlistName)

        db.playlistDao().setEnabled(b, false)
        assertNull(repo.browseShows(null).single { it.key == "s1" }.playlistName)
    }

    @Test fun showDetailSortsSeasonsOtherLast() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s"))
        db.vodWriteDao().upsertEpisodes(
            listOf(
                episode(a, "x", "s", null, null, position = 1),
                episode(a, "e2", "s", 2, 1),
                episode(a, "e1b", "s", 1, 2),
                episode(a, "e1a", "s", 1, 1),
            ),
        )

        val detail = repo.show(a, "s").first()!!
        assertEquals(listOf<Int?>(1, 2, null), detail.seasons.map { it.number })
        assertEquals(listOf("e1a", "e1b"), detail.seasons[0].episodes.map { it.key })
        assertEquals(listOf("x"), detail.seasons[2].episodes.map { it.key })
    }

    @Test fun searchFindsFilmAndShow() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "The Heat"), film(a, "f2", name = "Cold"))
        insertShows(show(a, "s1", name = "Heat Wave"), show(a, "s2", name = "100% Real"))

        assertEquals(listOf("f1"), repo.searchFilms(searchPattern("heat")!!, 10).map { it.key })
        assertEquals(listOf("s1"), repo.searchShows(searchPattern("heat")!!, 10).map { it.key })
        assertEquals(listOf("s2"), repo.searchShows(searchPattern("0%")!!, 10).map { it.key })
    }

    @Test fun allShowsIsNullCategory() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1", category = null), show(a, "s2", category = "Drama"))

        assertEquals(listOf("s1"), repo.browseShows(null).map { it.key })
        assertEquals(listOf("s2"), repo.browseShows("Drama").map { it.key })
        assertEquals(1, repo.showCount(null).first())
        assertEquals(listOf(CategoryRow("Drama", 1)), repo.showCategories().first())
    }

    @Test fun playableFromRows() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "Film One"))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "e1", "s", 1, 1)))

        val ref = VodRef(VodKind.MOVIE, a, "f1")
        assertEquals(VodPlayable(ref, "Film One", "http://s.test/f1.mp4", "ua", "ref"), repo.playable(ref))
        assertEquals("E e1", repo.playable(VodRef(VodKind.EPISODE, a, "e1"))?.title)
        assertNull(repo.playable(VodRef(VodKind.MOVIE, a, "missing")))
        assertNull(repo.playable(VodRef(VodKind.RECORDING, a, "rec:1")))
        assertEquals(listOf(a), repo.playlistsWithCategory("Action"))
    }

    @Test fun xtreamFilmUrlUsesCurrentLogin() = runTest {
        val a = db.addXtreamPlaylist(pass = "old")
        insertFilms(film(a, "xm:7").copy(streamUrl = "http://stale.test/x", container = "mkv"))
        val ref = VodRef(VodKind.MOVIE, a, "xm:7")
        assertEquals("http://xt.test/movie/u/old/7.mkv", repo.playable(ref)?.streamUrl)

        db.playlistDao().update(db.playlistDao().byId(a)!!.copy(xtreamPass = "new"))
        assertEquals("http://xt.test/movie/u/new/7.mkv", repo.playable(ref)?.streamUrl)
    }

    @Test fun xtreamEpisodeUrlUsesItsContainer() = runTest {
        val a = db.addXtreamPlaylist()
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "xe:55", "xs:1", 1, 1).copy(container = "avi")))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "xe:56", "xs:1", 1, 2)))

        assertEquals("http://xt.test/series/u/p/55.avi", repo.playable(VodRef(VodKind.EPISODE, a, "xe:55"))?.streamUrl)
        assertEquals("http://xt.test/series/u/p/56.mp4", repo.playable(VodRef(VodKind.EPISODE, a, "xe:56"))?.streamUrl)
    }

    @Test fun xtreamFilmIgnoresHlsFormat() = runTest {
        val a = db.addXtreamPlaylist()
        db.playlistDao().update(db.playlistDao().byId(a)!!.copy(xtreamFormat = "hls"))
        insertFilms(film(a, "xm:9").copy(container = "ts"))

        assertEquals("http://xt.test/movie/u/p/9.ts", repo.playable(VodRef(VodKind.MOVIE, a, "xm:9"))?.streamUrl)
    }

    @Test fun xtreamRowOnNonXtreamPlaylistIsNotPlayable() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "xm:7"))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "xe:5", "xs:1", 1, 1)))

        assertNull(repo.playable(VodRef(VodKind.MOVIE, a, "xm:7")))
        assertNull(repo.playable(VodRef(VodKind.EPISODE, a, "xe:5")))
    }

    @Test fun m3uFilmStillUsesStoredUrl() = runTest {
        val a = db.addXtreamPlaylist()
        insertFilms(film(a, "f1"))

        assertEquals("http://s.test/f1.mp4", repo.playable(VodRef(VodKind.MOVIE, a, "f1"))?.streamUrl)
    }

    @Test fun hiddenGroupExcludedEverywhere() = runTest {
        val a = db.addPlaylist()
        val recent = now.minus(Duration.ofDays(1))
        insertFilms(film(a, "f1", category = "Hid", seen = recent, name = "Heat film"), film(a, "f2", category = "Vis", seen = recent, name = "Heat two"))
        insertShows(
            show(a, "s1", name = "Heat show", group = "HidShows", seen = recent),
            show(a, "s2", name = "Heat other", group = "VisShows", seen = recent),
        )
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "Hid", hidden = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(a, "HidShows", hidden = true))
        val pattern = searchPattern("heat")!!

        assertEquals(listOf("f2"), repo.recentFilms().first().map { it.key })
        assertEquals(listOf("f2"), repo.searchFilms(pattern, 10).map { it.key })
        assertEquals(listOf("s2"), repo.recentShows().first().map { it.key })
        assertEquals(listOf("s2"), repo.searchShows(pattern, 10).map { it.key })
        assertEquals(1, repo.showCount("VisShows").first())
        assertEquals(0, repo.showCount("HidShows").first())
        assertEquals(listOf(CategoryRow("VisShows", 1)), repo.showCategories().first())
        assertEquals(
            listOf(HiddenCategory("Hid", listOf(a)), HiddenCategory("HidShows", listOf(a))),
            repo.hiddenCategories().first(),
        )
    }

    @Test fun nextEpisodeSkipsHiddenEpisode() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s"))
        db.vodWriteDao().upsertEpisodes(
            listOf(episode(a, "e1", "s", 1, 1, 0), episode(a, "e2", "s", 1, 2, 1), episode(a, "e3", "s", 1, 3, 2)),
        )
        assertEquals(VodRef(VodKind.EPISODE, a, "e2"), repo.nextEpisode(VodRef(VodKind.EPISODE, a, "e1")))

        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "e2", hidden = true))
        assertEquals(VodRef(VodKind.EPISODE, a, "e3"), repo.nextEpisode(VodRef(VodKind.EPISODE, a, "e1")))
    }

    @Test fun nextEpisodeNullWhenGone() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s"))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "e1", "s", 1, 1, 0), episode(a, "e2", "s", 1, 2, 1)))

        assertNull(repo.nextEpisode(VodRef(VodKind.EPISODE, a, "e2")))
        assertNull(repo.nextEpisode(VodRef(VodKind.EPISODE, a, "missing")))
        assertNull(repo.nextEpisode(VodRef(VodKind.MOVIE, a, "e1")))
        db.vodWriteDao().deleteEpisodes(a, listOf("e2"))
        assertNull(repo.nextEpisode(VodRef(VodKind.EPISODE, a, "e1")))
    }

    @Test fun matchedFilmCardShowsTmdbTitlePosterAndBadge() = runTest {
        val a = db.addPlaylist()
        val recent = now.minus(Duration.ofDays(1))
        insertFilms(film(a, "f1", name = "Heat 1995 4K", seen = recent).copy(logo = "http://logo.test/f1.png"))
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat", posterPath = "/heat.jpg", badge = "4K")

        val expected = FilmCard(a, "f1", "Heat", "https://image.tmdb.org/t/p/w342/heat.jpg", "4K")
        assertEquals(listOf(expected), repo.browseFilms("Action"))
        assertEquals(listOf(expected), repo.recentFilms().first())
    }

    @Test fun matchedFilmWithoutPosterKeepsPlaylistLogo() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "Heat 1995").copy(logo = "http://logo.test/f1.png"))
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat")

        assertEquals(listOf(FilmCard(a, "f1", "Heat", "http://logo.test/f1.png", null)), repo.browseFilms("Action"))
    }

    @Test fun unmatchedFilmCardUnchanged() = runTest {
        val a = db.addPlaylist()
        insertFilms(
            film(a, "f1", name = "Heat 1995 4K").copy(logo = "http://logo.test/f1.png"),
            film(a, "f2", name = "Lost 4K", position = 1),
        )
        // Not found: the badge stays with the match row and is not shown.
        db.matchTitle(a, TitleKind.FILM, "f2", 0, null, badge = "4K", state = MatchState.NOT_FOUND)

        assertEquals(
            listOf(FilmCard(a, "f1", "Heat 1995 4K", "http://logo.test/f1.png", null), FilmCard(a, "f2", "Lost 4K", null, null)),
            repo.browseFilms("Action"),
        )
    }

    @Test fun rejectedFilmShowsPlaylistName() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "Heat 1995"))
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat", posterPath = "/heat.jpg", badge = "HD", state = MatchState.REJECTED)

        assertEquals(listOf(FilmCard(a, "f1", "Heat 1995", null, null)), repo.browseFilms("Action"))
        assertEquals("Heat 1995", repo.playable(VodRef(VodKind.MOVIE, a, "f1"))?.title)
    }

    @Test fun matchedShowCardShowsTmdbTitleAndPoster() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1", name = "Office US", category = null))
        db.matchTitle(a, TitleKind.SHOW, "s1", 2316, "The Office", posterPath = "/o.jpg", badge = "FHD")

        assertEquals(
            listOf(ShowCard(a, "s1", "The Office", "https://image.tmdb.org/t/p/w342/o.jpg", null, "FHD")),
            repo.browseShows(null),
        )
    }

    @Test fun showCollisionUsesDisplayNames() = runTest {
        val a = db.addPlaylist("Alpha")
        val b = db.addPlaylist("Beta")
        insertShows(
            // Different playlist names, the same TMDB title: labelled.
            show(a, "s1", name = "Office US", category = null),
            show(b, "s2", name = "The Office", category = null),
            // The same playlist name, but one is matched to another title: not labelled.
            show(a, "s3", name = "Same", category = null),
            show(b, "s4", name = "Same", category = null),
            // Different TMDB titles that read the same: labelled.
            show(a, "s5", name = "Twin A", category = null),
            show(b, "s6", name = "Twin B", category = null),
        )
        db.matchTitle(a, TitleKind.SHOW, "s1", 2316, "The Office")
        db.matchTitle(b, TitleKind.SHOW, "s4", 77, "Different")
        db.matchTitle(a, TitleKind.SHOW, "s5", 10, "Twin")
        db.matchTitle(b, TitleKind.SHOW, "s6", 11, "Twin")

        val byKey = repo.browseShows(null).associateBy { it.key }
        assertEquals("Alpha", byKey.getValue("s1").playlistName)
        assertEquals("Beta", byKey.getValue("s2").playlistName)
        assertNull(byKey.getValue("s3").playlistName)
        assertNull(byKey.getValue("s4").playlistName)
        assertEquals("Alpha", byKey.getValue("s5").playlistName)
        assertEquals("Beta", byKey.getValue("s6").playlistName)
    }

    @Test fun allShowsSortByDisplayName() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1", name = "Zulu", category = null), show(a, "s2", name = "Mike", category = null))
        db.matchTitle(a, TitleKind.SHOW, "s1", 1, "alpha")

        assertEquals(listOf("alpha", "Mike"), repo.browseShows(null).map { it.name })
    }

    @Test fun searchFindsByTmdbTitleAndRawName() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "Le Fabuleux Destin"), film(a, "f2", name = "Zebra Amelie", position = 1))
        insertShows(show(a, "s1", name = "Office US"))
        db.matchTitle(a, TitleKind.FILM, "f1", 194, "Amelie", posterPath = "/a.jpg")
        db.matchTitle(a, TitleKind.SHOW, "s1", 2316, "The Office")

        val byTmdb = repo.searchFilms(searchPattern("amelie")!!, 10)
        // Ordered by the name shown: "Amelie" before "Zebra Amelie".
        assertEquals(listOf("Amelie", "Zebra Amelie"), byTmdb.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", byTmdb.first().logo)
        assertEquals(listOf("f1"), repo.searchFilms(searchPattern("fabuleux")!!, 10).map { it.key })
        assertEquals(listOf("The Office"), repo.searchShows(searchPattern("the off")!!, 10).map { it.name })
        assertEquals(listOf("s1"), repo.searchShows(searchPattern("office us")!!, 10).map { it.key })
    }

    @Test fun playableTitleUsesDisplayTitle() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", name = "Heat 1995 4K"))
        insertShows(show(a, "s", name = "BB"))
        db.vodWriteDao().upsertEpisodes(listOf(episode(a, "e1", "s", 1, 1), episode(a, "e2", "s", 1, 2, 1), episode(a, "x", "s", null, null, 2)))
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat")
        db.matchTitle(a, TitleKind.SHOW, "s", 1396, "Breaking Bad")
        db.tmdbDao().upsertEpisodes(1396, listOf(TmdbEpisodeEntity(1396, 1, 1, "Pilot", null)))

        assertEquals("Heat", repo.playable(VodRef(VodKind.MOVIE, a, "f1"))?.title)
        assertEquals("Heat", repo.displayTitle(VodRef(VodKind.MOVIE, a, "f1")))
        assertEquals("S1 E1 · Pilot", repo.playable(VodRef(VodKind.EPISODE, a, "e1"))?.title)
        assertEquals("Pilot", repo.episodeInfo(VodRef(VodKind.EPISODE, a, "e1"))?.name)
        // No TMDB row for the episode, or no number: the playlist's name as before.
        assertEquals("E e2", repo.playable(VodRef(VodKind.EPISODE, a, "e2"))?.title)
        assertEquals("E e2", repo.episodeInfo(VodRef(VodKind.EPISODE, a, "e2"))?.name)
        assertEquals("E x", repo.playable(VodRef(VodKind.EPISODE, a, "x"))?.title)
        assertNull(repo.displayTitle(VodRef(VodKind.MOVIE, a, "missing")))
        assertNull(repo.displayTitle(VodRef(VodKind.RECORDING, a, "rec:1")))
    }

    private suspend fun VodRepository.browseFilms(category: String) = films(category).asSnapshot()

    private suspend fun VodRepository.browseShows(category: String?) = shows(category).asSnapshot()
}
