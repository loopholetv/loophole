package io.github.loopholetv.data.settings

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {
    @Test fun defaultsMatchTable() {
        val d = KeyBindings.DEFAULT
        assertEquals(PlayerAction.REWIND, d.player(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(GuideAction.DAY_FORWARD, d.guide(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertNull(d.player(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun arrowsOkBackMenuDigitsNotRemappable() {
        val fixed = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_UNKNOWN, -5,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) + (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)
        fixed.forEach { assertFalse("code $it", isRemappable(it)) }
        assertTrue(isRemappable(KeyEvent.KEYCODE_PROG_RED))
        assertTrue(isRemappable(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test fun encodeDecodeRoundTrip() {
        val b = KeyBindings(
            player = mapOf(KeyEvent.KEYCODE_PROG_RED to PlayerAction.FAVOURITE, 89 to PlayerAction.NONE),
            guide = mapOf(KeyEvent.KEYCODE_PROG_GREEN to GuideAction.NOW),
        )
        assertEquals(b, decodeBindings(encodeBindings(b)))
        assertEquals(KeyBindings.DEFAULT, decodeBindings(encodeBindings(KeyBindings.DEFAULT)))
        assertEquals(
            "p:89=REWIND;g:92=PAGE_UP",
            encodeBindings(KeyBindings(mapOf(89 to PlayerAction.REWIND), mapOf(92 to GuideAction.PAGE_UP))),
        )
    }

    @Test fun decodeDropsJunk() {
        val b = decodeBindings("p:19=CHANNEL_UP;p:89=WARP;x;g:abc=NOW;p:183=FAVOURITE")
        assertEquals(mapOf(183 to PlayerAction.FAVOURITE), b.player)
        assertTrue(b.guide.isEmpty())
    }

    @Test fun undecodableTextDecodesToDefault() {
        assertEquals(KeyBindings.DEFAULT, decodeBindings("junk"))
        assertEquals(KeyBindings.DEFAULT, decodeBindings("p:19=CHANNEL_UP;g:abc=NOW"))
    }

    @Test fun blankDecodesToDefault() {
        assertEquals(KeyBindings.DEFAULT, decodeBindings(null))
        assertEquals(KeyBindings.DEFAULT, decodeBindings("  "))
    }

    @Test fun noneOverridesDefault() {
        val b = decodeBindings("p:89=NONE")
        assertEquals(PlayerAction.NONE, b.player(89))
    }

    @Test fun keyNames() {
        assertEquals("Rewind", keyName(89))
        assertEquals("Red button", keyName(183))
        assertEquals("Button 999", keyName(999))
    }
}
