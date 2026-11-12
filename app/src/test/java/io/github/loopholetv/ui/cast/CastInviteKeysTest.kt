package io.github.loopholetv.ui.cast

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastInviteKeysTest {
    private class Calls {
        var watched = 0
        var ignored = 0
        val keys = InviteKeys(onWatch = { watched++ }, onIgnore = { ignored++ })
    }

    private fun InviteKeys.down(code: Int, showing: Boolean = true, repeat: Int = 0) =
        onKey(code, down = true, repeatCount = repeat, showing = showing)

    private fun InviteKeys.up(code: Int, showing: Boolean = true) =
        onKey(code, down = false, repeatCount = 0, showing = showing)

    private fun InviteKeys.press(code: Int, showing: Boolean = true): Boolean {
        val down = down(code, showing)
        val up = up(code, showing)
        assertEquals("key-up follows key-down for $code", down, up)
        return down
    }

    @Test
    fun okOnWatchJoins() {
        val c = Calls()
        assertEquals("every invitation starts on Watch", 0, c.keys.selected)

        assertTrue(c.keys.press(KeyEvent.KEYCODE_DPAD_CENTER))

        assertEquals(1, c.watched)
        assertEquals(0, c.ignored)
    }

    @Test
    fun gamepadAPressesWatch() {
        val c = Calls()
        assertTrue(c.keys.press(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1, c.watched)
    }

    @Test
    fun rightThenOkIgnores() {
        val c = Calls()
        c.keys.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, c.keys.selected)
        // Right again stays on Ignore; Left goes back to Watch.
        c.keys.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, c.keys.selected)

        c.keys.press(KeyEvent.KEYCODE_ENTER)

        assertEquals(1, c.ignored)
        assertEquals(0, c.watched)

        c.keys.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, c.keys.selected)
    }

    @Test
    fun backIgnores() {
        // Escape (keyboard) and B (game controller) are Back too.
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)) {
            val c = Calls()
            assertTrue("key $code", c.keys.press(code))
            assertEquals("key $code", 1, c.ignored)
            assertEquals(0, c.watched)
        }
    }

    @Test
    fun otherKeysAreTakenButVolumeIsNot() {
        val c = Calls()
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_MENU,
        )) {
            assertTrue("key $code", c.keys.press(code))
        }
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertFalse("key $code", c.keys.press(code))
        }
        assertEquals(0, c.watched + c.ignored)
        assertEquals(0, c.keys.selected)
    }

    @Test
    fun withoutTheCardKeysGoToTheScreen() {
        val c = Calls()
        assertFalse(c.keys.press(KeyEvent.KEYCODE_DPAD_CENTER, showing = false))
        assertFalse(c.keys.press(KeyEvent.KEYCODE_BACK, showing = false))
        assertEquals(0, c.watched + c.ignored)
    }

    @Test
    fun releaseAfterTheCardWentIsStillTaken() {
        val c = Calls()
        // OK pressed on Watch: the card goes before the key is released.
        assertTrue(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(c.keys.up(KeyEvent.KEYCODE_DPAD_CENTER, showing = false))
    }

    @Test
    fun heldOkBeforeCardDoesNotPressWatch() {
        val c = Calls()
        // A long OK press in the player, still held when the card comes.
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER, showing = false))
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1, showing = true))
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 2, showing = true))
        assertFalse("its release belongs to the player", c.keys.up(KeyEvent.KEYCODE_DPAD_CENTER))

        assertEquals(0, c.watched)
        // The next press is the card's.
        assertTrue(c.keys.press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, c.watched)
    }

    @Test
    fun seekReleaseAcrossCardStillReachesPlayer() {
        val c = Calls()
        // A seek key held in the player: first press before the card, repeats and release after.
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_RIGHT, showing = false))
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 5, showing = true))
        assertFalse(c.keys.up(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("the card's highlight didn't move", 0, c.keys.selected)
    }

    @Test
    fun repeatsOfAPressTheCardTookStayWithIt() {
        val c = Calls()
        assertTrue(c.keys.down(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(c.keys.down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1))
        // The card went meanwhile: the rest of that press is still the card's.
        assertTrue(c.keys.down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 2, showing = false))
        assertTrue(c.keys.up(KeyEvent.KEYCODE_DPAD_DOWN, showing = false))
    }

    @Test
    fun aLostReleaseDoesNotSwallowALaterOne() {
        val c = Calls()
        // Taken while the card showed, but its release never came (the app was paused).
        assertTrue(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER))
        // Later, with no card, a new press goes to the screen, and so does its release.
        assertFalse(c.keys.down(KeyEvent.KEYCODE_DPAD_CENTER, showing = false))
        assertFalse(c.keys.up(KeyEvent.KEYCODE_DPAD_CENTER, showing = false))
    }

    @Test
    fun aNewInvitationStartsOnWatch() {
        val c = Calls()
        c.keys.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        c.keys.reset()
        assertEquals(0, c.keys.selected)
    }
}
