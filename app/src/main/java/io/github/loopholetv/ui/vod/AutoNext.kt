package io.github.loopholetv.ui.vod

import io.github.loopholetv.data.vod.NEXT_PILL_HIDE_MS
import io.github.loopholetv.data.vod.STILL_WATCHING_AFTER
import io.github.loopholetv.data.vod.nextWindowStartMs

/** What the player shows for auto-play at a given moment. */
sealed interface AutoNextUi {
    data object Hidden : AutoNextUi
    data object Pill : AutoNextUi
    data class Countdown(val label: String, val secondsLeft: Int) : AutoNextUi
    data object StillWatching : AutoNextUi
}

/**
 * Whether the "Next episode" button shows: only while playing in the episode's closing window, and
 * only for [NEXT_PILL_HIDE_MS] unless the control bar is up.
 */
fun pillVisible(
    positionMs: Long,
    durationMs: Long?,
    hasNext: Boolean,
    paused: Boolean,
    inPip: Boolean,
    shownForMs: Long,
    barShowing: Boolean,
): Boolean {
    if (!hasNext || paused || inPip) return false
    if (durationMs == null || durationMs <= 0) return false
    if (positionMs < nextWindowStartMs(durationMs)) return false
    return shownForMs < NEXT_PILL_HIDE_MS || barShowing
}

/** Counts episodes auto-started without any viewer input, to stop unattended play. */
class StillWatchingCounter(private val threshold: Int = STILL_WATCHING_AFTER) {
    private var count = 0

    fun onUserKey() = reset()

    fun onManualNext() = reset()

    fun reset() {
        count = 0
    }

    /**
     * Returns true when the caller must ask "Still watching?" instead of auto-starting; otherwise
     * counts this auto-start and returns false. Call exactly once per episode end: it changes state.
     */
    fun consumeAutoStart(): Boolean {
        if (count + 1 >= threshold) return true
        count++
        return false
    }
}

fun countdownLabel(season: Int?, episode: Int?, name: String): String =
    if (season != null && episode != null) "Next: S$season E$episode · $name" else "Next: $name"
