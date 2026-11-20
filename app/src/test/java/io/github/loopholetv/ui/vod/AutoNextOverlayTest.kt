package io.github.loopholetv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Config(qualifiers = "w1280dp-h720dp")
@RunWith(AndroidJUnit4::class)
class AutoNextOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Before fun useRealMain() = Dispatchers.resetMain()

    private val calls = mutableListOf<String>()

    private fun show(ui: AutoNextUi) = compose.setContent {
        AutoNextOverlay(
            ui,
            onPlayNow = { calls += "playNow" },
            onCancel = { calls += "cancel" },
            onKeepWatching = { calls += "keepWatching" },
            onStop = { calls += "stop" },
        )
    }

    private fun okOn(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(text).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun pillShowsAndOkPressesNext() {
        show(AutoNextUi.Pill)
        compose.waitForIdle()

        // The pill is a label, not a control: OK on the player is what presses it.
        compose.onNodeWithText("Next episode ›").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        assertTrue(okGoesToNext(AutoNextUi.Pill, paused = false))
    }

    @Test fun okResumesWhenPausedEvenInWindow() {
        // Paused in the closing window: OK resumes, whatever the pill showed a moment ago.
        assertFalse(okGoesToNext(AutoNextUi.Pill, paused = true))
        assertFalse(okGoesToNext(AutoNextUi.Hidden, paused = false))
        assertFalse(okGoesToNext(AutoNextUi.Countdown("Next: E2", 5), paused = false))
        assertFalse(okGoesToNext(AutoNextUi.StillWatching, paused = false))
    }

    @Test fun menuOpensOptionsOnlyWithoutACardOrError() {
        assertTrue(optionsMayOpen(hasError = false, card = AutoNextUi.Pill.isCard()))
        assertTrue(optionsMayOpen(hasError = false, card = AutoNextUi.Hidden.isCard()))
        assertFalse(optionsMayOpen(hasError = false, card = AutoNextUi.Countdown("Next: E2", 5).isCard()))
        assertFalse(optionsMayOpen(hasError = false, card = AutoNextUi.StillWatching.isCard()))
        assertFalse(optionsMayOpen(hasError = true, card = false))
    }

    @Test fun homeOpensPipOnlyWithoutACard() {
        assertTrue(pipMayOpen(AutoNextUi.Pill.isCard()))
        assertTrue(pipMayOpen(AutoNextUi.Hidden.isCard()))
        assertFalse(pipMayOpen(AutoNextUi.Countdown("Next: E2", 5).isCard()))
        assertFalse(pipMayOpen(AutoNextUi.StillWatching.isCard()))
    }

    @Test fun countdownFocusesPlayNow() {
        show(AutoNextUi.Countdown("Next: S2 E5 · The Return", 7))
        compose.waitForIdle()

        compose.onNodeWithText("Next: S2 E5 · The Return").assertIsDisplayed()
        compose.onNodeWithText("Starting in 7 s").assertIsDisplayed()
        compose.onNodeWithText("Play now").assertIsFocused()

        okOn("Cancel")
        okOn("Play now")
        assertEquals(listOf("cancel", "playNow"), calls)
    }

    @Test fun countdownKeepsFocusOnPlayNowAsItCounts() {
        var ui by mutableStateOf<AutoNextUi>(AutoNextUi.Countdown("Next: E2", 10))
        compose.setContent { AutoNextOverlay(ui, {}, {}, {}, {}) }
        compose.waitForIdle()

        ui = AutoNextUi.Countdown("Next: E2", 9)
        compose.waitForIdle()

        compose.onNodeWithText("Starting in 9 s").assertIsDisplayed()
        compose.onNodeWithText("Play now").assertIsFocused()
    }

    @Test fun stillWatchingFocusesKeepWatching() {
        var ui by mutableStateOf<AutoNextUi>(AutoNextUi.Countdown("Next: E2", 1))
        compose.setContent {
            AutoNextOverlay(ui, {}, {}, onKeepWatching = { calls += "keepWatching" }, onStop = { calls += "stop" })
        }
        compose.waitForIdle()

        // The question replaces the countdown when it reaches 0.
        ui = AutoNextUi.StillWatching
        compose.waitForIdle()

        compose.onNodeWithText("Still watching?").assertIsDisplayed()
        compose.onNodeWithText("Keep watching").assertIsFocused()
        okOn("Stop")
        okOn("Keep watching")
        assertEquals(listOf("stop", "keepWatching"), calls)
    }

    @Test fun hiddenShowsNothing() {
        show(AutoNextUi.Hidden)
        compose.waitForIdle()

        compose.onNodeWithText("Next episode ›").assertDoesNotExist()
        compose.onNodeWithText("Play now").assertDoesNotExist()
        compose.onNodeWithText("Still watching?").assertDoesNotExist()
    }
}
