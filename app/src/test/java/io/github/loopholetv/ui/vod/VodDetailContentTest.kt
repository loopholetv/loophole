package io.github.loopholetv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
// A TV-sized window: the default one is too narrow for the page's poster column plus the list.
@Config(qualifiers = "w1280dp-h720dp")
@RunWith(AndroidJUnit4::class)
class VodDetailContentTest {
    @get:Rule val compose = createComposeRule()

    @Before fun useRealMain() = Dispatchers.resetMain()

    /** Focuses the control with [text] and presses OK on it, as the remote does (TV controls ignore touch clicks). */
    private fun okOn(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(text).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private val film = FilmUi("Heat", "Crime", null, null, null)

    private fun episode(key: String, label: String, resume: Long? = null) =
        EpisodeRowUi(key, label, "Name $key", null, false, resume)

    private val show = ShowUi(
        name = "The Show", logo = null, summary = "2 seasons · 3 episodes",
        tabs = listOf("Season 1", "Season 2"), selectedTab = 0,
        episodes = listOf(episode("a", "E1"), episode("b", "E2")),
        continueLabel = "Play S1 E1", continueKey = "a", continueStartMs = 0,
    )

    @Test fun filmPageFocusesPlay() {
        compose.setContent { FilmContent(film, onPlay = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun filmPageFocusesResumeAndKeepsItWhenProgressArrives() {
        var ui by mutableStateOf(film)
        compose.setContent { FilmContent(ui, onPlay = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertIsFocused()

        ui = film.copy(resumeMs = 2_530_000L, fraction = 0.4f)
        compose.waitForIdle()

        compose.onNodeWithText("Resume from 42:10").assertIsFocused()
        compose.onNodeWithText("Start over").assertExists()
    }

    @Test fun filmButtonsStartAtTheRightPlace() {
        val started = mutableListOf<Long>()
        compose.setContent { FilmContent(film.copy(resumeMs = 61_000L), onPlay = { started += it }) }
        compose.waitForIdle()

        okOn("Resume from 1:01")
        okOn("Start over")

        assertEquals(listOf(61_000L, 0L), started)
    }

    @Test fun showPageFocusesContinue() {
        compose.setContent { ShowContent(show, onSelectTab = {}, onPlay = { _, _ -> }) }
        compose.waitForIdle()

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test fun showPageKeepsFocusOnContinueWhenProgressArrives() {
        var ui by mutableStateOf(show)
        compose.setContent { ShowContent(ui, onSelectTab = {}, onPlay = { _, _ -> }) }
        compose.waitForIdle()

        ui = show.copy(continueLabel = "Continue S1 E2", continueKey = "b", continueStartMs = 90_000)
        compose.waitForIdle()

        compose.onNodeWithText("Continue S1 E2").assertIsFocused()
    }

    @Test fun showPlaysEpisodesAndContinueFromTheirPositions() {
        val started = mutableListOf<Pair<String, Long>>()
        val ui = show.copy(
            continueLabel = "Continue S1 E2", continueKey = "b", continueStartMs = 90_000,
            episodes = listOf(episode("a", "E1"), episode("b", "E2", resume = 90_000)),
        )
        compose.setContent { ShowContent(ui, onSelectTab = {}, onPlay = { k, ms -> started += k to ms }) }
        compose.waitForIdle()

        okOn("Continue S1 E2")
        okOn("Name a")

        assertEquals(listOf("b" to 90_000L, "a" to 0L), started)
    }

    @Test fun filmPageReturnsFocusToTheButtonUsed() {
        compose.setContent { FilmContent(film.copy(resumeMs = 61_000L), lastUsedButton = 1, onPlay = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Start over").assertIsFocused()
    }

    @Test fun filmPageFallsBackToPrimaryWhenTheUsedButtonIsGone() {
        compose.setContent { FilmContent(film, lastUsedButton = 1, onPlay = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun filmPageReportsTheButtonUsed() {
        val used = mutableListOf<Int>()
        compose.setContent { FilmContent(film.copy(resumeMs = 61_000L), onUsed = { used += it }, onPlay = {}) }
        compose.waitForIdle()

        okOn("Start over")

        assertEquals(listOf(1), used)
    }

    private fun longShow(selected: Int = 0) = show.copy(
        episodes = (1..30).map { episode("e$it", "E$it") }, selectedTab = selected,
    )

    @Test fun showPageReturnsFocusToTheEpisodePlayed() {
        compose.setContent { ShowContent(longShow(), onSelectTab = {}, restoreEpisodeKey = "e25", onPlay = { _, _ -> }) }
        compose.waitForIdle()

        compose.onNodeWithText("Name e25").assertIsFocused()
    }

    @Test fun showPageFallsBackToContinueWhenTheEpisodeIsGone() {
        compose.setContent { ShowContent(show, onSelectTab = {}, restoreEpisodeKey = "gone", onPlay = { _, _ -> }) }
        compose.waitForIdle()

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test fun showPageReportsTheEpisodeOrContinueUsed() {
        val used = mutableListOf<String?>()
        compose.setContent { ShowContent(show, onSelectTab = {}, onEpisodeUsed = { used += it }, onPlay = { _, _ -> }) }
        compose.waitForIdle()

        okOn("Name b")
        okOn("Play S1 E1")

        assertEquals(listOf("b", null), used)
    }

    @Test fun pageClosesWhenTheItemIsGoneOnceLoaded() {
        var closed = 0
        compose.setContent { CloseWhenGone(present = false, loaded = true, onBack = { closed++ }) }
        compose.waitForIdle()

        assertEquals(1, closed)
    }

    @Test fun pageDoesNotCloseBeforeTheFirstRealEmission() {
        var loaded by mutableStateOf(false)
        var present by mutableStateOf(false)
        var closed = 0
        compose.setContent { CloseWhenGone(present, loaded, onBack = { closed++ }) }
        compose.waitForIdle()
        assertEquals(0, closed)

        // The first emission is the item: loaded and present arrive together, so no close.
        present = true
        loaded = true
        compose.waitForIdle()
        assertEquals(0, closed)

        present = false
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun showPageWithoutContinueFocusesTheSeasonTab() {
        val ui = show.copy(continueLabel = null, continueKey = null)
        compose.setContent { ShowContent(ui, onSelectTab = {}, restoreEpisodeKey = "gone", onPlay = { _, _ -> }) }
        compose.waitForIdle()

        // The tab is lazily composed, so the first focus attempts can miss; the page retries.
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Season 1").assertIsFocused()
    }

    @Test fun showPageFindsALateComposedRestoreTarget() {
        val ui = show.copy(episodes = (1..300).map { episode("e$it", "E$it") })
        compose.setContent { ShowContent(ui, onSelectTab = {}, restoreEpisodeKey = "e280", onPlay = { _, _ -> }) }
        compose.waitForIdle()

        compose.onNodeWithText("Name e280").assertIsFocused()
    }

    @Test fun switchingSeasonWhileRestoringNeitherCrashesNorStealsFocus() {
        var ui by mutableStateOf(longShow())
        val shorter = show.copy(selectedTab = 1, continueLabel = null, continueKey = null, episodes = listOf(episode("x1", "E1")))
        compose.mainClock.autoAdvance = false
        compose.setContent { ShowContent(ui, onSelectTab = {}, restoreEpisodeKey = "e25", onPlay = { _, _ -> }) }
        // Composed with the restore pending (nothing focused yet); then the user is on another season.
        compose.mainClock.advanceTimeByFrame()
        ui = shorter
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)

        // No crash from the stale restore index, and no pull onto an unrelated episode.
        compose.onNodeWithText("Name x1").assertIsNotFocused()
    }
}
