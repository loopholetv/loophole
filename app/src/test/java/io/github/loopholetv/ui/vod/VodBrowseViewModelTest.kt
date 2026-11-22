package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.vod.ALL_SHOWS_GRID_THRESHOLD
import io.github.loopholetv.data.vod.CategoryRow
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VodBrowseViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var repo: VodRepository
    private lateinit var edits: ChannelEdits
    private lateinit var library: VodLibraryRepository

    @get:Rule val main = MainDispatcherRule()

    @Before fun setUp() {
        db = inMemoryDb()
        repo = VodRepository(db) { now }
        edits = ChannelEdits(db, ChannelOverlay(db))
        library = VodLibraryRepository(db, repo) { now }
    }

    @After fun tearDown() {
        main.clear()
        db.close()
    }

    private fun browse(kind: VodKind) = main.keep(VodBrowseViewModel(kind, repo, library, edits))

    private fun film(p: Long, key: String, category: String, seen: Instant = Instant.EPOCH) =
        FilmEntity(p, key, key, category, null, "http://s.test/$key.mp4", "ua", "ref", null, 0, seen)

    private fun show(p: Long, key: String, category: String? = null, seen: Instant = Instant.EPOCH) =
        ShowEntity(p, key, key, "Drama", category, null, seen, 0)

    private suspend fun insertFilms(vararg rows: FilmEntity) = db.vodWriteDao().upsertFilms(rows.toList())
    private suspend fun insertShows(vararg rows: ShowEntity) = db.vodWriteDao().insertShows(rows.toList())

    private suspend fun VodBrowseViewModel.loaded() = rows.first { it.isNotEmpty() }

    @Test fun moviesRowsRecentThenCategories() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action", seen = now), film(a, "f2", "Drama"))

        val rows = browse(VodKind.MOVIE).loaded()

        assertEquals(listOf("Recently added", "Action", "Drama"), rows.map { it.title })
        assertTrue(rows[0] is VodRowModel.Recent)
        assertEquals(listOf("f1"), (rows[0] as VodRowModel.Recent).cards.map { it.key })
        assertEquals("Action", (rows[1] as VodRowModel.Paged).category)
    }

    @Test fun recentOmittedWhenEmpty() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))

        val rows = browse(VodKind.MOVIE).loaded()

        assertEquals(listOf("Action"), rows.map { it.title })
    }

    @Test fun seriesUsesGridAboveThreshold() = runTest {
        val a = db.addPlaylist()
        insertShows(*(0..ALL_SHOWS_GRID_THRESHOLD).map { show(a, "s$it") }.toTypedArray())

        val rows = browse(VodKind.EPISODE).loaded()

        assertEquals(listOf("All shows"), rows.map { it.title })
        assertTrue(rows[0] is VodRowModel.Grid)
    }

    @Test fun seriesUsesPagedRowAtOrBelowThreshold() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1"), show(a, "s2", category = "Crime"))

        val rows = browse(VodKind.EPISODE).loaded()

        assertEquals(listOf("All shows", "Crime"), rows.map { it.title })
        assertEquals(null, (rows[0] as VodRowModel.Paged).category)
    }

    @Test fun hideCategoryHidesInEveryPlaylist() = runTest {
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        insertFilms(film(a, "f1", "Action"), film(b, "f2", "Action"), film(a, "f3", "Drama"))
        val vm = browse(VodKind.MOVIE)
        vm.loaded()

        vm.hideCategory("Action")

        assertEquals(listOf(HiddenCategory("Action", listOf(a, b))), vm.hidden.first { it.isNotEmpty() })
        assertEquals(listOf(CategoryRow("Drama", 1)), repo.filmCategories().first())
    }

    @Test fun everyCategoryHiddenIsReported() = runTest {
        val a = db.addPlaylist("A")
        insertFilms(film(a, "f1", "Action"), film(a, "f2", "Drama"))
        val vm = browse(VodKind.MOVIE)
        vm.loaded()
        assertEquals(false, vm.allHidden.value)

        vm.hideCategory("Action")
        vm.hideCategory("Drama")

        assertTrue(vm.allHidden.first { it })
        assertEquals(emptyList<VodRowModel>(), vm.rows.value)
        vm.showCategory(vm.hidden.value.first())
        assertEquals(false, vm.allHidden.first { !it })
    }

    @Test fun showHiddenCategoryUnhides() = runTest {
        val a = db.addPlaylist("A")
        insertFilms(film(a, "f1", "Action"))
        val vm = browse(VodKind.MOVIE)
        vm.loaded()
        vm.hideCategory("Action")
        val hidden = vm.hidden.first { it.isNotEmpty() }.single()

        vm.showCategory(hidden)

        assertEquals(emptyList<HiddenCategory>(), vm.hidden.first { it.isEmpty() })
        assertEquals(listOf(CategoryRow("Action", 1)), repo.filmCategories().first())
    }

    private suspend fun watchFilm(p: Long, key: String, positionMs: Long) =
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, p, key, positionMs, 100_000, false, now))

    @Test fun rowsStartWithContinueThenMyList() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action", seen = now), film(a, "f2", "Drama"), film(a, "f3", "Drama"))
        watchFilm(a, "f2", 50_000)
        library.setInMyList(TitleKind.FILM, a, "f3", true)

        val rows = browse(VodKind.MOVIE).loaded()

        assertEquals(listOf(CONTINUE_ROW_TITLE, MY_LIST_ROW_TITLE, "Recently added", "Action", "Drama"), rows.map { it.title })
        assertTrue(rows[0] is VodRowModel.Continue)
        assertTrue(rows[1] is VodRowModel.MyList)
        assertEquals(listOf("f3"), (rows[1] as VodRowModel.MyList).cards.map { it.key })
    }

    @Test fun emptyContinueAndMyListAreOmitted() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))

        val rows = browse(VodKind.MOVIE).loaded()

        assertEquals(listOf("Action"), rows.map { it.title })
    }

    @Test fun continueCardCarriesPlayAndCaption() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1"))
        db.vodWriteDao().upsertEpisodes(
            listOf(EpisodeEntity(a, "e1", "s1", "Drama", "Ep 1", 1, 1, null, "http://s.test/e1.mp4", null, null, null, 0, Instant.EPOCH)),
        )
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, a, "e1", 50_000, 100_000, false, now))

        val rows = browse(VodKind.EPISODE).loaded()

        val card = (rows[0] as VodRowModel.Continue).cards.single()
        assertEquals("s1", card.key)
        assertEquals("S1 E1", card.subtitle)
        assertEquals(0.5f, card.fraction)
        assertEquals(VodPlay(VodRef(VodKind.EPISODE, a, "e1"), 50_000), card.play)
    }

    @Test fun dismissRemovesCard() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"), film(a, "f2", "Action"))
        watchFilm(a, "f1", 50_000)
        val vm = browse(VodKind.MOVIE)
        val card = (vm.loaded()[0] as VodRowModel.Continue).cards.single()

        vm.dismissContinue(card)

        assertEquals(listOf("Action"), vm.rows.first { r -> r.none { it is VodRowModel.Continue } }.map { it.title })
    }

    private fun reminder(id: Long, playlist: Long, startMin: Long, lengthMin: Long = 60) = io.github.loopholetv.data.db.ReminderEntity(
        id = id, playlistId = playlist, channelKey = "c$id", channelName = "Channel $id", epgChannelId = null, title = "Show $id",
        subTitle = null, episodeKey = null, start = now.plusSeconds(startMin * 60), stop = now.plusSeconds((startMin + lengthMin) * 60),
    )

    @Test fun remindersLeadMyListUntilAired() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f3", "Action"))
        library.setInMyList(TitleKind.FILM, a, "f3", true)
        val reminders = kotlinx.coroutines.flow.MutableStateFlow(listOf(reminder(1, a, startMin = 30), reminder(2, a, startMin = 90)))
        val vm = main.keep(
            VodBrowseViewModel(VodKind.MOVIE, repo, library, edits, reminders = reminders, clock = { now }, channelLogo = { _, _ -> "http://l.test/x.png" }),
        )

        val cards = (vm.rows.first { r -> r.any { it is VodRowModel.MyList } && (r.first() as VodRowModel.MyList).cards.size == 3 }
            .first() as VodRowModel.MyList).cards

        assertEquals(listOf("Show 1", "Show 2", "f3"), cards.map { it.title })
        val first = cards.first().programme!!
        assertEquals(a, first.playlistId)
        assertEquals("c1", first.channelKey)
        assertEquals(now.plusSeconds(30 * 60).toEpochMilli(), first.startMs)
        assertEquals(1L, first.reminderId)
        assertEquals("http://l.test/x.png", cards.first().logo)
        assertEquals(null, cards.last().programme)

        // The first one has aired: it leaves the row.
        reminders.value = listOf(reminder(1, a, startMin = -90), reminder(2, a, startMin = 90))
        val after = (vm.rows.first { r -> (r.first() as? VodRowModel.MyList)?.cards?.size == 2 }.first() as VodRowModel.MyList).cards
        assertEquals(listOf("Show 2", "f3"), after.map { it.title })
    }

    @Test fun remindersAloneStillMakeAMyListRow() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1", category = "Drama"))
        val vm = main.keep(
            VodBrowseViewModel(VodKind.EPISODE, repo, library, edits, reminders = kotlinx.coroutines.flow.flowOf(listOf(reminder(1, a, 30))), clock = { now }),
        )

        val row = vm.rows.first { r -> r.any { it is VodRowModel.MyList } }.first() as VodRowModel.MyList

        assertEquals(MY_LIST_ROW_TITLE, row.title)
        assertEquals(listOf("Show 1"), row.cards.map { it.title })
    }
}
