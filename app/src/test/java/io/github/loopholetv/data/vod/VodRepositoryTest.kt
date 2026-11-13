package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
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
        assertEquals(listOf(a), repo.playlistsWithCategory("Action"))
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

    private suspend fun VodRepository.browseFilms(category: String) = films(category).asSnapshot()

    private suspend fun VodRepository.browseShows(category: String?) = shows(category).asSnapshot()
}
