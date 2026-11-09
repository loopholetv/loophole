package io.github.loopholetv.ui.splash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One activity's splash: ties the [timeline], the remote keys and the playback [gate] together and
 * exposes what the screen needs as snapshot state. Call [begin] once from onCreate; then [onFrame]
 * from each splash frame, [onKey] from key dispatch, [onContentComposed] once the next screen has
 * composed and [onDispose] when the splash leaves composition. Main thread only.
 */
class SplashSession(
    private val gate: StartGate,
    private val timeline: SplashTimeline = SplashTimeline(),
) {
    private val keys = SplashKeyGate()

    /** The splash overlay is in composition. */
    var visible by mutableStateOf(false)
        private set

    /**
     * The next screen may compose underneath: before the first splash frame (its composition then
     * folds into the launch), or once the logo has settled, or after a skip.
     */
    var contentAllowed by mutableStateOf(true)
        private set

    private var started = false
    private var contentComposed = false

    /** Opacity of the overlay; read in the draw phase only. */
    var overlayAlpha by mutableFloatStateOf(0f)
        private set

    /** The screen underneath is worth drawing (the overlay is not fully opaque). */
    val drawContentUnder: Boolean get() = overlayAlpha < 1f

    fun begin(showSplash: Boolean) {
        visible = showSplash
        contentAllowed = true
        overlayAlpha = if (showSplash) 1f else 0f
        if (showSplash) gate.close() else gate.open()
    }

    fun isActive(now: Long): Boolean = visible && timeline.isActive(now)

    /** One splash frame; false once the splash has finished (it is then hidden). */
    fun onFrame(now: Long): Boolean {
        if (!started) {
            started = true
            timeline.start(now)
            // Not composed by the first frame: wait until the logo has settled, so composing it
            // does not stall the logo animation.
            if (!contentComposed) contentAllowed = false
        }
        if (!contentAllowed && timeline.contentMayCompose(now)) contentAllowed = true
        overlayAlpha = timeline.overlayAlpha(now)
        if (timeline.isActive(now)) return true
        finish()
        return false
    }

    /** The next screen has composed: the splash may end. */
    fun onContentComposed(now: Long) {
        contentComposed = true
        timeline.markReady(now)
    }

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
