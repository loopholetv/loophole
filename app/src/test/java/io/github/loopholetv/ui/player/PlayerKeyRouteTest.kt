package io.github.loopholetv.ui.player

import android.view.KeyEvent
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

    @Test fun upDownOnlyOpenTheOverlay() {
        for (s in listOf(bare, bare.copy(catchupActive = false), bare.copy(archiveFailed = true), bare.copy(hasCatchup = false))) {
            assertEquals(PlayerKeyRoute.ShowOverlay, route(s, KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(PlayerKeyRoute.ShowOverlay, route(s, KeyEvent.KEYCODE_DPAD_DOWN))
        }
    }

    @Test fun strayLeftOnLiveDoesNothingUnlessTurnedOn() {
        val live = bare.copy(catchupActive = false)
        assertEquals(PlayerKeyRoute.Pass, route(live, left))
        assertEquals(PlayerKeyRoute.RewindLive, route(live, left, RemoteSettings(leftRewindsLive = true)))
        assertEquals(PlayerKeyRoute.Pass, route(live.copy(hasCatchup = false), left, RemoteSettings(leftRewindsLive = true)))
    }
}
