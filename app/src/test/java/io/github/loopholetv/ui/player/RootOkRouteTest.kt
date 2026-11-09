package io.github.loopholetv.ui.player

import android.view.KeyEvent
import io.github.loopholetv.ui.common.KeyPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class RootOkRouteTest {
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER
    private val menu = KeyEvent.KEYCODE_MENU

    private data class Screen(var overlay: Boolean = false, var number: Boolean = false, var options: Boolean = false)

    /** Feeds key events through [rootOkRoute] like the screen does, collecting the actions. */
    private class Driver(val screen: Screen) {
        var press = RootPress.None
        fun send(code: Int, phase: KeyPhase, repeat: Int = 0): RootOkAction {
            val r = rootOkRoute(RootKeyState(screen.overlay, screen.number, screen.options), code, phase, repeat, press)
            press = r.press
            return r.action
        }
    }

    @Test fun freshRootPressClicksOnRelease() {
        val d = Driver(Screen())
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down))
        assertEquals(RootOkAction.Click, d.send(ok, KeyPhase.Up))
        assertEquals(RootPress.None, d.press)
    }

    @Test fun heldRootPressLongClicksOnceThenSwallows() {
        val d = Driver(Screen())
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 0))
        assertEquals(RootOkAction.LongClick, d.send(ok, KeyPhase.Down, 1))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 2))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 3))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Up))
        // the next press is fresh
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 0))
        assertEquals(RootOkAction.Click, d.send(ok, KeyPhase.Up))
    }

    @Test fun tileLongPressThenRootRepeatsAndKeyUpDoNothing() {
        val screen = Screen(overlay = true)
        val d = Driver(screen)
        assertEquals("the tile handles it", RootOkAction.PassThrough, d.send(ok, KeyPhase.Down, 0))
        assertEquals(RootOkAction.PassThrough, d.send(ok, KeyPhase.Down, 1))
        // the tile long-clicked: overlay hidden, dialog not focused yet, repeats reach the root
        screen.overlay = false
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 2))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 3))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Up))
        screen.options = true
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 4))
        assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Up))
    }

    @Test fun overlayUpRootIgnoresOkAndMenu() {
        val d = Driver(Screen(overlay = true))
        assertEquals(RootOkAction.PassThrough, d.send(ok, KeyPhase.Down))
        assertEquals("the tile clicks on release", RootOkAction.PassThrough, d.send(ok, KeyPhase.Up))
        assertEquals(RootOkAction.PassThrough, d.send(menu, KeyPhase.Down))
        assertEquals(RootOkAction.PassThrough, d.send(menu, KeyPhase.Up))
    }

    @Test fun menuActsOnlyOnFirstKeyDown() {
        val d = Driver(Screen())
        assertEquals(RootOkAction.LongClick, d.send(menu, KeyPhase.Down, 0))
        assertEquals(RootOkAction.Consume, d.send(menu, KeyPhase.Down, 1))
        assertEquals(RootOkAction.Consume, d.send(menu, KeyPhase.Down, 2))
        assertEquals(RootOkAction.Consume, d.send(menu, KeyPhase.Up))
    }

    @Test fun menuIgnoredWhileTypingANumber() {
        val d = Driver(Screen(number = true))
        assertEquals(RootOkAction.Consume, d.send(menu, KeyPhase.Down, 0))
        assertEquals(RootOkAction.Consume, d.send(menu, KeyPhase.Up))
    }

    @Test fun numberEntryCommitsOnKeyDownAndSwallowsTheRest() {
        listOf(false, true).forEach { overlay ->
            val screen = Screen(overlay = overlay, number = true)
            val d = Driver(screen)
            assertEquals(RootOkAction.Commit, d.send(ok, KeyPhase.Down, 0))
            screen.number = false // committed
            assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 1))
            assertEquals(RootOkAction.Consume, d.send(ok, KeyPhase.Down, 2))
            assertEquals("no tile click, no overlay", RootOkAction.Consume, d.send(ok, KeyPhase.Up))
            assertEquals(RootPress.None, d.press)
        }
    }

    @Test fun otherKeysPassThrough() {
        val d = Driver(Screen())
        assertEquals(RootOkAction.PassThrough, d.send(KeyEvent.KEYCODE_DPAD_UP, KeyPhase.Down))
        assertEquals(RootOkAction.PassThrough, d.send(KeyEvent.KEYCODE_5, KeyPhase.Up))
    }
}
