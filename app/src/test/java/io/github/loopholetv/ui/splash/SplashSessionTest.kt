package io.github.loopholetv.ui.splash

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashSessionTest {
    private val t0 = 50_000L
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER

    private fun session(show: Boolean, gate: StartGate = StartGate()) =
        SplashSession(gate, SplashTimeline(minShowMs = 2_000, maxShowMs = 5_000, fadeMs = 300)).apply { begin(show) }

    @Test
    fun `with the splash the gate closes and the content waits`() {
        val gate = StartGate()
        val s = session(show = true, gate = gate)
        assertFalse(gate.isOpen)
        assertTrue(s.visible)
        assertFalse(s.contentAllowed)
        assertFalse(s.drawContentUnder)
        assertTrue(s.isActive(t0))
    }

    @Test
    fun `without the splash the gate is open and the content shows at once`() {
        val gate = StartGate().apply { close() }
        val s = session(show = false, gate = gate)
        assertTrue(gate.isOpen)
        assertFalse(s.visible)
        assertTrue(s.contentAllowed)
        assertTrue(s.drawContentUnder)
        assertFalse(s.isActive(t0))
        assertEquals(SplashKeyResult.PASS, s.onKey(down = true, keyCode = ok, repeatCount = 0, now = t0))
    }

    @Test
    fun `frames allow the content once the logo settled, and draw it only once the fade starts`() {
        val s = session(show = true)
        assertTrue(s.onFrame(t0))
        assertFalse(s.contentAllowed)
        assertTrue(s.onFrame(t0 + 1_400))
        assertTrue(s.contentAllowed)
        assertFalse("opaque: nothing drawn underneath", s.drawContentUnder)
        s.onReady(t0 + 1_500)
        assertTrue(s.onFrame(t0 + 2_100))
        assertTrue("fading: content drawn", s.drawContentUnder)
    }

    @Test
    fun `the end of the fade hides the splash and opens the gate`() {
        val gate = StartGate()
        val s = session(show = true, gate = gate)
        s.onFrame(t0)
        s.onReady(t0)
        assertFalse(s.onFrame(t0 + 2_300))
        assertFalse(s.visible)
        assertTrue(gate.isOpen)
        assertTrue(s.drawContentUnder)
    }

    @Test
    fun `a key skips, opens the gate and allows the content at once`() {
        val gate = StartGate()
        val s = session(show = true, gate = gate)
        s.onFrame(t0)
        assertEquals(SplashKeyResult.SKIP, s.onKey(down = true, keyCode = ok, repeatCount = 0, now = t0 + 300))
        assertTrue(gate.isOpen)
        assertTrue(s.contentAllowed)
        assertEquals(SplashKeyResult.CONSUME, s.onKey(down = false, keyCode = ok, repeatCount = 0, now = t0 + 350))
    }

    @Test
    fun `leaving composition opens the gate`() {
        val gate = StartGate()
        val s = session(show = true, gate = gate)
        s.onDispose()
        assertTrue(gate.isOpen)
    }
}
