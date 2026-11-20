package io.github.loopholetv.ui.vod

import io.github.loopholetv.data.vod.COUNTDOWN_SECONDS
import io.github.loopholetv.data.vod.STILL_WATCHING_TIMEOUT_MS
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.nextWindowStartMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the player must do after [AutoPlayRun.tick]. */
internal sealed interface RunStep {
    data object None : RunStep
    data class Start(val next: VodRef) : RunStep
    data object Exit : RunStep
}

/**
 * The timing of an auto-play run in the on-demand player: the Next episode button's window, the
 * countdown after an episode ends and the "Still watching?" question with its timeout. It decides;
 * the view model stops and opens streams. [nowMs] is a monotonic clock.
 */
internal class AutoPlayRun(private val nowMs: () -> Long) {
    private sealed interface Phase {
        /** An episode is opening or playing. */
        data object Playing : Phase
        /** The stream ended and stopped; the next episode is being looked up. */
        data object Deciding : Phase
        data class Counting(val next: VodRef, val label: String, val since: Long) : Phase
        data class Asking(val next: VodRef, val since: Long) : Phase
        /** The run is over: the player leaves, or will once the screen is back. */
        data object Over : Phase
    }

    private val counter = StillWatchingCounter()
    private var phase: Phase = Phase.Playing
    private var windowEnteredAt: Long? = null

    private val _ui = MutableStateFlow<AutoNextUi>(AutoNextUi.Hidden)
    val ui: StateFlow<AutoNextUi> = _ui.asStateFlow()

    /** True from an episode's end until the next one starts or the player leaves: no stream is open. */
    val between: Boolean get() = phase != Phase.Playing

    val countingDown: Boolean get() = phase is Phase.Counting
    val asking: Boolean get() = phase is Phase.Asking

    /** A new episode is starting: the button's window starts over and nothing shows. */
    fun onItemStarted() {
        phase = Phase.Playing
        windowEnteredAt = null
        _ui.value = AutoNextUi.Hidden
    }

    /** Called on every tick while an episode plays, to show or hide the Next episode button. */
    fun onPlayingTick(positionMs: Long, durationMs: Long?, hasNext: Boolean, paused: Boolean, inPip: Boolean, barShowing: Boolean) {
        if (phase != Phase.Playing) return
        val inWindow = durationMs != null && durationMs > 0 && positionMs >= nextWindowStartMs(durationMs)
        val entered = if (inWindow) windowEnteredAt ?: nowMs().also { windowEnteredAt = it } else null
        val shownFor = entered?.let { nowMs() - it } ?: 0L
        val pill = pillVisible(positionMs, durationMs, hasNext, paused, inPip, shownFor, barShowing)
        _ui.value = if (pill) AutoNextUi.Pill else AutoNextUi.Hidden
    }

    /** The playing episode ended and its stream was stopped. */
    fun onEnded() {
        phase = Phase.Deciding
        _ui.value = AutoNextUi.Hidden
    }

    /** The next episode is known: count down to it. */
    fun countDown(next: VodRef, label: String) {
        if (phase != Phase.Deciding) return
        phase = Phase.Counting(next, label, nowMs())
        _ui.value = AutoNextUi.Countdown(label, COUNTDOWN_SECONDS)
    }

    /** Called on every tick between episodes. */
    fun tick(): RunStep {
        val now = nowMs()
        when (val p = phase) {
            is Phase.Counting -> {
                val left = COUNTDOWN_SECONDS - ((now - p.since) / 1_000L).toInt()
                if (left > 0) {
                    _ui.value = AutoNextUi.Countdown(p.label, left)
                    return RunStep.None
                }
                if (counter.consumeAutoStart()) {
                    phase = Phase.Asking(p.next, now)
                    _ui.value = AutoNextUi.StillWatching
                    return RunStep.None
                }
                return claimStart(p.next)
            }
            is Phase.Asking -> if (now - p.since >= STILL_WATCHING_TIMEOUT_MS) return end()
            else -> Unit
        }
        return RunStep.None
    }

    /** Play now on the countdown; nothing once a start is under way. */
    fun playNow(): RunStep {
        val p = phase as? Phase.Counting ?: return RunStep.None
        counter.onManualNext()
        return claimStart(p.next)
    }

    fun keepWatching(): RunStep {
        val p = phase as? Phase.Asking ?: return RunStep.None
        counter.reset()
        return claimStart(p.next)
    }

    /** The Next episode button: only while it shows. */
    fun nextPressed(next: VodRef?): RunStep {
        if (phase != Phase.Playing || _ui.value != AutoNextUi.Pill || next == null) return RunStep.None
        counter.onManualNext()
        return claimStart(next)
    }

    fun onUserKey() = counter.onUserKey()

    /** Leaving the app or the player ends the run's count. */
    fun onLeft() = counter.reset()

    /** The run ends here: whatever shows goes away. */
    fun end(): RunStep {
        phase = Phase.Over
        _ui.value = AutoNextUi.Hidden
        return RunStep.Exit
    }

    /** Only one start per episode end: the phase moves on before the caller opens anything. */
    private fun claimStart(next: VodRef): RunStep {
        onItemStarted()
        return RunStep.Start(next)
    }
}
