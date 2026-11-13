package io.github.loopholetv.ui.setup

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.guide.RailItem
import io.github.loopholetv.ui.guide.SideRail
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** However a copy ends, the remote comes back to the screen under the setup screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class SetupFocusBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) {}
        override fun enterCode(code: String) {}
        override fun cancel() {
            state.value = SetupState.Idle
        }
    }

    private val setup = FakeSetup()
    private val screenFocused = mutableStateOf(false)
    private lateinit var focusManager: FocusManager

    /** The guide's side menu with TVs focused (where a copy starts), the setup screens over it. */
    private fun start() {
        compose.setContent {
            LoopholeTheme {
                focusManager = LocalFocusManager.current
                val grid = remember { FocusRequester() }
                LaunchedEffect(Unit) { grid.requestFocus() }
                Box(Modifier.fillMaxSize().onFocusChanged { screenFocused.value = it.hasFocus }) {
                    Row(Modifier.fillMaxSize()) {
                        SideRail(items = RailItem.entries.filter { it != RailItem.MOVIES && it != RailItem.SERIES }, onSelect = {}, onFocused = {})
                        Box(Modifier.size(200.dp).testTag(GRID).focusRequester(grid).focusable())
                    }
                }
                SetupOverlay(setup, hidden = false, onReturnToGuide = {}, screenHasFocus = { screenFocused.value }, now = { 0L })
            }
        }
        compose.waitForIdle()
        key(Key.DirectionLeft)
        key(Key.DirectionDown)
        compose.onNodeWithContentDescription("TVs").assertIsFocused()
    }

    private fun key(k: Key) {
        compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }

    /** The sending TV's copy, start to end, with [ending] as its result. */
    private fun copy(ending: SetupState.Done, closeWithOk: Boolean, whileShowing: () -> Unit = {}) {
        setup.state.value = SetupState.Working(SetupController.connecting("Bedroom"))
        compose.waitForIdle()
        setup.state.value = SetupState.EnterCode("Bedroom", asked = false, triesLeft = null, error = null)
        compose.waitForIdle()
        compose.runOnUiThread(whileShowing)
        setup.state.value = ending
        compose.waitForIdle()
        if (closeWithOk) {
            compose.onNodeWithTag(SETUP_OVERLAY_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        } else {
            compose.mainClock.advanceTimeBy(DONE_SHOWS_MS + 100)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).assertDoesNotExist()
    }

    @Test
    fun theItemFocusedBeforeKeepsTheRemote() {
        start()
        copy(SetupState.Done(SetupController.copiedTo("Bedroom", 0), copied = true), closeWithOk = true)

        compose.onNodeWithContentDescription("TVs").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithContentDescription("Recordings").assertIsFocused()
    }

    @Test
    fun everyEndingGivesTheRemoteBack() {
        start()
        val endings = listOf(
            SetupState.Done(SetupController.CANCELLED, copied = false),
            SetupState.Done(SetupController.TIMED_OUT, copied = false),
            SetupState.Done(SetupController.unreachable("Bedroom"), copied = false),
            SetupState.Done(SetupController.FAILED, copied = false),
        )
        for ((i, ending) in endings.withIndex()) {
            copy(ending, closeWithOk = i % 2 == 0)
            compose.onNodeWithContentDescription("TVs").assertIsFocused()
        }
        key(Key.DirectionUp)
        compose.onNodeWithContentDescription("Guide").assertIsFocused()
    }

    @Test
    fun focusLostUnderTheScreensComesBackIn() {
        start()
        // The screen underneath loses its focus while the setup screens have the remote.
        copy(
            SetupState.Done(SetupController.copiedTo("Bedroom", 0), copied = true),
            closeWithOk = false,
            whileShowing = { focusManager.clearFocus(force = true) },
        )

        assertTrue("a control on the screen has focus again", screenFocused.value)
        // And the remote works on it.
        val before = focusedRailItem()
        key(Key.DirectionDown)
        assertTrue("Down moved focus", focusedRailItem() != before || before == null)
        assertTrue(screenFocused.value)
    }

    @Test
    fun nothingMovesWhileTheWindowNeverGetsFocusBack() = runTest {
        val view = View(ApplicationProvider.getApplicationContext())
        var movedIn = false
        handFocusBack(windowFocused = { false }, view = view, screenHasFocus = { false }, moveIn = { movedIn = true; true })
        assertFalse("a panel still over the screen keeps the remote", movedIn)
        assertFalse(view.hasFocus())
    }

    @Test
    fun focusMovesInOnlyWhenNothingHasIt() = runTest {
        val view = View(ApplicationProvider.getApplicationContext())
        var movedIn = 0
        handFocusBack(windowFocused = { true }, view = view, screenHasFocus = { true }, moveIn = { movedIn++; true })
        assertEquals("the focused control keeps focus", 0, movedIn)
        handFocusBack(windowFocused = { true }, view = view, screenHasFocus = { false }, moveIn = { movedIn++; true })
        assertEquals(1, movedIn)
    }

    private fun focusedRailItem(): String? =
        listOf("Search", "Guide", "TVs", "Recordings", "My list", "Settings").firstOrNull { label ->
            runCatching { compose.onNodeWithContentDescription(label).assertIsFocused() }.isSuccess
        }

    private companion object {
        const val GRID = "grid"
    }
}
