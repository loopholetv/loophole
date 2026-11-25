package io.github.loopholetv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun filmLockPillOnlyWhenOffered() {
        var offer by mutableStateOf(false)
        var locked by mutableStateOf(false)
        compose.setContent { FilmContent(film, offerLock = offer, locked = locked, onPlay = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Lock film").assertDoesNotExist()
        compose.onNodeWithText("Unlock film").assertDoesNotExist()

        offer = true
        compose.waitForIdle()
        compose.onNodeWithText("Lock film").assertExists()
        locked = true
        compose.waitForIdle()
        compose.onNodeWithText("Unlock film").assertExists()
    }

    @Test fun showLockPillOnlyWhenOffered() {
        var offer by mutableStateOf(false)
        var locked by mutableStateOf(false)
        compose.setContent { ShowContent(show, onSelectTab = {}, offerLock = offer, locked = locked, onPlay = { _, _ -> }) }
        compose.waitForIdle()
        compose.onNodeWithText("Lock show").assertDoesNotExist()
        compose.onNodeWithText("Unlock show").assertDoesNotExist()

        offer = true
        compose.waitForIdle()
        compose.onNodeWithText("Lock show").assertExists()
        locked = true
        compose.waitForIdle()
        compose.onNodeWithText("Unlock show").assertExists()
    }

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

    @Test fun myListPillKeepsFocusAfterToggle() {
        var film by mutableStateOf(film)
        compose.setContent { FilmContent(film, onToggleMyList = { film = film.copy(inMyList = !film.inMyList) }, onPlay = {}) }
        compose.waitForIdle()

        okOn("+ My list")
        compose.waitForIdle()

        compose.onNodeWithText("✓ In My list").assertIsFocused()
        compose.onNodeWithText("Play").assertIsNotFocused()
    }

    @Test fun showMyListPillKeepsFocusAfterToggle() {
        var ui by mutableStateOf(show)
        compose.setContent {
            ShowContent(ui, onSelectTab = {}, onToggleMyList = { ui = ui.copy(inMyList = !ui.inMyList) }, onPlay = { _, _ -> })
        }
        compose.waitForIdle()

        okOn("+ My list")
        compose.waitForIdle()

        compose.onNodeWithText("✓ In My list").assertIsFocused()
    }

    @Test fun showPageFocusesLastEpisodeFromSavedState() {
        val handle = SavedStateHandle(mapOf(VOD_LAST_EPISODE to "e25"))
        compose.setContent {
            val restore = remember { handle.takeLastEpisode() }
            ShowContent(longShow(), onSelectTab = {}, restoreEpisodeKey = restore, onPlay = { _, _ -> })
        }
        compose.waitForIdle()

        compose.onNodeWithText("Name e25").assertIsFocused()
        // Read once: a later return to the page without a player run does not land there again.
        assertNull(handle.get<String>(VOD_LAST_EPISODE))
    }

    private val details = TitleDetailsUi(
        year = 1995, runtimeMinutes = 170, seasonCount = null, rating = 8.26, genres = listOf("Crime"),
        overview = "A thief and a detective.", cast = listOf("Al Pacino", "Robert De Niro"), backdropUrl = null, badge = "4K",
    )

    @Test fun unmatchedFilmPageHasNoWrongDetails() {
        compose.setContent { FilmContent(film, onPlay = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Crime").assertExists()
        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
    }

    @Test fun matchedFilmPageShowsDetailsAndWrongDetailsFallsBackToPlay() {
        var ui by mutableStateOf(film.copy(details = details))
        var rejected = 0
        compose.setContent {
            FilmContent(ui, onWrongDetails = { rejected++; ui = ui.copy(details = null) }, onPlay = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("1995 · 2 h 50 min · ★ 8.3 · Crime").assertExists()
        compose.onNodeWithText("Crime").assertDoesNotExist()
        compose.onNodeWithText("A thief and a detective.").assertExists()
        compose.onNodeWithText("Al Pacino, Robert De Niro").assertExists()
        compose.onNodeWithText("4K").assertExists()

        okOn(WRONG_DETAILS_LABEL)
        compose.waitForIdle()

        assertEquals(1, rejected)
        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Crime").assertExists()
        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun matchedShowPageWrongDetailsFallsBackToContinue() {
        var ui by mutableStateOf(
            show.copy(
                details = details.copy(runtimeMinutes = null, seasonCount = 2),
                episodes = listOf(episode("a", "E1").copy(tmdbTitle = "Pilot", plot = "It begins.", season = 1), episode("b", "E2")),
            ),
        )
        compose.setContent {
            ShowContent(ui, onSelectTab = {}, onWrongDetails = { ui = ui.copy(details = null) }, onPlay = { _, _ -> })
        }
        compose.waitForIdle()

        compose.onNodeWithText("1995 · 2 seasons · ★ 8.3 · Crime").assertExists()
        compose.onNodeWithText("S1 E1 · Pilot").assertExists()
        compose.onNodeWithText("It begins.").assertExists()
        compose.onNodeWithText("Name b").assertExists()

        okOn(WRONG_DETAILS_LABEL)
        compose.waitForIdle()

        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
        compose.onNodeWithText("2 seasons · 3 episodes").assertExists()
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test fun showWithoutContinueWrongDetailsFocusesTheSeasonTab() {
        var ui by mutableStateOf(show.copy(continueKey = null, continueLabel = null, details = details.copy(runtimeMinutes = null)))
        compose.setContent {
            ShowContent(ui, onSelectTab = {}, onWrongDetails = { ui = ui.copy(details = null) }, onPlay = { _, _ -> })
        }
        compose.waitForIdle()
        // Let the page's own opening focus on the lazily composed tab settle first.
        compose.mainClock.advanceTimeBy(1_000)

        okOn(WRONG_DETAILS_LABEL)
        compose.waitForIdle()

        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Season 1").assertIsFocused()
    }

    @Test fun showWithoutTabsWrongDetailsFocusesMyList() {
        var ui by mutableStateOf(
            show.copy(continueKey = null, continueLabel = null, tabs = emptyList(), episodes = emptyList(), details = details),
        )
        compose.setContent {
            ShowContent(ui, onSelectTab = {}, onWrongDetails = { ui = ui.copy(details = null) }, onPlay = { _, _ -> })
        }
        compose.waitForIdle()

        okOn(WRONG_DETAILS_LABEL)
        compose.waitForIdle()

        compose.onNodeWithText("+ My list").assertIsFocused()
    }

    // A short window stands in for large fonts: the header is then taller than the space it would leave.
    @Config(qualifiers = "w960dp-h480dp")
    @Test fun showPageKeepsRoomForTheEpisodeList() {
        val long = "A very long plot. ".repeat(80)
        compose.setContent {
            ShowContent(
                show.copy(details = details.copy(overview = long, cast = List(40) { "Actor $it" })),
                onSelectTab = {},
                onPlay = { _, _ -> },
            )
        }
        compose.waitForIdle()

        // The list keeps its minimum height (less its top padding) rather than shrinking to what is left.
        compose.onNodeWithTag(EPISODE_LIST_TAG).assertHeightIsAtLeast(MIN_LIST_HEIGHT - 12.dp)
        compose.onNodeWithText("Name a").assertIsDisplayed()
        // The text gives way; the buttons and the season tabs stay in full.
        compose.onNodeWithText("Play S1 E1").assertIsDisplayed()
        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertIsDisplayed()
        compose.onNodeWithText("Season 1").assertIsDisplayed()
        // In full, not squeezed into a sliver of the header.
        compose.onNodeWithText("Season 1").assertHeightIsAtLeast(32.dp)
    }

    private val noEpisodes = show.copy(
        summary = "0 episodes", tabs = emptyList(), episodes = emptyList(), continueLabel = null, continueKey = null,
    )

    @Test fun noEpisodesPageStillTakesFocus() {
        compose.setContent { ShowContent(noEpisodes.copy(episodesState = EpisodesState.LOADING), onSelectTab = {}, onPlay = { _, _ -> }) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText(LOADING_EPISODES).assertIsDisplayed()
        compose.onNodeWithText(TRY_AGAIN).assertDoesNotExist()
        compose.onNodeWithText("+ My list").assertIsFocused()
    }

    @Test fun emptyShowSaysNoEpisodesYetAndOffersTryAgain() {
        var retried = 0
        compose.setContent {
            ShowContent(
                noEpisodes.copy(episodesState = EpisodesState.EMPTY), onSelectTab = {}, onRetryEpisodes = { retried++ },
                onPlay = { _, _ -> },
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText(NO_EPISODES).assertIsDisplayed()
        // Focus stays on My list; Try again is one press down.
        compose.onNodeWithText("+ My list").assertIsFocused()
        compose.onNodeWithText("+ My list").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText(TRY_AGAIN).assertIsFocused()
        compose.onNodeWithText(TRY_AGAIN).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, retried)
    }

    @Test fun providerDetailsHaveNoWrongDetailsButton() {
        val provider = details.copy(badge = null, fromTmdb = false)
        compose.setContent { ShowContent(show.copy(details = provider.copy(runtimeMinutes = null)), onSelectTab = {}, onPlay = { _, _ -> }) }
        compose.waitForIdle()

        compose.onNodeWithText("1995 · ★ 8.3 · Crime").assertExists()
        compose.onNodeWithText("A thief and a detective.").assertExists()
        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
    }

    @Test fun filmProviderDetailsHaveNoWrongDetailsButton() {
        compose.setContent { FilmContent(film.copy(details = details.copy(badge = null, fromTmdb = false)), onPlay = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("1995 · 2 h 50 min · ★ 8.3 · Crime").assertExists()
        compose.onNodeWithText(WRONG_DETAILS_LABEL).assertDoesNotExist()
    }

    @Test fun tryAgainShownOnFailure() {
        var ui by mutableStateOf(noEpisodes.copy(episodesState = EpisodesState.FAILED))
        var retried = 0
        compose.setContent {
            ShowContent(
                ui, onSelectTab = {}, onRetryEpisodes = { retried++; ui = ui.copy(episodesState = EpisodesState.LOADING) },
                onPlay = { _, _ -> },
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText(EPISODES_FAILED).assertIsDisplayed()
        // The only thing in the list area takes focus, so OK retries straight away.
        compose.onNodeWithText(TRY_AGAIN).assertIsFocused()

        compose.onNodeWithText(TRY_AGAIN).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(1, retried)
        compose.onNodeWithText(LOADING_EPISODES).assertIsDisplayed()
        // The button went away; focus is not lost with it.
        compose.onNodeWithText("+ My list").assertIsFocused()

        // A second failure brings the button back, focused.
        ui = ui.copy(episodesState = EpisodesState.FAILED)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(TRY_AGAIN).assertIsFocused()
    }

    @Test fun tryAgainReachableFromMyList() {
        // Opened with a stale fetch running; it fails while the user is on My list.
        var ui by mutableStateOf(noEpisodes.copy(episodesState = EpisodesState.LOADING))
        compose.setContent { ShowContent(ui, onSelectTab = {}, onPlay = { _, _ -> }) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("+ My list").assertIsFocused()

        ui = ui.copy(episodesState = EpisodesState.FAILED)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("+ My list").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("+ My list").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()

        compose.onNodeWithText(TRY_AGAIN).assertIsFocused()
    }
}
