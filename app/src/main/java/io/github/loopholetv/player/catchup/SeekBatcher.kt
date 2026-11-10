package io.github.loopholetv.player.catchup

const val COMMIT_DELAY_MS = 700L

/** Size of one key press: repeats speed up the longer a key is held. [direction] is negative for back. */
fun stepMs(direction: Int, repeatCount: Int): Long {
    val magnitude = when {
        repeatCount <= 5 -> if (direction < 0) 10_000L else 30_000L
        repeatCount <= 15 -> 30_000L
        repeatCount <= 30 -> 60_000L
        else -> 300_000L
    }
    return if (direction < 0) -magnitude else magnitude
}

/** Sums seek key presses so one seek happens after the key is released. Main thread only. */
class SeekBatcher(private val nowMs: () -> Long) {
    var pending: Long? = null
        private set
    private var releasedAt: Long? = null

    /** Adds one press and returns the total pending offset in ms. */
    fun press(direction: Int, repeatCount: Int): Long {
        releasedAt = null
        val total = (pending ?: 0L) + stepMs(direction, repeatCount)
        pending = total
        return total
    }

    fun release() {
        if (pending != null) releasedAt = nowMs()
    }

    /** The pending offset once [COMMIT_DELAY_MS] has passed since the last release, then resets. */
    fun due(): Long? {
        val offset = pending ?: return null
        val released = releasedAt ?: return null
        if (nowMs() - released < COMMIT_DELAY_MS) return null
        cancel()
        return offset
    }

    fun cancel() {
        pending = null
        releasedAt = null
    }
}
