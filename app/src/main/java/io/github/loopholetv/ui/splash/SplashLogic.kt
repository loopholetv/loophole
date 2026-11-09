package io.github.loopholetv.ui.splash

import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing

enum class SplashPhase {
    /** Before the minimum show time. */
    SHOWING,

    /** Minimum time reached, start-up work not ready yet; the background keeps moving. */
    HOLDING,

    /** Fading out over the next screen. */
    EXITING,
    DONE,
}

/**
 * Timing of the start-up splash, on one monotonic clock (milliseconds). The splash shows for at
 * least [minShowMs] from its first frame ([start]), longer while start-up work is not [markReady],
 * never longer than [maxShowMs], then fades out over [fadeMs]. [skip] starts the fade at once.
 * Not thread-safe: call from the main thread only.
 */
class SplashTimeline(
    private val minShowMs: Long = 2_000,
    private val maxShowMs: Long = 5_000,
    private val fadeMs: Long = 300,
) {
    private var startAt: Long? = null
    private var readyAt: Long? = null
    private var skipAt: Long? = null

    fun start(now: Long) {
        if (startAt == null) startAt = now
    }

    fun markReady(now: Long) {
        if (readyAt == null) readyAt = now
    }

    /** True when this call brought the exit forward (false once the exit has already begun). */
    fun skip(now: Long): Boolean {
        val exit = exitStartsAt()
        if (exit != null && now >= exit) return false
        skipAt = now
        return true
    }

    /** When the fade-out begins, or null while that is not yet known. */
    fun exitStartsAt(): Long? {
        val natural = startAt?.let { s ->
            val cap = s + maxShowMs
            val ready = readyAt?.let { maxOf(it, s + minShowMs) }
            if (ready != null) minOf(ready, cap) else cap
        }
        val skip = skipAt
        return when {
            natural == null -> skip
            skip == null -> natural
            else -> minOf(natural, skip)
        }
    }

    fun phase(now: Long): SplashPhase {
        val exit = exitStartsAt()
        val s = startAt
        return when {
            exit != null && now >= exit + fadeMs -> SplashPhase.DONE
            exit != null && now >= exit -> SplashPhase.EXITING
            s != null && now >= s + minShowMs -> SplashPhase.HOLDING
            else -> SplashPhase.SHOWING
        }
    }

    /**
     * Whether the screen underneath may compose: once the logo has settled ([settleMs] after the
     * first frame), or at once after a skip, so composing it never competes with the logo animation.
     */
    fun contentMayCompose(now: Long, settleMs: Long = 1_400): Boolean {
        if (skipAt != null) return true
        val s = startAt ?: return false
        return now >= s + settleMs
    }

    fun isActive(now: Long): Boolean = phase(now) != SplashPhase.DONE

    /** Opacity of the splash over the next screen: 1 until the exit, then eased down to 0. */
    fun overlayAlpha(now: Long): Float {
        val exit = exitStartsAt() ?: return 1f
        if (now <= exit) return 1f
        val p = ((now - exit).toFloat() / fadeMs).coerceIn(0f, 1f)
        return 1f - FastOutSlowInEasing.transform(p)
    }
}

enum class SplashKeyResult {
    /** Not for the splash: dispatch as usual. */
    PASS,

    /** Swallowed; this key also ends the splash. */
    SKIP,

    /** Swallowed only. */
    CONSUME,
}

/** Keys the system handles (volume, power): never swallowed, never skip the splash. */
fun isSystemKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
    -> true
    else -> false
}

/**
 * Remote keys while the splash shows: a press skips it and is swallowed together with its
 * repeats and its release, even when those arrive after the splash has gone, so the press never
 * reaches the next screen.
 */
class SplashKeyGate {
    private val held = HashSet<Int>()

    fun onKey(down: Boolean, keyCode: Int, splashActive: Boolean, repeatCount: Int = 0): SplashKeyResult {
        if (isSystemKey(keyCode)) return SplashKeyResult.PASS
        if (!down) return if (held.remove(keyCode)) SplashKeyResult.CONSUME else SplashKeyResult.PASS
        if (keyCode in held) {
            if (repeatCount > 0) return SplashKeyResult.CONSUME
            // A fresh press of a key still marked held: its release was lost; start over.
            held.remove(keyCode)
        }
        if (!splashActive) return SplashKeyResult.PASS
        held += keyCode
        return SplashKeyResult.SKIP
    }
}

/**
 * Cold start: a new activity (no saved state), or the first activity of a new process (restored
 * after process death). A recreation in the same process (configuration change) is not one.
 */
fun shouldShowSplash(hasSavedState: Boolean, firstActivityInProcess: Boolean): Boolean =
    !hasSavedState || firstActivityInProcess

/** True on the first [take] only; one instance per process. */
class FirstInProcess {
    private var taken = false

    fun take(): Boolean {
        val first = !taken
        taken = true
        return first
    }
}
