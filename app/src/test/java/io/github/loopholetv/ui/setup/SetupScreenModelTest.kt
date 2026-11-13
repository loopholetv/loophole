package io.github.loopholetv.ui.setup

import android.view.KeyEvent
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupScreenModelTest {
    private class FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        val codes = mutableListOf<String>()
        var cancels = 0
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) {}
        override fun enterCode(code: String) {
            codes += code
            state.value = SetupState.Working(SetupController.COPYING)
        }
        override fun cancel() {
            cancels++
            state.value = SetupState.Idle
        }
    }

    private class Harness(scope: TestScope) {
        val setup = FakeSetup()
        var toGuide = 0
        val model = SetupScreenModel(setup, onReturnToGuide = { toGuide++ })

        init {
            scope.backgroundScope.launch { model.run() }
        }

        /** A full press: down, then up. */
        fun press(keyCode: Int) {
            model.onKey(keyCode, down = true, repeatCount = 0)
            model.onKey(keyCode, down = false, repeatCount = 0)
        }
    }

    private fun TestScope.harness() = Harness(this)

    private val asking = SetupState.EnterCode("Living room", asked = false, triesLeft = null, error = null)

    @Test
    fun codeEntryAcceptsDigitsAndDpad() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = asking

        // The remote's digit keys, top row and numpad alike.
        h.press(KeyEvent.KEYCODE_7)
        h.press(KeyEvent.KEYCODE_NUMPAD_0)
        assertEquals("70", h.model.pad.digits)

        // The D-pad walks the on-screen pad: it starts on 1; Right twice is 3, Down is 6.
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        h.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("6", padLabel(h.model.pad.focused))
        h.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("706", h.model.pad.digits)

        // Delete on the pad (bottom left) takes one back.
        h.press(KeyEvent.KEYCODE_DPAD_DOWN)
        h.press(KeyEvent.KEYCODE_DPAD_DOWN)
        h.press(KeyEvent.KEYCODE_DPAD_LEFT)
        h.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Delete", padLabel(h.model.pad.focused))
        h.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("70", h.model.pad.digits)

        // Back with digits deletes one too.
        h.press(KeyEvent.KEYCODE_BACK)
        assertEquals("7", h.model.pad.digits)
        assertEquals(0, h.setup.cancels)

        // The fourth digit sends the code on its own.
        h.press(KeyEvent.KEYCODE_2)
        h.press(KeyEvent.KEYCODE_5)
        assertTrue(h.setup.codes.isEmpty())
        h.press(KeyEvent.KEYCODE_9)
        assertEquals(listOf("7259"), h.setup.codes)
    }

    @Test
    fun backOnEmptyEntryCancels() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = asking

        h.press(KeyEvent.KEYCODE_BACK)

        assertEquals(1, h.setup.cancels)
        assertNull(h.model.shown.value)
    }

    @Test
    fun cancelButtonSitsBelowThePad() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = asking
        repeat(4) { h.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("Cancel", padLabel(h.model.pad.focused))
        // Further down stays put; Up comes back to 0.
        h.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Cancel", padLabel(h.model.pad.focused))
        h.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("0", padLabel(h.model.pad.focused))
        h.press(KeyEvent.KEYCODE_DPAD_DOWN)

        h.press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(1, h.setup.cancels)
    }

    @Test
    fun wrongCodeShowsTriesLeft() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = asking
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4).forEach(h::press)
        assertEquals(listOf("1234"), h.setup.codes)

        val wrong = asking.copy(triesLeft = 2, error = SetupController.wrongCode(2))
        h.setup.state.value = wrong

        assertEquals(wrong, h.model.shown.value)
        assertEquals("Wrong code. 2 tries left.", (h.model.shown.value as SetupState.EnterCode).error)
        assertEquals("the entry starts again", "", h.model.pad.digits)
        h.press(KeyEvent.KEYCODE_5)
        assertEquals("5", h.model.pad.digits)
    }

    @Test
    fun doneReturnsToGuide() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = SetupState.Done(SetupController.copiedFrom("Living room", 0), copied = true, received = true)

        assertEquals(1, h.toGuide)
        assertTrue(h.model.shown.value is SetupState.Done)

        // The message goes on its own after a few seconds.
        advanceTimeBy(DONE_SHOWS_MS - 1)
        runCurrent()
        assertEquals(0, h.setup.cancels)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, h.setup.cancels)
        assertNull(h.model.shown.value)
        assertEquals(1, h.toGuide)
    }

    @Test
    fun sendingTvStaysWhereItIs() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = SetupState.Done(SetupController.copiedTo("Bedroom", 0), copied = true)
        h.setup.state.value = SetupState.Idle
        h.setup.state.value = SetupState.Done(SetupController.CANCELLED, copied = false)

        assertEquals(0, h.toGuide)
        // OK closes the message.
        h.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, h.setup.cancels)
    }

    @Test
    fun busyNoticeNeverCancelsTheCopy() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        val code = SetupState.ShowingCode("4821", "Bedroom", expiresAtMs = 60_000)
        h.setup.state.value = code
        h.setup.state.value = SetupState.Done(SetupController.FINISH_FIRST, copied = false)

        h.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull("OK hides the notice", h.model.shown.value)
        advanceTimeBy(DONE_SHOWS_MS + 1)
        runCurrent()
        assertEquals(0, h.setup.cancels)

        // The copy's own state comes back.
        h.setup.state.value = code
        assertEquals(code, h.model.shown.value)
        assertEquals(0, h.toGuide)
    }

    @Test
    fun codeScreenCancels() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = SetupState.ShowingCode("4821", "Bedroom", expiresAtMs = 60_000)

        // Digits and moves do nothing here.
        h.press(KeyEvent.KEYCODE_4)
        h.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, h.setup.cancels)

        h.press(KeyEvent.KEYCODE_BACK)
        assertEquals(1, h.setup.cancels)
    }

    @Test
    fun keysTakenOnlyWhileShowingAndVolumePasses() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        assertFalse(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0))

        h.setup.state.value = SetupState.ShowingCode("4821", "Bedroom", expiresAtMs = 60_000)
        assertFalse(h.model.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true, repeatCount = 0))
        // A release whose press came before the screen showed does nothing.
        assertTrue(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0))
        assertEquals(0, h.setup.cancels)
        assertTrue(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0))
        assertTrue(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0))
        assertEquals(1, h.setup.cancels)
    }

    @Test
    fun heldOkDoesNotActOnTheNextScreen() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = SetupState.Done(SetupController.CANCELLED, copied = false)
        // OK goes down on the result, which then goes on its own...
        assertTrue(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0))
        advanceTimeBy(DONE_SHOWS_MS + 1)
        runCurrent()
        assertEquals(1, h.setup.cancels)

        // ...and a code screen shows before OK comes up: the release does nothing there.
        h.setup.state.value = SetupState.ShowingCode("4821", "Bedroom", expiresAtMs = 60_000)
        assertTrue(h.model.onKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0))
        assertEquals(1, h.setup.cancels)
        assertTrue(h.model.shown.value is SetupState.ShowingCode)
    }

    @Test
    fun keyHeldAcrossAStateChangeIsForgotten() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.setup.state.value = SetupState.Working(SetupController.connecting("Bedroom"))
        h.model.onKey(KeyEvent.KEYCODE_BACK, down = true, repeatCount = 0)
        h.setup.state.value = SetupState.ShowingCode("4821", "Bedroom", expiresAtMs = 60_000)

        h.model.onKey(KeyEvent.KEYCODE_BACK, down = false, repeatCount = 0)

        assertEquals("Back pressed on the old screen does not cancel the new one", 0, h.setup.cancels)
    }

    @Test
    fun codeExpiryHint() {
        assertEquals("4:59", remainingText(299_001))
        assertEquals("0:05", remainingText(5_000))
        assertEquals("0:00", remainingText(-10))
    }
}
