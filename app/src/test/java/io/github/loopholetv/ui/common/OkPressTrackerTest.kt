package io.github.loopholetv.ui.common

import io.github.loopholetv.ui.common.KeyPhase.Down
import io.github.loopholetv.ui.common.KeyPhase.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OkPressTrackerTest {
    private val t = OkPressTracker()
    private fun ok(phase: KeyPhase, repeat: Int = 0) = t.onKey(phase, isOk = true, isMenu = false, repeatCount = repeat)
    private fun menu(phase: KeyPhase) = t.onKey(phase, isOk = false, isMenu = true, repeatCount = 0)

    @Test fun shortPressClicksOnKeyUp() {
        assertNull(ok(Down))
        assertEquals(OkPressAction.Click, ok(Up))
    }

    @Test fun holdFiresLongOnceAndNoClick() {
        assertNull(ok(Down))
        assertEquals(OkPressAction.LongClick, ok(Down, 1))
        assertNull(ok(Down, 2))
        assertNull(ok(Down, 3))
        assertNull(ok(Up))
    }

    @Test fun menuFiresLong() {
        assertEquals(OkPressAction.LongClick, menu(Down))
        assertNull(menu(Up))
    }

    @Test fun nextPressAfterLongClicksAgain() {
        ok(Down); ok(Down, 1); ok(Up)
        assertNull(ok(Down))
        assertEquals(OkPressAction.Click, ok(Up))
    }

    @Test fun heldMenuFiresLongOnce() {
        assertEquals(OkPressAction.LongClick, t.onKey(Down, isOk = false, isMenu = true, repeatCount = 0))
        assertNull(t.onKey(Down, isOk = false, isMenu = true, repeatCount = 1))
        assertNull(t.onKey(Down, isOk = false, isMenu = true, repeatCount = 2))
        assertNull(t.onKey(Up, isOk = false, isMenu = true, repeatCount = 0))
    }

    @Test fun otherKeysAreIgnored() {
        assertNull(t.onKey(Down, isOk = false, isMenu = false, repeatCount = 0))
        assertNull(t.onKey(Up, isOk = false, isMenu = false, repeatCount = 0))
    }
}
