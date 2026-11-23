package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.matchTitle
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
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.data.vod.RatingMin
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VodBrowseViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var repo: VodRepository
    private lateinit var edits: ChannelEdits
    private lateinit var library: VodLibraryRepository

    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

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

    @Test fun matchedCardsCarryTmdbTitleAndBadge() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action", seen = now), film(a, "f2", "Action"))
        watchFilm(a, "f2", 50_000)
        library.setInMyList(TitleKind.FILM, a, "f1", true)
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat", badge = "4K")
        db.matchTitle(a, TitleKind.FILM, "f2", 950, "Ronin", badge = "HD")

        val rows = browse(VodKind.MOVIE).loaded()

        val cont = (rows.first { it is VodRowModel.Continue } as VodRowModel.Continue).cards.single()
        assertEquals("Ronin" to "HD", cont.title to cont.badge)
        for (row in listOf(rows.first { it is VodRowModel.MyList }, rows.first { it is VodRowModel.Recent })) {
            val card = (row as VodRowModel.Fixed).cards.single()
            assertEquals("Heat" to "4K", card.title to card.badge)
        }
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

    // Sort & filter

    private fun settings(scope: CoroutineScope) = SettingsStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { File(tmp.root, "settings.preferences_pb") },
    )

    private fun filtering(kind: VodKind, store: SettingsStore? = null, hasKey: Boolean = true): VodBrowseViewModel {
        val titleKind = if (kind == VodKind.MOVIE) TitleKind.FILM else TitleKind.SHOW
        return main.keep(
            VodBrowseViewModel(
                kind, repo, library, edits,
                hasKey = flowOf(hasKey),
                savedFilter = store?.vodFilter(titleKind) ?: flowOf(VodFilter()),
                saveFilter = { f -> store?.setVodFilter(titleKind, f) },
            ),
        )
    }

    @Test fun unreadableSavedFilterFallsBackToNoFilter() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))
        val vm = main.keep(
            VodBrowseViewModel(
                VodKind.MOVIE, repo, library, edits,
                hasKey = flowOf(true),
                savedFilter = kotlinx.coroutines.flow.flow { throw java.io.IOException("unreadable") },
            ),
        )

        val rows = vm.loaded()

        assertTrue(rows.first() is VodRowModel.FilterButton)
        assertEquals(VodFilter(), vm.filter.value)
    }

    @Test fun savedGenreThatIsGoneIsDropped() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"), film(a, "f2", "Action"))
        db.matchTitle(a, TitleKind.FILM, "f1", 1, "Heat")
        db.matchTitle(a, TitleKind.FILM, "f2", 2, "Ronin")
        val dao = db.tmdbDao()
        dao.upsertGenres(
            listOf(
                io.github.loopholetv.data.db.TmdbGenreEntity(io.github.loopholetv.data.tmdb.TmdbKind.MOVIE, 28, "Action", "en-US"),
                io.github.loopholetv.data.db.TmdbGenreEntity(io.github.loopholetv.data.tmdb.TmdbKind.MOVIE, 99, "Documentary", "en-US"),
            ),
        )
        dao.upsertTitle(dao.titleNow(io.github.loopholetv.data.tmdb.TmdbKind.MOVIE, 1)!!, listOf(28))
        dao.upsertTitle(dao.titleNow(io.github.loopholetv.data.tmdb.TmdbKind.MOVIE, 2)!!, listOf(28))
        // Saved while a documentary was in the library; none is now.
        val vm = main.keep(
            VodBrowseViewModel(
                VodKind.MOVIE, repo, library, edits,
                hasKey = flowOf(true),
                savedFilter = flowOf(VodFilter(genres = setOf(28, 99))),
            ),
        )

        val f = vm.filter.first { !it.isDefault }

        assertEquals(setOf(28), f.genres)
        val bar = vm.rows.first { r -> r.any { it is VodRowModel.FilterBar } }[0] as VodRowModel.FilterBar
        assertEquals(2, bar.count)
    }

    @Test fun defaultFilterShowsButtonPlusNormalRows() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action", seen = now), film(a, "f2", "Drama"))

        val rows = filtering(VodKind.MOVIE).loaded()

        assertTrue(rows[0] is VodRowModel.FilterButton)
        assertEquals(listOf("Recently added", "Action", "Drama"), rows.drop(1).map { it.title })
    }

    @Test fun detailsWithoutAKeyStillShowTheButton() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))
        db.matchTitle(a, TitleKind.FILM, "f1", 949, "Heat")

        val rows = filtering(VodKind.MOVIE, hasKey = false).rows.first { r -> r.any { it is VodRowModel.FilterButton } }

        assertTrue(rows[0] is VodRowModel.FilterButton)
    }

    @Test fun noKeyNoDetailsHidesFilterButton() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))

        val rows = filtering(VodKind.MOVIE, hasKey = false).loaded()

        assertEquals(listOf("Action"), rows.map { it.title })
        assertTrue(rows.none { it is VodRowModel.FilterButton })
    }

    @Test fun activeFilterShowsOnlyBarAndGrid() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action", seen = now), film(a, "f2", "Drama"))
        watchFilm(a, "f2", 50_000)
        library.setInMyList(TitleKind.FILM, a, "f1", true)
        val vm = filtering(VodKind.MOVIE)
        vm.loaded()

        vm.setFilter(VodFilter(sort = VodSort.TITLE))

        val rows = vm.rows.first { r -> r.any { it is VodRowModel.FilterBar } }
        assertEquals(2, rows.size)
        assertEquals(VodRowModel.FilterBar("by title", 2), rows[0])
        assertTrue(rows[1] is VodRowModel.Grid)
        assertEquals(VodSort.TITLE, vm.filter.value.sort)
    }

    @Test fun filterPersistsAcrossViewModelRecreation() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))
        val store = settings(backgroundScope)
        val first = filtering(VodKind.MOVIE, store)
        first.loaded()
        first.setFilter(VodFilter(rating = RatingMin.SEVEN))
        store.vodFilter(TitleKind.FILM).first { it.rating == RatingMin.SEVEN }
        main.clear()

        val again = filtering(VodKind.MOVIE, store)

        val rows = again.rows.first { r -> r.any { it is VodRowModel.FilterBar } }
        assertEquals(VodRowModel.FilterBar("★7+", 0), rows[0])
        assertEquals(RatingMin.SEVEN, again.filter.first { it.rating == RatingMin.SEVEN }.rating)
    }

    @Test fun clearRestoresRows() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"), film(a, "f2", "Drama"))
        val store = settings(backgroundScope)
        val vm = filtering(VodKind.MOVIE, store)
        vm.loaded()
        vm.setFilter(VodFilter(sort = VodSort.YEAR))
        vm.rows.first { r -> r.any { it is VodRowModel.FilterBar } }

        vm.clearFilter()

        val rows = vm.rows.first { r -> r.any { it is VodRowModel.FilterButton } }
        assertEquals(listOf("Action", "Drama"), rows.drop(1).map { it.title })
        assertEquals(VodFilter(), vm.filter.value)
        assertEquals(VodFilter(), store.vodFilter(TitleKind.FILM).first { it.isDefault })
    }

    @Test fun seriesHasNoRuntimeOptions() = runTest {
        assertEquals(false, filtering(VodKind.EPISODE).offersRuntime)
        assertEquals(true, filtering(VodKind.MOVIE).offersRuntime)
        assertTrue(VodSort.RUNTIME !in sortChoices(offersRuntime = false))
        assertTrue(VodSort.RUNTIME in sortChoices(offersRuntime = true))
    }

    @Test fun seriesFilterDropsRuntime() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1", category = "Crime"))
        val store = settings(backgroundScope)
        store.setVodFilter(TitleKind.SHOW, VodFilter(runtime = RuntimeBand.OVER_120, sort = VodSort.RUNTIME))

        // A stored runtime choice is dropped on load: no invisible filter hides shows.
        val vm = filtering(VodKind.EPISODE, store)
        val rows = vm.loaded()
        assertTrue(rows[0] is VodRowModel.FilterButton)
        assertEquals(VodFilter(), vm.filter.value)

        // And on set: the band goes, the sort falls back to Recently added.
        vm.setFilter(VodFilter(rating = RatingMin.SIX, runtime = RuntimeBand.UNDER_90, sort = VodSort.RUNTIME))
        assertEquals(VodFilter(rating = RatingMin.SIX), vm.filter.value)
    }
}
