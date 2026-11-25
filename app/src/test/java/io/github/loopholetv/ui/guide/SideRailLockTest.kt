package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.ui.parental.lockAvailable
import io.github.loopholetv.ui.parental.lockRailAction
import io.github.loopholetv.ui.parental.lockRailLabel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideRailLockTest {
    @Test fun railItemLabelsAndAbsentWithoutPin() {
        assertEquals("Show locked channels", lockRailLabel(LockState.Locked))
        assertEquals("Hide locked channels", lockRailLabel(LockState.Unlocked(null)))
        assertEquals("Hide locked channels", lockRailLabel(LockState.Unlocked(123L)))
        assertNull(lockRailLabel(LockState.NoPin))

        assertFalse(RailItem.LOCK in railItemsFor(films = true, shows = true, pin = false))
        val withPin = railItemsFor(films = true, shows = true, pin = true)
        assertTrue(RailItem.LOCK in withPin)
        // Just above Settings.
        assertEquals(RailItem.SETTINGS, withPin[withPin.indexOf(RailItem.LOCK) + 1])
        assertEquals(RailItem.SETTINGS, withPin.last())
    }

    @Test fun movieAndSeriesEntriesStillFollowContent() {
        val none = railItemsFor(films = false, shows = false, pin = true)
        assertFalse(RailItem.MOVIES in none)
        assertFalse(RailItem.SERIES in none)
        assertTrue(RailItem.MOVIES in railItemsFor(films = true, shows = false, pin = false))
    }

    @Test fun railItemUnlockThenRelock() = runTest {
        val calls = mutableListOf<String>()
        val ask: suspend () -> Boolean = { calls += "ask"; true }
        val relock: suspend () -> Unit = { calls += "relock" }

        lockRailAction(LockState.Locked, ask, relock)
        lockRailAction(LockState.Unlocked(null), ask, relock)
        lockRailAction(LockState.NoPin, ask, relock)

        assertEquals(listOf("ask", "relock"), calls)
    }

    @Test fun lockRowsAreOfferedWithoutPinOrWhenUnlocked() {
        assertTrue(lockAvailable(LockState.NoPin))
        assertTrue(lockAvailable(LockState.Unlocked(null)))
        assertFalse(lockAvailable(LockState.Locked))
    }
}
