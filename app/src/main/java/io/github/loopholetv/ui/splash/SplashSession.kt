package io.github.loopholetv.ui.splash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One activity's splash: ties the [timeline], the remote keys and the playback [gate] together and
 * exposes what the screen needs as snapshot state. Call [begin] once from onCreate; then [onFrame]
 * from each splash frame, [onKey] from key dispatch, [onReady] once start-up work is done and
 * [onDispose] when the splash leaves composition. Main thread only.
 */
class SplashSession(
    private val gate: StartGate,
    private val timeline: SplashTimeline = SplashTimeline(),
) {
    private val keys = SplashKeyGate()

    /** The splash overlay is in composition. */
    var visible by mutableStateOf(false)
        private set

    /** The next screen may compose underneath (the logo has settled, or the splash was skipped). */
    var contentAllowed by mutableStateOf(true)
        private set

    /** Opacity of the overlay; read in the draw phase only. */
    var overlayAlpha by mutableFloatStateOf(0f)
        private set

    /** The screen underneath is worth drawing (the overlay is not fully opaque). */
    val drawContentUnder: Boolean get() = overlayAlpha < 1f

    fun begin(showSplash: Boolean) {
        visible = showSplash
        contentAllowed = !showSplash
        overlayAlpha = if (showSplash) 1f else 0f
        if (showSplash) gate.close() else gate.open()
    }

    fun isActive(now: Long): Boolean = visible && timeline.isActive(now)

    /** One splash frame; false once the splash has finished (it is then hidden). */
    fun onFrame(now: Long): Boolean {
        timeline.start(now)
        if (!contentAllowed && timeline.contentMayCompose(now)) contentAllowed = true
        overlayAlpha = timeline.overlayAlpha(now)
        if (timeline.isActive(now)) return true
        finish()
        return false
    }

    fun onReady(now: Long) = timeline.markReady(now)

    fun onKey(down: Boolean, keyCode: Int, repeatCount: Int, now: Long): SplashKeyResult {
        val result = keys.onKey(down, keyCode, isActive(now), repeatCount)
        if (result == SplashKeyResult.SKIP) {
            timeline.skip(now)
            contentAllowed = true
            gate.open()
        }
        return result
    }

    /** The splash left composition, finished or not: never leave playback held back. */
    fun onDispose() = gate.open()

    private fun finish() {
        visible = false
        contentAllowed = true
        overlayAlpha = 0f
        gate.open()
    }
}
