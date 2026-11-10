package io.github.loopholetv.player

import android.view.Display
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode reduced to what frame-rate matching needs; [id] is `Display.Mode.modeId`. */
data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

/** How long the content frame rate must stay unchanged before a mode switch is requested. */
const val AFR_SETTLE_MS = 1_000L

/** Relative tolerance between a refresh rate and a multiple of the content rate (0.05 %). */
private const val RATE_TOLERANCE = 0.0005f

/** Refresh rates up to this are preferred; higher ones are used only when nothing lower fits. */
private const val PREFERRED_MAX_HZ = 60.5f

/** True when [refreshHz] is an integer multiple (k >= 1) of [fps], within [RATE_TOLERANCE]. */
private fun isMultipleOf(refreshHz: Float, fps: Float): Boolean {
    val k = (refreshHz / fps).roundToInt()
    if (k < 1) return false
    val target = k * fps
    return abs(refreshHz - target) <= target * RATE_TOLERANCE
}

/**
 * The best mode for [fps] at the resolution of [current], which may be [current] itself, or null
 * when no mode at that resolution runs at a multiple of [fps]. The highest matching rate up to
 * 60.5 Hz wins; above that, the lowest matching rate.
 */
internal fun bestDisplayMode(modes: List<ModeInfo>, current: ModeInfo, fps: Float): ModeInfo? {
    if (!fps.isFinite() || fps <= 0f) return null
    val candidates = modes.filter {
        it.width == current.width && it.height == current.height && isMultipleOf(it.refreshHz, fps)
    }
    return candidates.filter { it.refreshHz <= PREFERRED_MAX_HZ }.maxByOrNull { it.refreshHz }
        ?: candidates.minByOrNull { it.refreshHz }
}

/**
 * The mode to switch to for content at [fps], keeping the current resolution, or null when the
 * frame rate is unknown, nothing matches, or [current] is already the best match.
 */
fun pickDisplayMode(modes: List<ModeInfo>, current: ModeInfo, fps: Float): ModeInfo? =
    bestDisplayMode(modes, current, fps)?.takeIf { it != current }

private fun Display.Mode.toInfo() = ModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)

/**
 * Requests display modes through the window's `preferredDisplayModeId`, which works on API 23+
 * (including TVs where `Surface.setFrameRate` cannot trigger a non-seamless HDMI switch).
 * The system drops the request when the window goes away.
 */
class DisplayModeSwitcher(private val window: Window) {

    /** Requests the mode matching [fps]; false when no change was requested. */
    fun matchFrameRate(fps: Float): Boolean {
        val display = window.decorView.display ?: return false
        val current = display.mode.toInfo()
        val target = bestDisplayMode(display.supportedModes.map { it.toInfo() }, current, fps) ?: return false
        val preferred = window.attributes.preferredDisplayModeId
        // Already requested, or already showing it with no other request pending.
        if (preferred == target.id || (target == current && preferred == 0)) return false
        setPreferred(target.id)
        return true
    }

    /** Hands the display mode back to the system (no preference). */
    fun restore() {
        if (window.attributes.preferredDisplayModeId != 0) setPreferred(0)
    }

    private fun setPreferred(id: Int) {
        window.attributes = window.attributes.apply { preferredDisplayModeId = id }
    }
}
