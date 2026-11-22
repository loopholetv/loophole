package io.github.loopholetv.record

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesRuleEntity
import java.time.Instant
import kotlin.math.abs

const val DEFAULT_PAD_BEFORE_MIN = 1
const val DEFAULT_PAD_AFTER_MIN = 5
val REMINDER_LEAD_CHOICES = listOf(0, 1, 2, 5, 10)
const val DEFAULT_REMINDER_LEAD_MIN = 1
const val POPUP_TIMEOUT_MS = 30_000L
const val WARNING_LEAD_MS = 30_000L
const val NO_GUIDE_RECORD_MS = 3_600_000L
const val EXTEND_STEP_MIN = 30
const val REFUSED_TRIES = 3
const val REFUSED_WINDOW_MS = 60_000L
const val STORAGE_RETRY_MS = 120_000L
const val UNKNOWN_BYTES_PER_HOUR = 4L * 1024 * 1024 * 1024
const val TIME_FOLLOW_WINDOW_MS = 30 * 60_000L
const val LIVE_EDGE_BEHIND_MS = 5_000L
const val DEFAULT_CONNECTIONS = 1

/** A series re-match books only showings this many days ahead, and at most [SERIES_MAX_PER_RULE] new ones per rule. */
const val SERIES_HORIZON_DAYS = 14
const val SERIES_MAX_PER_RULE = 50

/** Streams this TV may open at once on the playlist's provider: the playlist's setting, else the account's, else one. */
fun PlaylistEntity.effectiveConnections(): Int =
    connectionsAllowed?.takeIf { it > 0 } ?: accountMaxConnections?.takeIf { it > 0 } ?: DEFAULT_CONNECTIONS

/** The span a recording actually captures: the programme plus its padding. */
data class Window(val from: Instant, val until: Instant)

fun RecordingEntity.window(): Window =
    Window(start.minusSeconds(padBeforeMin * 60L), stop.plusSeconds(padAfterMin * 60L))

/** Half-open: a window ending exactly when another starts does not clash with it. */
fun overlaps(a: Window, b: Window): Boolean = a.from < b.until && b.from < a.until

/** The caller passes only programmes of the rule's channel. */
fun seriesMatches(rule: SeriesRuleEntity, p: ProgrammeEntity): Boolean =
    p.title.trim().equals(rule.title.trim(), ignoreCase = true)

fun episodeIdentity(episodeKey: String?, subTitle: String?): String? =
    episodeKey ?: subTitle?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private val REPEAT_STATES = setOf(RecordingState.RECORDING, RecordingState.DONE, RecordingState.SCHEDULED)

/** A showing whose episode is already recording, recorded or booked. Without an identity nothing is a repeat. */
fun isRepeat(candidate: ProgrammeEntity, existing: List<RecordingEntity>): Boolean {
    val id = episodeIdentity(candidate.episodeKey, candidate.subTitle) ?: return false
    return existing.any { it.state in REPEAT_STATES && episodeIdentity(it.episodeKey, it.subTitle) == id }
}

/**
 * Where a booked programme went after a guide refresh: the same title starting nearest the booked start,
 * within the window, on the booked guide channel.
 */
fun followMove(booked: RecordingEntity, programmes: List<ProgrammeEntity>): ProgrammeEntity? =
    programmes
        .filter { booked.epgChannelId == null || it.epgChannelId == booked.epgChannelId }
        .filter { it.title.trim().equals(booked.title.trim(), ignoreCase = true) }
        .map { it to abs(it.start.toEpochMilli() - booked.start.toEpochMilli()) }
        .filter { it.second <= TIME_FOLLOW_WINDOW_MS }
        .minByOrNull { it.second }
        ?.first

enum class StartTiming { ON_TIME, LATE, MISSED }

private const val ON_TIME_GRACE_S = 60L

fun startTiming(r: RecordingEntity, now: Instant): StartTiming = when {
    now < r.window().from.plusSeconds(ON_TIME_GRACE_S) -> StartTiming.ON_TIME
    now < r.stop -> StartTiming.LATE
    else -> StartTiming.MISSED
}

private const val MAX_ESTIMATE_MS = 24L * 3_600_000L

/** Rough size of the whole window (at most 24 hours of it), for the "May not fit" check. */
fun estimatedBytes(r: RecordingEntity, bytesPerHour: Long?): Long {
    val w = r.window()
    val ms = (w.until.toEpochMilli() - w.from.toEpochMilli()).coerceIn(0L, MAX_ESTIMATE_MS)
    val rate = bytesPerHour ?: UNKNOWN_BYTES_PER_HOUR
    return runCatching { Math.multiplyExact(rate, ms) / 3_600_000L }.getOrDefault(Long.MAX_VALUE)
}

/**
 * The estimate for the whole window against [free], with this channel's own earlier rate (from [done], the
 * finished recordings) when it has one. An unknown [free] never flags anything.
 */
fun mayNotFit(row: RecordingEntity, free: Long?, done: List<RecordingEntity>): Boolean {
    if (free == null) return false
    val rates = done
        .filter { it.playlistId == row.playlistId && it.channelKey == row.channelKey && it.bytes > 0 }
        .mapNotNull { r ->
            val ms = if (r.startedAt != null && r.endedAt != null) {
                r.endedAt.toEpochMilli() - r.startedAt.toEpochMilli()
            } else {
                r.window().let { it.until.toEpochMilli() - it.from.toEpochMilli() }
            }
            if (ms > 0) r.bytes.toDouble() * 3_600_000.0 / ms else null
        }
    val perHour = if (rates.isEmpty()) null else rates.average().toLong()
    return estimatedBytes(row, perHour) > free
}

/**
 * The oldest finished episodes beyond the newest [keepLast]; recordings in progress or booked are never listed.
 * An empty (0-byte) file is neither counted nor listed: it must not push out a good episode.
 */
fun keepLastDeletions(episodes: List<RecordingEntity>, keepLast: Int?): List<RecordingEntity> {
    if (keepLast == null) return emptyList()
    val finished = episodes.filter { (it.state == RecordingState.DONE || it.state == RecordingState.STOPPED) && it.bytes > 0 }
        .sortedBy { it.start }
    return finished.take((finished.size - keepLast.coerceAtLeast(0)).coerceAtLeast(0))
}

/**
 * Whether [r] has something to play: finished, still being written, or failed after writing part of the
 * programme (a failure that wrote nothing has nothing to play).
 */
fun playable(r: RecordingEntity): Boolean = r.fileName != null && when (r.state) {
    RecordingState.DONE, RecordingState.STOPPED, RecordingState.RECORDING -> true
    RecordingState.FAILED -> r.bytes > 0
    else -> false
}
