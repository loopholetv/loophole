package io.github.loopholetv.ui.setup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.ui.theme.LoopholeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
// Real text measurement, so long titles take their true width beside the pad.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupContentTest {
    @get:Rule val compose = createComposeRule()

    private val shown = mutableStateOf<SetupState>(SetupState.Idle)
    private var started = false

    /** Shows [state]; later calls in a test change the screen in place. */
    private fun show(state: SetupState, pad: CodePad = CodePad({}, {})) {
        shown.value = state
        if (!started) {
            started = true
            compose.setContent { LoopholeTheme { SetupContent(shown.value, pad, now = { 0L }) } }
        }
        compose.waitForIdle()
    }

    @Test
    fun codeScreenShowsCodeAndWarning() {
        show(SetupState.ShowingCode("4821", "Living room", expiresAtMs = 120_000))
        compose.onNodeWithText("Enter this code on Living room to copy its setup here.").assertIsDisplayed()
        compose.onNodeWithText("This replaces this TV's playlists, guide, favourites and settings.").assertIsDisplayed()
        for (digit in "4821") compose.onNodeWithText(digit.toString(), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2:00").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun codeEntryTitles() {
        show(SetupState.EnterCode("Bedroom", asked = false, triesLeft = null, error = null))
        compose.onNodeWithText("Enter the code shown on Bedroom").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun askedTvExplainsTheRequest() {
        show(SetupState.EnterCode("Bedroom", asked = true, triesLeft = null, error = null))
        compose.onNodeWithText("Bedroom wants to copy your setup. Enter the code shown on Bedroom.").assertIsDisplayed()
    }

    @Test
    fun padKeysKeepTheirWidthBesideALongTitle() {
        // The longest title wraps to its widest; the pad beside it must keep its last column.
        show(SetupState.EnterCode("Upstairs living room television", asked = true, triesLeft = null, error = null))
        val widths = (0..PAD_CANCEL).map { compose.onNodeWithTag(padKeyTag(it)).getUnclippedBoundsInRoot().width.value }
        for ((index, width) in widths.withIndex()) assertEquals("key ${padLabel(index)}", widths[0], width, 0.5f)
        assertEquals(104f, widths[0], 0.5f)
    }

    @Test
    fun wrongCodeShowsTriesLeftUnderThePad() {
        show(SetupState.EnterCode("Bedroom", asked = false, triesLeft = 2, error = "Wrong code. 2 tries left."))
        compose.onNodeWithText("Wrong code. 2 tries left.").assertIsDisplayed()
    }

    @Test
    fun workingAndDone() {
        show(SetupState.Working("Copying the setup…"))
        compose.onNodeWithText("Copying the setup…").assertIsDisplayed()
        compose.onNodeWithText("OK").assertDoesNotExist()

        // The copy ends: the spinner's text gives way to the result and its OK.
        show(SetupState.Done("Setup copied from Bedroom.", copied = true, received = true))
        compose.onNodeWithText("Copying the setup…").assertDoesNotExist()
        compose.onNodeWithText("Setup copied from Bedroom.").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun doneHasOk() {
        show(SetupState.Done("Setup copied to Bedroom.", copied = true))
        compose.onNodeWithText("Setup copied to Bedroom.").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
    }
}
