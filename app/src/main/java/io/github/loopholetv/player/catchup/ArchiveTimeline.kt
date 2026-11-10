package io.github.loopholetv.player.catchup

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

sealed interface SeekPlan {
    /** Seek inside the loaded archive stream. */
    data class Within(val positionMs: Long) : SeekPlan

    /** Release the stream and open a new one starting at [from]. */
    data class Reopen(val from: Instant) : SeekPlan

    data object GoLive : SeekPlan
    data object Ignore : SeekPlan
}

private val IGNORE_BELOW: Duration = Duration.ofSeconds(5)
private val PROGRESSIVE_MAX_JUMP: Duration = Duration.ofMinutes(5)

/** True when [t] is within [lag] of [now], where an archive may not exist yet. */
fun isNearLive(t: Instant, now: Instant, lag: Duration): Boolean = t >= now.minus(lag)

private fun Instant.ceilToMinute(): Instant {
    val floored = truncatedTo(ChronoUnit.MINUTES)
    return if (floored == this) this else floored.plus(1, ChronoUnit.MINUTES)
}

/** Decides how to move from [current] to [target] while [loaded] plays; at most one stream is ever open. */
fun planSeek(
    current: Instant,
    target: Instant,
    loaded: PlayMode.Archive,
    now: Instant,
    earliest: Instant,
    lag: Duration,
    minuteGranular: Boolean,
): SeekPlan {
    val floor = if (minuteGranular) earliest.ceilToMinute() else earliest
    val t = if (target.isBefore(floor)) floor else target
    if (isNearLive(t, now, lag)) return SeekPlan.GoLive
    val jump = Duration.between(current, t).abs()
    if (jump < IGNORE_BELOW) return SeekPlan.Ignore
    val from = loaded.request.from
    val duration = loaded.durationMs
    val inside = duration != null && !t.isBefore(from) && t.isBefore(from.plusMillis(duration))
    if (loaded.seekable && inside && !(!loaded.hls && jump > PROGRESSIVE_MAX_JUMP)) {
        return SeekPlan.Within(Duration.between(from, t).toMillis())
    }
    return SeekPlan.Reopen(if (minuteGranular) t.truncatedTo(ChronoUnit.MINUTES) else t)
}

/** Where a rewind from the live edge lands: at least [lag] behind [now], inside the archive. */
fun rewindFromLive(now: Instant, step: Duration, lag: Duration, earliest: Instant, minuteGranular: Boolean): Instant {
    val back = if (step > lag) step else lag
    var t = now.minus(back)
    if (t.isBefore(earliest)) t = earliest
    return if (minuteGranular) t.truncatedTo(ChronoUnit.MINUTES) else t
}

fun positionOf(loaded: PlayMode.Archive, positionMs: Long): Instant = loaded.request.from.plusMillis(positionMs)

/** The clock hour containing [t] in [zone], used when the guide has no programme there. */
fun fallbackSpan(t: Instant, zone: ZoneId): ProgrammeSpan {
    val start = t.atZone(zone).truncatedTo(ChronoUnit.HOURS)
    return ProgrammeSpan(null, start.toInstant(), start.plusHours(1).toInstant())
}
