package io.github.loopholetv.ui.cast

import android.view.KeyEvent
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CastFollowKeysTest {
    @Test
    fun onlyBackLeaves() {
        // Back on a remote, Escape on a keyboard and B on a game controller.
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)) {
            assertEquals("key $code", FollowKey.LEAVE, followKeyFor(code, down = true))
            // Its release does nothing more (no second Back on the guide).
            assertEquals("key $code", FollowKey.CONSUME, followKeyFor(code, down = false))
        }

        val ignored = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_BUTTON_A,
        )
        for (code in ignored) {
            assertEquals("key $code down", FollowKey.CONSUME, followKeyFor(code, down = true))
            assertEquals("key $code up", FollowKey.CONSUME, followKeyFor(code, down = false))
        }
    }

    @Test
    fun volumeStaysWithTheSystem() {
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertEquals(FollowKey.PASS, followKeyFor(code, down = true))
            assertEquals(FollowKey.PASS, followKeyFor(code, down = false))
        }
    }

    @Test
    fun followLabelUsesTheLatestName() {
        val peer = CastPeer("p", "Living room", "192.168.1.20", 8000, CastStatus.CASTING, true, "c", "t", 1L, 0)
        assertEquals("From Living room", followLabel(peer, emptyList()))
        // Renamed while followed: the list's name wins.
        assertEquals("From Lounge", followLabel(peer, listOf(peer.copy(name = "Lounge"))))
    }

    @Test
    fun inviteText() {
        assertEquals("Living room is casting", inviteText("Living room"))
    }
}
