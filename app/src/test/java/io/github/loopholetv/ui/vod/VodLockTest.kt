package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.ui.MainDispatcherRule
import io.github.loopholetv.ui.parental.LockMarks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VodLockTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var overlay: ChannelOverlay
    private lateinit var repo: VodRepository
    private lateinit var edits: ChannelEdits
    private lateinit var library: VodLibraryRepository

    @get:Rule val main = MainDispatcherRule()

    @Before fun setUp() {
        db = inMemoryDb()
        overlay = ChannelOverlay(db)
        repo = VodRepository(db) { now }
        edits = ChannelEdits(db, overlay)
        library = VodLibraryRepository(db, repo) { now }
    }

    @After fun tearDown() {
        main.clear()
        db.close()
    }

    private fun film(p: Long, key: String, category: String) =
        FilmEntity(p, key, key, category, null, "http://s.test/$key.mp4", "ua", "ref", null, 0, Instant.EPOCH)

    private fun show(p: Long, key: String, group: String) = ShowEntity(p, key, key, group, group, null, Instant.EPOCH, 0)

    private fun browse(kind: VodKind) = main.keep(VodBrowseViewModel(kind, repo, library, edits))

    @Test fun categoryLockHidesRowAfterRelock() = runTest {
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action"), film(b, "f2", "Action"), film(a, "f3", "Drama")))
        overlay.setLockState(unlocked = true)
        val vm = browse(VodKind.MOVIE)
        vm.rows.first { it.isNotEmpty() }

        vm.lockCategory("Action", true) { true }

        // Still listed while unlocked, in every playlist that has it.
        assertEquals(listOf("Action", "Drama"), repo.filmCategories().first().map { it.title })
        assertEquals(listOf(a, b), db.groupPrefsDao().let { dao -> listOf(a, b).filter { dao.get(it, "Action")?.locked == true } })

        overlay.setLockState(unlocked = false)
        assertEquals(listOf("Drama"), repo.filmCategories().first().map { it.title })

        overlay.setLockState(unlocked = true)
        vm.lockCategory("Action", false) { true }
        overlay.setLockState(unlocked = false)
        assertEquals(listOf("Action", "Drama"), repo.filmCategories().first().map { it.title })
    }

    @Test fun lockingACategoryAsksForThePinFirst() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action")))
        val vm = browse(VodKind.MOVIE)
        vm.rows.first { it.isNotEmpty() }

        vm.lockCategory("Action", true) { false }
        overlay.setLockState(unlocked = false)

        assertEquals(listOf("Action"), repo.filmCategories().first().map { it.title })
    }

    @Test fun unlockingACategoryAsksForThePinToo() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action")))
        val vm = browse(VodKind.MOVIE)
        vm.rows.first { it.isNotEmpty() }
        vm.lockCategory("Action", true) { true }
        db.lockDao().counts().first { it.groups == 1 }

        // The PIN step is cancelled (a menu left open across a relock): it stays locked.
        var asked = false
        vm.lockCategory("Action", false) { asked = true; false }
        assertTrue(asked)
        assertEquals(1, db.lockDao().counts().first().groups)
        vm.lockCategory("Action", false) { true }
        db.lockDao().counts().first { it.groups == 0 }
    }

    @Test fun hiddenAndLockedCategoryIsNotListedWhileLocked() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action"), film(a, "f2", "Drama")))
        edits.setGroupHidden(a, "Action", true)
        edits.setGroupHidden(a, "Drama", true)
        edits.setGroupLocked(a, "Action", true)

        overlay.setLockState(unlocked = false)
        assertEquals(listOf("Drama"), db.vodBrowseDao().hiddenGroups().first().map { it.groupTitle })

        overlay.setLockState(unlocked = true)
        assertEquals(listOf("Action", "Drama"), db.vodBrowseDao().hiddenGroups().first().map { it.groupTitle })
    }

    @Test fun moviesAndSeriesAreAbsentWhenEverythingIsLocked() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action"), film(a, "f2", "Drama")))
        db.vodWriteDao().insertShows(listOf(show(a, "s1", "Crime"), show(a, "s2", "Crime")))
        val dao = db.vodBrowseDao()
        overlay.setLockState(unlocked = false)
        assertTrue(dao.hasFilms().first())
        assertTrue(dao.hasShows().first())

        // Every film locked one by one, the only show group locked.
        edits.setLocked(ChannelRef(a, "f1"), true)
        edits.setLocked(ChannelRef(a, "f2"), true)
        edits.setGroupLocked(a, "Crime", true)
        assertFalse(dao.hasFilms().first())
        assertFalse(dao.hasShows().first())

        overlay.setLockState(unlocked = true)
        assertTrue(dao.hasFilms().first())
        assertTrue(dao.hasShows().first())

        // One film stays unlocked: Movies shows.
        overlay.setLockState(unlocked = false)
        edits.setLocked(ChannelRef(a, "f2"), false)
        assertTrue(dao.hasFilms().first())
    }

    @Test fun filmAndShowLockActions() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action")))
        db.vodWriteDao().insertShows(listOf(show(a, "s1", "Crime")))
        val progress = VodProgressRepository(db.vodProgressDao()) { now }
        val writes = mutableListOf<String>()
        val filmVm = main.keep(FilmViewModel(a, "f1", repo, progress, library, writeLocked = { writes += "film:$it" }))
        val showVm = main.keep(ShowViewModel(a, "s1", repo, progress, library, writeLocked = { writes += "show:$it" }))

        filmVm.setLocked(true) { false }
        showVm.setLocked(true) { false }
        assertEquals(emptyList<String>(), writes)

        filmVm.setLocked(true) { true }
        showVm.setLocked(true) { true }
        // Unlocking runs the PIN step too: a menu left open across a relock unlocks nothing.
        filmVm.setLocked(false) { false }
        showVm.setLocked(false) { false }
        assertEquals(listOf("film:true", "show:true"), writes)
        filmVm.setLocked(false) { true }
        showVm.setLocked(false) { true }
        assertEquals(listOf("film:true", "show:true", "film:false", "show:false"), writes)
    }

    @Test fun filmAndShowLockWritesReachTheirRows() = runTest {
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(listOf(film(a, "f1", "Action")))
        db.vodWriteDao().insertShows(listOf(show(a, "s1", "Crime")))
        edits.setLocked(ChannelRef(a, "f1"), true)
        edits.setShowLocked(a, "s1", true)

        overlay.setLockState(unlocked = false)
        assertEquals(null, repo.film(a, "f1").first())
        assertEquals(null, repo.show(a, "s1").first())
        overlay.setLockState(unlocked = true)
        assertEquals("f1", repo.film(a, "f1").first()?.film?.key)
        assertEquals("s1", repo.show(a, "s1").first()?.show?.key)
    }

    @Test fun padlockShownOnlyWhileUnlocked() {
        val channels = listOf(ChannelRef(1, "c1"))
        val groups = listOf(ChannelRef(1, "News"))
        val shows = listOf(ChannelRef(1, "s1"))

        val open = LockMarks.of(LockState.Unlocked(null), channels, groups, shows)
        assertTrue(open.channel(1, "c1"))
        assertFalse(open.channel(1, "c2"))
        assertTrue(open.group(1, "News"))
        assertTrue(open.categoryTitle("News"))
        assertTrue(open.show(1, "s1"))
        assertTrue(open.channelOrGroup(1, "other", "News"))
        assertTrue(open.lockAvailable)

        for (state in listOf(LockState.Locked, LockState.NoPin)) {
            val marks = LockMarks.of(state, channels, groups, shows)
            assertFalse(marks.channel(1, "c1"))
            assertFalse(marks.group(1, "News"))
            assertFalse(marks.categoryTitle("News"))
            assertFalse(marks.show(1, "s1"))
            assertFalse(marks.channelOrGroup(1, "c1", "News"))
        }
        // The film and show pages offer their lock pill only with a PIN entered; rows also offer it with no PIN.
        assertTrue(open.unlocked)
        assertFalse(LockMarks.of(LockState.NoPin, channels, groups, shows).unlocked)
        assertFalse(LockMarks.of(LockState.Locked, channels, groups, shows).unlocked)
        assertFalse(LockMarks.of(LockState.Locked, channels, groups, shows).lockAvailable)
        assertTrue(LockMarks.of(LockState.NoPin, channels, groups, shows).lockAvailable)
    }
}
