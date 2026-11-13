package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodProgressRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShowViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private var playlist = 0L

    @Before fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDb()
        playlist = db.addPlaylist()
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm(key: String = "s") =
        ShowViewModel(playlist, key, VodRepository(db) { now }, VodProgressRepository(db.vodProgressDao()) { now })

    private fun ep(key: String, season: Int?, episode: Int?, show: String = "s", position: Int = 0) =
        EpisodeEntity(playlist, key, show, "Drama", "Name $key", season, episode, null, "http://s.test/$key.mp4", null, null, null, position, now)

    private suspend fun insertShow(key: String = "s", episodes: List<EpisodeEntity>) {
        db.vodWriteDao().insertShows(listOf(ShowEntity(playlist, key, "The Show", "Drama", null, "http://logo.test/s.png", now, 0)))
        db.vodWriteDao().upsertEpisodes(episodes)
    }

    private suspend fun progress(key: String, positionMs: Long, watched: Boolean = false, durationMs: Long? = 1_000_000, at: Long = 1) =
        db.vodProgressDao().upsert(
            VodProgressEntity(VodKind.EPISODE, playlist, key, positionMs, durationMs, watched, Instant.ofEpochSecond(at)),
        )

    private suspend fun ShowViewModel.loaded() = ui.first { it != null }!!

    @Test fun summaryPluralisation() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        insertShow("t", (1..24).map { ep("t$it", 1 + it / 9, it, show = "t", position = it) })

        assertEquals("1 season · 1 episode", vm("s").loaded().summary)
        assertEquals("3 seasons · 24 episodes", vm("t").loaded().summary)
    }

    @Test fun summaryLeavesOutSeasonsWhenThereAreNoneNumbered() = runTest {
        insertShow("s", listOf(ep("a", null, null), ep("b", null, null, position = 1)))

        assertEquals("2 episodes", vm().loaded().summary)
    }

    @Test fun tabsSortedOtherLast() = runTest {
        insertShow("s", listOf(ep("x", null, null), ep("b", 2, 1), ep("a", 1, 1)))
        val ui = vm().loaded()

        assertEquals(listOf("Season 1", "Season 2", "Other"), ui.tabs)
    }

    @Test fun opensOnContinueSeason() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 2, 1), ep("c", 2, 2, position = 1)))
        progress("b", positionMs = 120_000)
        val ui = vm().loaded()

        assertEquals(1, ui.selectedTab)
        assertEquals(listOf("b", "c"), ui.episodes.map { it.key })
        assertEquals("b", ui.continueKey)
        assertEquals(120_000L, ui.continueStartMs)
    }

    @Test fun selectingATabShowsItsEpisodesAndSurvivesProgressUpdates() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 2, 1), ep("x", null, null)))
        val model = vm()
        model.loaded()

        model.selectTab(2)
        progress("a", positionMs = 90_000)
        val ui = model.ui.first { it?.continueKey == "a" }!!

        assertEquals(2, ui.selectedTab)
        assertEquals(listOf("x"), ui.episodes.map { it.key })
        assertEquals("", ui.episodes.single().label)
    }

    @Test fun continueLabelForStartedAndFresh() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("n", null, null, position = 2)))

        assertEquals("Play S1 E1", vm().loaded().continueLabel)

        progress("b", positionMs = 120_000)
        val started = vm().ui.first { it?.continueKey == "b" }!!
        assertEquals("Continue S1 E2", started.continueLabel)

        progress("n", positionMs = 60_000, at = 5)
        val unnumbered = vm().ui.first { it?.continueKey == "n" }!!
        assertEquals("Continue Name n", unnumbered.continueLabel)
    }

    @Test fun unnumberedFreshEpisodeSaysPlayAndItsName() = runTest {
        insertShow("s", listOf(ep("n", null, null)))

        assertEquals("Play Name n", vm().loaded().continueLabel)
    }

    @Test fun episodeRowsShowProgress() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("c", 1, 3, position = 2)))
        progress("a", positionMs = 0, watched = true, at = 1)
        progress("b", positionMs = 250_000, durationMs = 1_000_000, at = 2)
        val rows = vm().ui.first { it?.episodes?.any { r -> r.fraction != null } == true }!!.episodes

        assertEquals(listOf("E1", "E2", "E3"), rows.map { it.label })
        assertTrue(rows[0].watched)
        assertNull(rows[0].fraction)
        assertEquals(0.25f, rows[1].fraction!!, 0.001f)
        assertEquals(250_000L, rows[1].resumeMs)
        assertFalse(rows[2].watched)
        assertNull(rows[2].resumeMs)
    }
}
