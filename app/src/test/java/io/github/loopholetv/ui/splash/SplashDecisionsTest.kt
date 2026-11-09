package io.github.loopholetv.ui.splash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashDecisionsTest {
    @Test
    fun `a new activity without saved state shows the splash`() {
        assertTrue(shouldShowSplash(hasSavedState = false, firstActivityInProcess = true))
        // Relaunch after the activity was finished (e.g. Back to exit) while the process lived on.
        assertTrue(shouldShowSplash(hasSavedState = false, firstActivityInProcess = false))
    }

    @Test
    fun `recreation in the same process (configuration change) does not`() {
        assertFalse(shouldShowSplash(hasSavedState = true, firstActivityInProcess = false))
    }

    @Test
    fun `restore into a new process (after process death) does`() {
        assertTrue(shouldShowSplash(hasSavedState = true, firstActivityInProcess = true))
    }

    @Test
    fun `the process flag is true only for the first take`() {
        val flag = FirstInProcess()
        assertTrue(flag.take())
        assertFalse(flag.take())
        assertFalse(flag.take())
    }
}
