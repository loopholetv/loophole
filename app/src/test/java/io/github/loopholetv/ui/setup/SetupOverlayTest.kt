package io.github.loopholetv.ui.setup

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class SetupOverlayTest {
    @get:Rule val compose = createComposeRule()

    private class FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        val codes = mutableListOf<String>()
        var cancels = 0
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) {}
        override fun enterCode(code: String) { codes += code }
        override fun cancel() {
            cancels++
            state.value = SetupState.Idle
        }
    }

    private val setup = FakeSetup()
    private val inPip = mutableStateOf(false)
    private val under = mutableListOf<Key>()
    private var toGuide = 0

    /** A focused screen underneath that records every key it gets, with the overlay over it. */
    private fun start() {
        compose.setContent {
            LoopholeTheme {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Box(
                    Modifier.fillMaxSize().testTag(UNDER).focusRequester(focus).focusable()
                        .onKeyEvent { under += it.key; false },
                )
                SetupOverlay(setup, hidden = inPip.value, onReturnToGuide = { toGuide++ }, now = { 0L })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun overlayTakesTheRemote() {
        start()
        compose.onNodeWithTag(UNDER).assertIsFocused()
        setup.state.value = SetupState.EnterCode("Bedroom", asked = false, triesLeft = null, error = null)
        compose.waitForIdle()

        compose.onNodeWithTag(SETUP_OVERLAY_TAG).assertIsFocused()
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).performKeyInput {
            pressKey(Key.Seven)
            pressKey(Key.NumPad2)
            // The pad starts on 1: Right, Down is 5.
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
            pressKey(Key.Back)
            pressKey(Key.Nine)
            pressKey(Key.Four)
        }
        compose.waitForIdle()

        assertEquals(listOf("7294"), setup.codes)
        assertEquals(0, setup.cancels)
        assertEquals("the screen underneath gets none of it", emptyList<Key>(), under)

        // Back on an empty entry cancels, and the screen underneath has its focus back.
        setup.state.value = SetupState.EnterCode("Bedroom", asked = false, triesLeft = 2, error = SetupController.wrongCode(2))
        compose.waitForIdle()
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertEquals(1, setup.cancels)
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(UNDER).assertIsFocused()
    }

    @Test
    fun hiddenInPictureInPicture() {
        inPip.value = true
        start()
        setup.state.value = SetupState.ShowingCode("4821", "Living room", expiresAtMs = 60_000)
        compose.waitForIdle()
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).assertDoesNotExist()
        compose.onNodeWithText(showCodeText("Living room")).assertDoesNotExist()

        inPip.value = false
        compose.waitForIdle()
        compose.onNodeWithText(showCodeText("Living room")).assertIsDisplayed()
    }

    @Test
    fun receivedSetupReturnsToGuideThroughTheOverlay() {
        start()
        setup.state.value = SetupState.Done(SetupController.copiedFrom("Bedroom", 0), copied = true, received = true)
        compose.waitForIdle()
        assertEquals(1, toGuide)
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, setup.cancels)
        compose.onNodeWithTag(SETUP_OVERLAY_TAG).assertDoesNotExist()
    }

    private companion object {
        const val UNDER = "under"
    }
}
