package io.github.loopholetv.ui.player

import android.view.KeyEvent
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerKeyRouteTest {
    private val bare = PlayerKeyState(
        overlayVisible = false, bannerVisible = false, numberEntry = false, error = false,
        hasCatchup = true, catchupActive = true, archiveFailed = false,
    )
    private val left = KeyEvent.KEYCODE_DPAD_LEFT
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT

    private fun route(s: PlayerKeyState, code: Int, remote: RemoteSettings = RemoteSettings()) = routePlayerKey(s, code, remote)

    @Test fun leftRightMoveOverTheArchiveErrorCard() {
        val failed = bare.copy(archiveFailed = true, error = true)
        assertEquals(PlayerKeyRoute.Pass, route(failed, left))
        assertEquals(PlayerKeyRoute.Pass, route(failed, right))
        assertEquals(PlayerKeyRoute.RetryArchive, route(failed, KeyEvent.KEYCODE_DPAD_CENTER))
        // Without the card they seek.
        assertEquals(PlayerKeyRoute.Seek(-1), route(bare, left))
        assertEquals(PlayerKeyRoute.Seek(1), route(bare, right))
    }

    @Test fun playPauseIgnoresTheSeekKeySetting() {
        val off = RemoteSettings(mediaKeysSeek = false)
        assertEquals(PlayerKeyRoute.TogglePause, route(bare, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, off))
        assertEquals(PlayerKeyRoute.Pass, route(bare, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, off))
        assertEquals(PlayerKeyRoute.TogglePause, route(bare.copy(catchupActive = false), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, off))
    }

    private val up = KeyEvent.KEYCODE_DPAD_UP
    private val down = KeyEvent.KEYCODE_DPAD_DOWN
    private val quickMenu = PlayerKeyRoute.Action(PlayerAction.QUICK_MENU)
    private val bareStates = listOf(bare, bare.copy(catchupActive = false), bare.copy(archiveFailed = true), bare.copy(hasCatchup = false), bare.copy(bannerVisible = true))

    @Test fun downOnBareVideoOpensQuickMenu() {
        for (s in bareStates) assertEquals(quickMenu, route(s, down))
    }

    @Test fun upOnBareVideoShowsChannelRow() {
        for (s in bareStates) assertEquals(PlayerKeyRoute.ShowOverlay, route(s, up))
    }

    @Test fun upDownOnlyChangeOnBareVideo() {
        // With the overlay showing, the D-pad belongs to it.
        val overlay = bare.copy(overlayVisible = true)
        assertEquals(PlayerKeyRoute.Pass, route(overlay, up))
        assertEquals(PlayerKeyRoute.Pass, route(overlay, down))
        // A number being typed keeps opening the overlay.
        val typing = bare.copy(numberEntry = true)
        assertEquals(PlayerKeyRoute.ShowOverlay, route(typing, up))
        assertEquals(PlayerKeyRoute.ShowOverlay, route(typing, down))
    }

    @Test fun upDownKeepOpeningTheOverlayWhileTheSeekBarShowsWithoutIt() {
        val barOnly = bare.copy(overlayVisible = false, barVisible = true)
        assertEquals(PlayerKeyRoute.ShowOverlay, route(barOnly, up))
        assertEquals(PlayerKeyRoute.ShowOverlay, route(barOnly, down))
    }

    @Test fun upDownNeverZap() {
        val states = bareStates + bare.copy(overlayVisible = true) + bare.copy(numberEntry = true) + bare.copy(barVisible = true)
        for (s in states) for (code in listOf(up, down)) {
            val r = route(s, code)
            assertEquals("$code in $s", true, r == PlayerKeyRoute.Pass || r == PlayerKeyRoute.ShowOverlay || r == quickMenu)
        }
    }

    @Test fun strayLeftOnLiveDoesNothingUnlessTurnedOn() {
        val live = bare.copy(catchupActive = false)
        assertEquals(PlayerKeyRoute.Pass, route(live, left))
        assertEquals(PlayerKeyRoute.RewindLive, route(live, left, RemoteSettings(leftRewindsLive = true)))
        assertEquals(PlayerKeyRoute.Pass, route(live.copy(hasCatchup = false), left, RemoteSettings(leftRewindsLive = true)))
    }
}
