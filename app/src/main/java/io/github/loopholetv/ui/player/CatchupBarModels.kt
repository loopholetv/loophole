package io.github.loopholetv.ui.player

import io.github.loopholetv.player.catchup.CatchupUi
import io.github.loopholetv.ui.guide.GuideFormat
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** The buttons under the catch-up bar, in focus order. */
enum class TransportButton(val label: String) {
    PREVIOUS("Previous programme"),
    REWIND("Rewind"),
    PLAY_PAUSE("Play/Pause"),
    FAST_FORWARD("Fast-forward"),
    NEXT("Next programme"),
    LIVE("Live"),
}

/** Everything the catch-up bar draws, already formatted. */
data class CatchupBarUi(
    val title: String,
    val times: String,
    val elapsed: String,
    val total: String,
    val fraction: Float,
    /** Where the live edge sits in the programme while it is still airing; null once it has ended. */
    val liveFraction: Float?,
    /** Where a pending seek will land; null when no seek is waiting. */
    val pendingFraction: Float?,
    /** The pending seek relative to the current position, e.g. "−0:30" or "+5:00". */
    val pendingLabel: String?,
    val paused: Boolean,
    val behindLive: Boolean,
)

/** Playback further back than this from the live edge counts as behind live. */
private val LIVE_TOLERANCE: Duration = Duration.ofSeconds(5)

fun catchupBarUi(c: CatchupUi, format: GuideFormat): CatchupBarUi {
    val p = c.programme
    val totalMs = Duration.between(p.start, p.stop).toMillis().coerceAtLeast(1)
    fun fractionAt(t: Instant) = (Duration.between(p.start, t).toMillis().toFloat() / totalMs).coerceIn(0f, 1f)

    val pending = c.pendingTarget
    return CatchupBarUi(
        title = p.title ?: "No information",
        times = "${format.time(p.start)}–${format.time(p.stop)}",
        elapsed = durationText(Duration.between(p.start, c.position)),
        total = durationText(Duration.between(p.start, p.stop)),
        fraction = fractionAt(c.position),
        liveFraction = if (c.now < p.stop) fractionAt(c.now) else null,
        pendingFraction = pending?.let(::fractionAt),
        pendingLabel = pending?.let { signedDurationText(Duration.between(c.position, it)) },
        paused = c.paused,
        behindLive = c.live || Duration.between(c.position, c.now) > LIVE_TOLERANCE,
    )
}

/** "12:34" under an hour, "1:02:03" from an hour up; negative durations show as zero. */
fun durationText(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun signedDurationText(d: Duration): String {
    val sign = if (d.isNegative) "−" else "+"
    return sign + durationText(Duration.ofSeconds(abs(d.seconds)))
}
