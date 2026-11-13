package io.github.loopholetv

import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.nav.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPolicyTest {
    private val playing = PlaybackState.Playing(5, StreamTags(null, null, null))
    private val buffering = PlaybackState.Buffering(5)

    @Test fun enterPipOnLeaveOnlyWhenAllowed() {
        assertTrue(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = true, state = playing, supported = true))
        assertTrue(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = true, state = buffering, supported = true))
        assertFalse(shouldEnterPipOnLeave(pipOnHome = false, playerShowing = true, state = playing, supported = true))
        assertFalse(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = false, state = playing, supported = true))
        assertFalse(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = true, state = playing, supported = false))
        assertFalse(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = true, state = PlaybackState.Idle, supported = true))
        assertFalse(
            shouldEnterPipOnLeave(
                pipOnHome = true, playerShowing = true, state = PlaybackState.Error(5, "x"), supported = true,
            ),
        )
    }

    @Test fun pipNeverEnteredOutsidePlayer() {
        // The guide preview plays with the player route not showing.
        assertFalse(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = false, state = playing, supported = true))
        assertFalse(shouldEnterPipOnLeave(pipOnHome = true, playerShowing = false, state = buffering, supported = true))
    }

    @Test fun pipClosesDestinationsAbovePlayer() {
        assertTrue(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = Routes.SETTINGS))
        assertTrue(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = Routes.PLAYLIST_FORM))
        assertFalse(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = Routes.PLAYER))
        assertFalse(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = null))
        assertFalse(shouldCloseAbovePlayerForPip(inPip = false, currentRoute = Routes.SETTINGS))
    }

    @Test fun pipClosesAboveVodPlayer() {
        assertTrue(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = Routes.SETTINGS))
        assertFalse(shouldCloseAbovePlayerForPip(inPip = true, currentRoute = Routes.VOD_PLAYER))
    }

    @Test fun sleepExpiryLeavesPipWindowOnly() {
        assertTrue(shouldCloseWindowOnSleepExpiry(inPip = true))
        assertFalse(shouldCloseWindowOnSleepExpiry(inPip = false))
    }

    @Test fun stopOnlyWhenNotChangingConfiguration() {
        assertTrue(shouldReleaseOnStop(changingConfigurations = false))
        assertFalse(shouldReleaseOnStop(changingConfigurations = true))
    }
}
