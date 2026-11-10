package io.github.loopholetv.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Durations offered by the sleep timer, in minutes. */
val SLEEP_PRESETS_MIN = listOf(15, 30, 45, 60, 90, 120)

/** The timer reports [SleepState.Warning] during this many final seconds. */
const val SLEEP_WARNING_SECONDS = 60

/** "15 min", "1 hour", "1 hour 30 min", "2 hours". */
fun sleepPresetText(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> if (h == 1) "1 hour" else "$h hours"
        else -> "${if (h == 1) "1 hour" else "$h hours"} $m min"
    }
}

/** "<minutes, rounded up> min left", never below "1 min left". */
fun sleepRemainingText(remainingMs: Long): String {
    val min = ((remainingMs + 59_999) / 60_000).coerceAtLeast(1)
    return "$min min left"
}

sealed interface SleepState {
    data object Off : SleepState
    data class Running(val minutes: Int, val remainingMs: Long) : SleepState
    data class Warning(val minutes: Int, val secondsLeft: Int) : SleepState
}

/**
 * Counts down a preset duration and calls [onExpire] when it reaches zero. Remaining time is
 * recomputed from [elapsed] on every tick, so a late tick cannot stretch the countdown.
 *
 * The tick loop and [onExpire] run on [scope]'s dispatcher; pass a main-thread scope when
 * [onExpire] touches the player. Call [start], [cancel] and [keepWatching] from that same thread.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onExpire: () -> Unit,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val tickMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    private var job: Job? = null

    /** Starts (or restarts) the countdown with one of [SLEEP_PRESETS_MIN]. */
    fun start(minutes: Int) {
        require(minutes in SLEEP_PRESETS_MIN) { "Unsupported sleep preset: $minutes" }
        job?.cancel()
        val totalMs = minutes * 60_000L
        val startedAt = elapsed()
        _state.value = SleepState.Running(minutes, totalMs)
        job = scope.launch {
            while (true) {
                delay(tickMs)
                val remaining = totalMs - (elapsed() - startedAt)
                if (remaining <= 0) {
                    onExpire()
                    _expired.tryEmit(Unit)
                    _state.value = SleepState.Off
                    return@launch
                }
                _state.value = if (remaining <= SLEEP_WARNING_SECONDS * 1_000L) {
                    SleepState.Warning(minutes, ((remaining + 999) / 1_000).toInt())
                } else {
                    SleepState.Running(minutes, remaining)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepState.Off
    }

    /** During the warning, restarts with the same duration; otherwise does nothing. */
    fun keepWatching() {
        val s = _state.value
        if (s is SleepState.Warning) start(s.minutes)
    }
}
