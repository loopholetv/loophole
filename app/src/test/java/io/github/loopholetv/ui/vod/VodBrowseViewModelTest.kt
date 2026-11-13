package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.vod.ALL_SHOWS_GRID_THRESHOLD
import io.github.loopholetv.data.vod.CategoryRow
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDb()
        repo = VodRepository(db) { now }
        edits = ChannelEdits(db, ChannelOverlay(db))
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

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

        val rows = VodBrowseViewModel(VodKind.MOVIE, repo, edits).loaded()

        assertEquals(listOf("Recently added", "Action", "Drama"), rows.map { it.title })
        assertTrue(rows[0] is VodRowModel.Recent)
        assertEquals(listOf("f1"), (rows[0] as VodRowModel.Recent).cards.map { it.key })
        assertEquals("Action", (rows[1] as VodRowModel.Paged).category)
    }

    @Test fun recentOmittedWhenEmpty() = runTest {
        val a = db.addPlaylist()
        insertFilms(film(a, "f1", "Action"))

        val rows = VodBrowseViewModel(VodKind.MOVIE, repo, edits).loaded()

        assertEquals(listOf("Action"), rows.map { it.title })
    }

    @Test fun seriesUsesGridAboveThreshold() = runTest {
        val a = db.addPlaylist()
        insertShows(*(0..ALL_SHOWS_GRID_THRESHOLD).map { show(a, "s$it") }.toTypedArray())

        val rows = VodBrowseViewModel(VodKind.EPISODE, repo, edits).loaded()

        assertEquals(listOf("All shows"), rows.map { it.title })
        assertTrue(rows[0] is VodRowModel.Grid)
    }

    @Test fun seriesUsesPagedRowAtOrBelowThreshold() = runTest {
        val a = db.addPlaylist()
        insertShows(show(a, "s1"), show(a, "s2", category = "Crime"))

        val rows = VodBrowseViewModel(VodKind.EPISODE, repo, edits).loaded()

        assertEquals(listOf("All shows", "Crime"), rows.map { it.title })
        assertEquals(null, (rows[0] as VodRowModel.Paged).category)
    }

    @Test fun hideCategoryHidesInEveryPlaylist() = runTest {
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        insertFilms(film(a, "f1", "Action"), film(b, "f2", "Action"), film(a, "f3", "Drama"))
        val vm = VodBrowseViewModel(VodKind.MOVIE, repo, edits)
        vm.loaded()

        vm.hideCategory("Action")

        assertEquals(listOf(HiddenCategory("Action", listOf(a, b))), vm.hidden.first { it.isNotEmpty() })
        assertEquals(listOf(CategoryRow("Drama", 1)), repo.filmCategories().first())
    }

    @Test fun everyCategoryHiddenIsReported() = runTest {
        val a = db.addPlaylist("A")
        insertFilms(film(a, "f1", "Action"), film(a, "f2", "Drama"))
        val vm = VodBrowseViewModel(VodKind.MOVIE, repo, edits)
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
        val vm = VodBrowseViewModel(VodKind.MOVIE, repo, edits)
        vm.loaded()
        vm.hideCategory("Action")
        val hidden = vm.hidden.first { it.isNotEmpty() }.single()

        vm.showCategory(hidden)

        assertEquals(emptyList<HiddenCategory>(), vm.hidden.first { it.isEmpty() })
        assertEquals(listOf(CategoryRow("Action", 1)), repo.filmCategories().first())
    }
}
