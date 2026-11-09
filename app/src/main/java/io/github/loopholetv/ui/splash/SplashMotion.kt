package io.github.loopholetv.ui.splash

import androidx.compose.animation.core.CubicBezierEasing

/** Splash animation curves as pure functions of the time since the splash's first frame (ms). */
object SplashMotion {
    private val Decelerate = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    private const val LOGO_DELAY_MS = 200L
    private const val LOGO_MS = 1_300L
    private const val HALO_DELAY_MS = 250L
    private const val HALO_IN_MS = 800L
    private const val HALO_SETTLE_MS = 1_200L
    private const val HALO_REST = 0.45f
    private const val SPIN_MS = 18_000L
    private const val BACKGROUND_WIDTH = 320

    private fun progress(t: Long, delay: Long, duration: Long): Float =
        ((t - delay).toFloat() / duration).coerceIn(0f, 1f)

    private fun logoProgress(t: Long) = Decelerate.transform(progress(t, LOGO_DELAY_MS, LOGO_MS))

    fun logoAlpha(t: Long): Float = logoProgress(t)

    fun logoScale(t: Long): Float = 0.94f + 0.06f * logoProgress(t)

    /** 0 to 1 over [HALO_IN_MS], then down to [HALO_REST], each part eased out. */
    fun haloAlpha(t: Long): Float {
        val inEnd = HALO_DELAY_MS + HALO_IN_MS
        return if (t < inEnd) {
            EaseOut.transform(progress(t, HALO_DELAY_MS, HALO_IN_MS))
        } else {
            1f - (1f - HALO_REST) * EaseOut.transform(progress(t, inEnd, HALO_SETTLE_MS))
        }
    }

    /** 0 to 1 and back, one way per [periodMs], eased in and out at both ends. */
    fun drift(t: Long, periodMs: Long): Float {
        val cycle = (t % (2 * periodMs)).toFloat() / periodMs
        val u = if (cycle <= 1f) cycle else 2f - cycle
        return EaseInOut.transform(u)
    }

    /** One clockwise turn per [SPIN_MS]. */
    fun spinDegrees(t: Long): Float = (t % SPIN_MS).toFloat() / SPIN_MS * 360f

    /** Pixel size of the small background layer for a screen of [width] x [height]. */
    fun backgroundSize(width: Float, height: Float): Pair<Int, Int> {
        val h = if (width > 0f) Math.round(BACKGROUND_WIDTH * height / width) else 0
        return BACKGROUND_WIDTH to h.coerceAtLeast(1)
    }
}
