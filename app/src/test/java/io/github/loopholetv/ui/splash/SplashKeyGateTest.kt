package io.github.loopholetv.ui.splash

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashKeyGateTest {
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER
    private val back = KeyEvent.KEYCODE_BACK

    @Test
    fun `keys pass through when the splash is not active`() {
        val gate = SplashKeyGate()
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = true, keyCode = ok, splashActive = false))
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = false, keyCode = ok, splashActive = false))
    }

    @Test
    fun `a key down while active skips and is consumed`() {
        val gate = SplashKeyGate()
        assertEquals(SplashKeyResult.SKIP, gate.onKey(down = true, keyCode = ok, splashActive = true))
    }

    @Test
    fun `the up of a consumed key is consumed even after the splash ended`() {
        val gate = SplashKeyGate()
        gate.onKey(down = true, keyCode = back, splashActive = true)
        assertEquals(SplashKeyResult.CONSUME, gate.onKey(down = false, keyCode = back, splashActive = false))
        // Only once: the next press is a normal key again.
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = true, keyCode = back, splashActive = false))
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = false, keyCode = back, splashActive = false))
    }

    @Test
    fun `repeats of a held consumed key are consumed after the splash ended`() {
        val gate = SplashKeyGate()
        gate.onKey(down = true, keyCode = ok, splashActive = true)
        assertEquals(SplashKeyResult.CONSUME, gate.onKey(down = true, keyCode = ok, splashActive = false, repeatCount = 1))
        assertEquals(SplashKeyResult.CONSUME, gate.onKey(down = false, keyCode = ok, splashActive = false))
    }

    @Test
    fun `further downs during the splash are consumed without another skip`() {
        val gate = SplashKeyGate()
        gate.onKey(down = true, keyCode = ok, splashActive = true)
        assertEquals(SplashKeyResult.CONSUME, gate.onKey(down = true, keyCode = ok, splashActive = true, repeatCount = 1))
        assertEquals(SplashKeyResult.SKIP, gate.onKey(down = true, keyCode = back, splashActive = true))
    }

    @Test
    fun `a lost up does not swallow the next fresh press after the splash`() {
        val gate = SplashKeyGate()
        gate.onKey(down = true, keyCode = ok, splashActive = true)
        // The up never arrives; later a new press (repeat count 0) comes after the splash.
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = true, keyCode = ok, splashActive = false, repeatCount = 0))
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = false, keyCode = ok, splashActive = false))
    }

    @Test
    fun `an up without a consumed down passes`() {
        val gate = SplashKeyGate()
        assertEquals(SplashKeyResult.PASS, gate.onKey(down = false, keyCode = ok, splashActive = true))
    }

    @Test
    fun `volume and power keys pass through and do not skip`() {
        val gate = SplashKeyGate()
        for (code in listOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )) {
            assertEquals(SplashKeyResult.PASS, gate.onKey(down = true, keyCode = code, splashActive = true))
            assertEquals(SplashKeyResult.PASS, gate.onKey(down = false, keyCode = code, splashActive = true))
        }
    }

    @Test
    fun `isSystemKey covers only the pass-through keys`() {
        assertTrue(isSystemKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(isSystemKey(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(isSystemKey(KeyEvent.KEYCODE_BACK))
        assertFalse(isSystemKey(KeyEvent.KEYCODE_MENU))
    }
}
