package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FilmViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var vm: FilmViewModel
    private lateinit var library: VodLibraryRepository
    private var playlist = 0L

    @get:Rule val main = MainDispatcherRule()

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        playlist = db.addPlaylist()
        db.vodWriteDao().upsertFilms(
            listOf(FilmEntity(playlist, "f1", "Heat", "Crime", "http://logo.test/h.png", "http://s.test/f1.mp4", null, null, null, 0, now)),
        )
        val repo = VodRepository(db) { now }
        library = VodLibraryRepository(db, repo) { now }
        vm = main.keep(FilmViewModel(playlist, "f1", repo, VodProgressRepository(db.vodProgressDao()) { now }, library))
    }

    @After fun tearDown() {
        main.clear()
        db.close()
    }

    private suspend fun saveProgress(positionMs: Long, durationMs: Long? = null) = db.vodProgressDao().upsert(
        VodProgressEntity(VodKind.MOVIE, playlist, "f1", positionMs, durationMs, false, now),
    )

    @Test fun notStartedShowsPlay() = runTest {
        val ui = vm.ui.first { it != null }!!

        assertEquals("Heat", ui.title)
        assertEquals("Crime", ui.category)
        assertEquals("http://logo.test/h.png", ui.logo)
        assertNull(ui.resumeMs)
        assertNull(ui.fraction)
        assertEquals(listOf("Play"), filmButtons(ui).map { it.label })
    }

    @Test fun startedShowsResumeAndStartOver() = runTest {
        saveProgress(2_530_000, durationMs = 5_060_000)
        val ui = vm.ui.first { it?.resumeMs != null }!!

        assertEquals(2_530_000L, ui.resumeMs)
        assertEquals(0.5f, ui.fraction!!, 0.001f)
        val buttons = filmButtons(ui)
        assertEquals(listOf("Resume from 42:10", "Start over"), buttons.map { it.label })
        assertEquals(listOf(2_530_000L, 0L), buttons.map { it.startMs })
    }

    @Test fun resumeLabelFormat() {
        assertEquals("Resume from 42:10", resumeLabel(2_530_000))
        assertEquals("Resume from 1:02:03", resumeLabel(3_723_000))
        assertEquals("Resume from 0:45", resumeLabel(45_000))
    }

    @Test fun positionBelowTheThresholdStillPlaysFromTheStart() = runTest {
        saveProgress(10_000, durationMs = 5_000_000)
        val ui = vm.ui.first { it != null }!!

        assertNull(ui.resumeMs)
        assertEquals(listOf("Play"), filmButtons(ui).map { it.label })
    }

    @Test fun filmUiReflectsMyList() = runTest {
        assertFalse(vm.ui.first { it != null }!!.inMyList)

        library.setInMyList(TitleKind.FILM, playlist, "f1", true)

        assertTrue(vm.ui.first { it?.inMyList == true }!!.inMyList)
    }

    @Test fun toggleMyListAddsAndRemoves() = runTest {
        vm.ui.first { it != null }

        vm.toggleMyList()
        vm.ui.first { it?.inMyList == true }
        assertTrue(library.inMyList(TitleKind.FILM, playlist, "f1").first())

        vm.toggleMyList()
        vm.ui.first { it?.inMyList == false }
        assertFalse(library.inMyList(TitleKind.FILM, playlist, "f1").first())
    }

    @Test fun myListLabelShowsTheState() {
        assertEquals("+ My list", myListLabel(false))
        assertEquals("✓ In My list", myListLabel(true))
    }
}
