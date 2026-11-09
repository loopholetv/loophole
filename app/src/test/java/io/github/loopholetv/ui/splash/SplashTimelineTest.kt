package io.github.loopholetv.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimelineTest {
    private val t0 = 10_000L
    private fun timeline() = SplashTimeline(minShowMs = 2_000, maxShowMs = 5_000, fadeMs = 300)

    @Test
    fun `before the first frame the splash shows and has no exit time`() {
        val s = timeline()
        s.markReady(t0)
        assertNull(s.exitStartsAt())
        assertEquals(SplashPhase.SHOWING, s.phase(t0 + 60_000))
        assertEquals(1f, s.overlayAlpha(t0 + 60_000))
    }

    @Test
    fun `ready early - exits at the minimum show time`() {
        val s = timeline()
        s.start(t0)
        s.markReady(t0 + 100)
        assertEquals(t0 + 2_000, s.exitStartsAt())
        assertEquals(SplashPhase.SHOWING, s.phase(t0 + 1_999))
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 2_000))
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 2_299))
        assertEquals(SplashPhase.DONE, s.phase(t0 + 2_300))
    }

    @Test
    fun `not ready at the minimum - holds until ready`() {
        val s = timeline()
        s.start(t0)
        assertEquals(SplashPhase.HOLDING, s.phase(t0 + 2_500))
        s.markReady(t0 + 3_200)
        assertEquals(t0 + 3_200, s.exitStartsAt())
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 3_300))
        assertEquals(SplashPhase.DONE, s.phase(t0 + 3_500))
    }

    @Test
    fun `never ready - exits at the cap`() {
        val s = timeline()
        s.start(t0)
        assertEquals(t0 + 5_000, s.exitStartsAt())
        assertEquals(SplashPhase.HOLDING, s.phase(t0 + 4_999))
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 5_000))
        assertEquals(SplashPhase.DONE, s.phase(t0 + 5_300))
    }

    @Test
    fun `ready after the cap still exits at the cap`() {
        val s = timeline()
        s.start(t0)
        s.markReady(t0 + 7_000)
        assertEquals(t0 + 5_000, s.exitStartsAt())
    }

    @Test
    fun `start and ready only count the first call`() {
        val s = timeline()
        s.start(t0)
        s.start(t0 + 1_000)
        s.markReady(t0 + 2_500)
        s.markReady(t0 + 2_600)
        assertEquals(t0 + 2_500, s.exitStartsAt())
    }

    @Test
    fun `skip starts the exit at once, ready or not`() {
        val s = timeline()
        s.start(t0)
        assertTrue(s.skip(t0 + 400))
        assertEquals(t0 + 400, s.exitStartsAt())
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 400))
        assertEquals(SplashPhase.DONE, s.phase(t0 + 700))
    }

    @Test
    fun `skip before the first frame also exits`() {
        val s = timeline()
        assertTrue(s.skip(t0))
        assertEquals(SplashPhase.EXITING, s.phase(t0 + 10))
        assertEquals(SplashPhase.DONE, s.phase(t0 + 300))
    }

    @Test
    fun `skip during the exit or after it changes nothing`() {
        val s = timeline()
        s.start(t0)
        s.markReady(t0)
        assertFalse(s.skip(t0 + 2_100))
        assertEquals(t0 + 2_000, s.exitStartsAt())
        assertFalse(s.skip(t0 + 9_000))
        assertEquals(t0 + 2_000, s.exitStartsAt())
    }

    @Test
    fun `a second skip keeps the first exit time`() {
        val s = timeline()
        s.start(t0)
        assertTrue(s.skip(t0 + 300))
        assertFalse(s.skip(t0 + 400))
        assertEquals(t0 + 300, s.exitStartsAt())
    }

    @Test
    fun `overlay alpha is opaque until the exit, then fades to zero over the fade time`() {
        val s = timeline()
        s.start(t0)
        s.markReady(t0)
        assertEquals(1f, s.overlayAlpha(t0 + 1_999))
        assertEquals(1f, s.overlayAlpha(t0 + 2_000))
        val mid = s.overlayAlpha(t0 + 2_150)
        assertTrue(mid > 0f && mid < 1f)
        assertTrue(s.overlayAlpha(t0 + 2_250) < mid)
        assertEquals(0f, s.overlayAlpha(t0 + 2_300))
        assertEquals(0f, s.overlayAlpha(t0 + 9_000))
    }

    @Test
    fun `isActive until done`() {
        val s = timeline()
        s.start(t0)
        s.markReady(t0)
        assertTrue(s.isActive(t0 + 2_299))
        assertFalse(s.isActive(t0 + 2_300))
    }

    @Test
    fun `content may compose once the logo has settled`() {
        val s = timeline()
        assertFalse(s.contentMayCompose(t0))
        s.start(t0)
        assertFalse(s.contentMayCompose(t0 + 1_399))
        assertTrue(s.contentMayCompose(t0 + 1_400))
    }

    @Test
    fun `content may compose at once after a skip`() {
        val s = timeline()
        s.skip(t0)
        assertTrue(s.contentMayCompose(t0))
    }
}
