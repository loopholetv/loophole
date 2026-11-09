package io.github.loopholetv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldFocusGuardTest {
    private val now = 100_000L

    @Test fun backHidingTheKeyboardThenLosingFocusIsStray() {
        // Fire TV: Back hides the keyboard (the insets may or may not notice), focus jumps to the top.
        assertTrue(isStrayFieldFocusLoss(now, lastMoveAtMs = null, typing = true, imeHiddenAtMs = null))
        assertTrue(isStrayFieldFocusLoss(now, lastMoveAtMs = null, typing = false, imeHiddenAtMs = now - 200))
        assertTrue(isStrayFieldFocusLoss(now, lastMoveAtMs = now - 5_000, typing = false, imeHiddenAtMs = now - 1_000))
    }

    @Test fun aDpadMoveOutOfTheFieldIsNotStray() {
        assertFalse(isStrayFieldFocusLoss(now, lastMoveAtMs = now - 50, typing = true, imeHiddenAtMs = now - 100))
        assertFalse(isStrayFieldFocusLoss(now, lastMoveAtMs = now - 50, typing = false, imeHiddenAtMs = null))
    }

    @Test fun losingFocusLongAfterTheKeyboardClosedIsNotStray() {
        // E.g. the page or dialog closing with the field read-only.
        assertFalse(isStrayFieldFocusLoss(now, lastMoveAtMs = null, typing = false, imeHiddenAtMs = null))
        assertFalse(isStrayFieldFocusLoss(now, lastMoveAtMs = null, typing = false, imeHiddenAtMs = now - 2_000))
    }
}

class FieldGuardTest {
    private var now = 100_000L
    private val guard = FieldGuard()

    private fun focusedAfterKeyboardHid() {
        guard.onFocusChanged(true, now, typing = true)
        guard.onImeVisible(true, now)
        guard.onImeVisible(false, now)
    }

    @Test fun strayLossRightAfterTheKeyboardHidStartsARestore() {
        focusedAfterKeyboardHid()
        now += 200
        assertTrue(guard.onFocusChanged(false, now, typing = false))
        assertTrue(guard.restoring(now + 500))
        assertFalse(guard.restoring(now + FIELD_RESTORE_WINDOW_MS))
    }

    @Test fun theKeyboardHidingExplainsOneLossOnly() {
        // Once used (or focus is back on the field), a later app-driven move is not pulled back.
        focusedAfterKeyboardHid()
        now += 100
        assertTrue(guard.onFocusChanged(false, now, typing = false))
        guard.onRestoreEnded()
        guard.onFocusChanged(true, now + 50, typing = false)
        assertFalse(guard.onFocusChanged(false, now + 300, typing = false))
    }

    @Test fun regainingFocusForgetsTheHiddenKeyboard() {
        focusedAfterKeyboardHid()
        guard.onFocusChanged(false, now, typing = false) // stray: restoring
        guard.onRestoreEnded()
        guard.onFocusChanged(true, now + 10, typing = false)
        assertFalse(guard.onFocusChanged(false, now + 400, typing = false))
    }

    @Test fun imeDoneOrADpadMoveIsTheUsersOwnWayOut() {
        focusedAfterKeyboardHid()
        guard.onUserMove(now)
        assertFalse(guard.onFocusChanged(false, now + 100, typing = true))
    }

    @Test fun lossWhileAlreadyRestoringDoesNotStartAnother() {
        focusedAfterKeyboardHid()
        assertTrue(guard.onFocusChanged(false, now, typing = true))
        guard.onFocusChanged(true, now + 20, typing = false)
        // The window re-enters its first item again: the running restore handles it.
        assertFalse(guard.onFocusChanged(false, now + 40, typing = false))
        assertTrue(guard.restoring(now + 40))
    }
}
