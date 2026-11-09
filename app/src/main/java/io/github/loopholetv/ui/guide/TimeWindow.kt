package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ProgrammeEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The slice of time the guide grid shows: [start, end). */
data class TimeWindow(val start: Instant, val end: Instant) {
    val durationMillis: Long get() = end.toEpochMilli() - start.toEpochMilli()

    fun overlaps(p: ProgrammeEntity): Boolean = p.stop > start && p.start < end
    fun contains(t: Instant): Boolean = t >= start && t < end
}

val WINDOW_LENGTH: Duration = Duration.ofHours(2)
val WINDOW_STEP: Duration = Duration.ofMinutes(30)

/** [t] floored to the previous half hour in [zone] (local wall-clock, so +10:30 zones work). */
fun floorToHalfHour(t: Instant, zone: ZoneId): Instant {
    val local = t.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
    return local.withMinute(if (local.minute >= 30) 30 else 0).toInstant()
}

/** Starts at the half hour before [now] and lasts [WINDOW_LENGTH]. */
fun initialWindow(now: Instant, zone: ZoneId): TimeWindow {
    val start = floorToHalfHour(now, zone)
    return TimeWindow(start, start + WINDOW_LENGTH)
}

/** [t] rounded up to the next half hour in [zone] (unchanged when already on one). */
fun ceilToHalfHour(t: Instant, zone: ZoneId): Instant {
    val floor = floorToHalfHour(t, zone)
    return if (floor == t) t else floorToHalfHour(floor + WINDOW_STEP, zone)
}

/**
 * [w] moved by [steps] × 30 min, clamped so it never starts before the "now" window (no past
 * navigation) and ends no later than [latestEnd] rounded up to the half hour.
 * Every reachable start is on :00/:30, so shifting back always lands on the "now" window again.
 */
fun shiftWindow(w: TimeWindow, steps: Int, now: Instant, zone: ZoneId, latestEnd: Instant?): TimeWindow {
    val nowStart = initialWindow(now, zone).start
    val latestStart = latestEnd
        ?.let { floorToHalfHour(ceilToHalfHour(it, zone) - WINDOW_LENGTH, zone) }
        ?.let { maxOf(nowStart, it) }
        ?: nowStart
    val start = w.start.plus(WINDOW_STEP.multipliedBy(steps.toLong())).coerceIn(nowStart, latestStart)
    return TimeWindow(start, start + WINDOW_LENGTH)
}

/** Share of [w]'s width that [p] covers once clipped to the window (0..1). */
fun blockWidthFraction(p: ProgrammeEntity, w: TimeWindow): Float {
    val from = maxOf(p.start, w.start).toEpochMilli()
    val to = minOf(p.stop, w.end).toEpochMilli()
    return ((to - from).toFloat() / w.durationMillis).coerceIn(0f, 1f)
}

/** Where [p] starts within [w], as a share of the width (0 when it began before the window). */
fun blockStartFraction(p: ProgrammeEntity, w: TimeWindow): Float =
    fractionOf(p.start, w)

/** Position of [t] in [w] as a share of the width, clamped to 0..1. */
fun fractionOf(t: Instant, w: TimeWindow): Float =
    ((t.toEpochMilli() - w.start.toEpochMilli()).toFloat() / w.durationMillis).coerceIn(0f, 1f)

private val QUERY_BUCKET: Duration = Duration.ofHours(3)

/**
 * The time range to load for [w]: a 9 h range aligned to 3 h buckets that holds the window with at
 * least 1 h to spare, so moving the window by 30 min rarely needs a new query.
 */
fun queryRange(w: TimeWindow): TimeWindow {
    val bucket = QUERY_BUCKET.toMillis()
    val start = Math.floorDiv(w.start.toEpochMilli() - bucket, bucket) * bucket
    return TimeWindow(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 3 * bucket))
}
