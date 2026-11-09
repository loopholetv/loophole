package io.github.loopholetv.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashMotionTest {
    private val eps = 1e-3f

    @Test
    fun `logo waits 200 ms, then fades in and grows to full over 1_3 s`() {
        assertEquals(0f, SplashMotion.logoAlpha(0), eps)
        assertEquals(0f, SplashMotion.logoAlpha(200), eps)
        assertEquals(0.94f, SplashMotion.logoScale(0), eps)
        assertEquals(1f, SplashMotion.logoAlpha(1_500), eps)
        assertEquals(1f, SplashMotion.logoScale(1_500), eps)
        assertEquals(1f, SplashMotion.logoAlpha(10_000), eps)
        // Strong deceleration: well past half way at a quarter of the time.
        assertTrue(SplashMotion.logoAlpha(200 + 325) > 0.6f)
        val a = SplashMotion.logoAlpha(600)
        assertTrue(a > 0f && a < 1f)
    }

    @Test
    fun `halo fades in to full, then eases back to 45 percent`() {
        assertEquals(0f, SplashMotion.haloAlpha(0), eps)
        assertEquals(0f, SplashMotion.haloAlpha(250), eps)
        assertEquals(1f, SplashMotion.haloAlpha(1_050), eps)
        assertEquals(0.45f, SplashMotion.haloAlpha(2_250), eps)
        assertEquals(0.45f, SplashMotion.haloAlpha(8_000), eps)
        val mid = SplashMotion.haloAlpha(1_600)
        assertTrue(mid < 1f && mid > 0.45f)
    }

    @Test
    fun `drift goes back and forth over its period with ease in-out`() {
        val p = 3_500L
        assertEquals(0f, SplashMotion.drift(0, p), eps)
        assertEquals(0.5f, SplashMotion.drift(p / 2, p), eps)
        assertEquals(1f, SplashMotion.drift(p, p), eps)
        assertEquals(0.5f, SplashMotion.drift(p + p / 2, p), eps)
        assertEquals(0f, SplashMotion.drift(2 * p, p), eps)
        // Eased: slow at the ends.
        assertTrue(SplashMotion.drift(p / 10, p) < 0.1f)
    }

    @Test
    fun `the layer turns once every 18 s`() {
        assertEquals(0f, SplashMotion.spinDegrees(0), eps)
        assertEquals(180f, SplashMotion.spinDegrees(9_000), eps)
        assertEquals(0f, SplashMotion.spinDegrees(18_000), eps)
    }
}
