package io.github.loopholetv.ui.splash

import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.ui.nav.StartPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `next screen is unknown until the playlist check is done`() {
        assertNull(nextScreen(hasPlaylist = null, start = null))
    }

    @Test
    fun `no playlist goes to onboarding`() {
        assertEquals(NextScreen.ONBOARDING, nextScreen(hasPlaylist = false, start = null))
    }

    @Test
    fun `a playlist goes to the guide`() {
        assertEquals(NextScreen.GUIDE, nextScreen(hasPlaylist = true, start = null))
    }

    @Test
    fun `a start channel goes to the player`() {
        assertEquals(
            NextScreen.PLAYER,
            nextScreen(hasPlaylist = true, start = StartPlayback(7, ChannelScope.All)),
        )
    }

    @Test
    fun `a start channel never applies without a playlist`() {
        assertEquals(
            NextScreen.ONBOARDING,
            nextScreen(hasPlaylist = false, start = StartPlayback(7, ChannelScope.All)),
        )
    }
}
