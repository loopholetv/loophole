package io.github.loopholetv.ui.recordings

import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.SeriesRuleEntity
import io.github.loopholetv.record.effectiveConnections
import io.github.loopholetv.record.overlaps
import io.github.loopholetv.record.peakConcurrency
import io.github.loopholetv.record.playable
import io.github.loopholetv.record.providerHost
import io.github.loopholetv.record.window
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val RECORDINGS_TITLE = "Recordings"
const val OTHER_TVS_TITLE = "Other TVs"
const val EMPTY_RECORDINGS = "No recordings yet. Open a programme in the guide to record it."
const val SET_UP_STORAGE = "Set up storage"
const val DELETE_FAILED_PROMPT = "Couldn't delete the file. Remove it from the list?"
const val REMOVE_FROM_LIST = "Remove from list"

const val STATE_RECORDING = "Recording"
const val STATE_SCHEDULED = "Scheduled"
const val STATE_DONE = "Done"
const val STATE_DONE_GAPS = "Done, with gaps"
const val STATE_STARTED_LATE = "Started late"
const val STATE_STOPPED = "Stopped"
const val STATE_STOPPED_FULL = "Stopped: storage full"
const val STATE_FAILED_BUSY = "Failed: provider busy"
const val STATE_FAILED_STORAGE = "Failed: storage unreachable"
const val STATE_FAILED = "Failed"
const val STATE_SKIPPED = "Skipped"
const val STATE_MISSED_CLASH = "Missed: clash"
const val STATE_MISSED_OFF = "Missed: TV was off"
const val NOTE_NOT_IN_GUIDE = "Not in guide"
const val NOTE_MAY_NOT_FIT = "May not fit"

fun noteClashesWith(title: String) = "Clashes with $title"

fun noteFrom(playlist: String) = "from $playlist"

enum class SectionKind(val title: String) {
    CLASHES("Clashes"),
    RECORDING_NOW("Recording now"),
    REMINDERS("Reminders"),
    SCHEDULED("Scheduled"),
    DONE("Done"),
    NOT_COMPLETED("Not completed"),
}

/**
 * One line of the screen: a recording (collapsed to its next showing when it belongs to a series) or a
 * reminder. [recording] or [reminder] is set, and [series] when the recording belongs to a rule that exists.
 */
data class RecordingRow(
    val key: String,
    val title: String,
    val channel: String,
    val whenText: String,
    val stateText: String,
    val notes: List<String> = emptyList(),
    val sizeText: String? = null,
    val recording: RecordingEntity? = null,
    val reminder: ReminderEntity? = null,
    val series: SeriesRuleEntity? = null,
    /** Failed or missed: shown in the error colour. */
    val problem: Boolean = false,
)

data class RecordingsSection(val kind: SectionKind, val rows: List<RecordingRow>) {
    val title: String get() = kind.title
}

/** What the hold-OK menu of a row can offer. */
enum class RowAction(val label: String) {
    PLAY("Play"),
    DELETE("Delete"),
    RETRY("Retry"),
    RECORD_FROM_CATCH_UP("Record from catch-up"),
    EDIT_PADDING("Edit padding"),
    CANCEL_RECORDING("Cancel recording"),
    CANCEL_SERIES("Cancel series"),
    KEEP_LAST("Keep the last…"),
    CHANGE_SOURCE("Change source"),
    CANCEL_REMINDER("Cancel reminder"),
    CANCEL_SERIES_REMINDER("Cancel series reminder"),
}

fun stateText(r: RecordingEntity): String = when (r.state) {
    RecordingState.RECORDING -> STATE_RECORDING
    RecordingState.SCHEDULED -> STATE_SCHEDULED
    RecordingState.DONE -> when (r.reason) {
        RecordingReason.GAPS -> STATE_DONE_GAPS
        RecordingReason.STARTED_LATE -> STATE_STARTED_LATE
        else -> STATE_DONE
    }
    RecordingState.STOPPED -> if (r.reason == RecordingReason.STORAGE_FULL) STATE_STOPPED_FULL else STATE_STOPPED
    RecordingState.FAILED -> when (r.reason) {
        RecordingReason.PROVIDER_BUSY -> STATE_FAILED_BUSY
        RecordingReason.STORAGE_UNREACHABLE -> STATE_FAILED_STORAGE
        else -> STATE_FAILED
    }
    RecordingState.SKIPPED -> STATE_SKIPPED
    RecordingState.MISSED -> if (r.reason == RecordingReason.CLASH) STATE_MISSED_CLASH else STATE_MISSED_OFF
    RecordingState.CANCELLED -> STATE_SKIPPED
}

/**
 * The screen's sections, each in the order the viewer wants them: what needs attention first, then what is
 * happening, then what is coming, then what is over. A series appears once in Scheduled, with its next
 * showing. [clashWith] names the booking a scheduled one cannot run beside; [mayNotFit] and [sourceName]
 * add notes. Empty sections are left out.
 */
fun recordingsSections(
    rows: List<RecordingEntity>,
    reminders: List<ReminderEntity>,
    series: List<SeriesRuleEntity>,
    inGuide: (RecordingEntity) -> Boolean,
    now: Instant,
    zone: ZoneId,
    clashWith: (RecordingEntity) -> RecordingEntity? = { null },
    mayNotFit: (RecordingEntity) -> Boolean = { false },
    sourceName: (RecordingEntity) -> String? = { null },
): List<RecordingsSection> {
    val rules = series.associateBy { it.id }
    val shown = rows.filter { it.state != RecordingState.CANCELLED }

    fun row(r: RecordingEntity, extra: List<String> = emptyList()): RecordingRow {
        val notes = buildList {
            if (r.state == RecordingState.SCHEDULED && !inGuide(r)) add(NOTE_NOT_IN_GUIDE)
            addAll(extra)
            if (r.state == RecordingState.SCHEDULED && mayNotFit(r)) add(NOTE_MAY_NOT_FIT)
            sourceName(r)?.let { add(noteFrom(it)) }
        }
        return RecordingRow(
            key = "rec:${r.id}", title = r.title, channel = r.channelName, whenText = whenText(r.start, now, zone),
            stateText = stateText(r), notes = notes,
            sizeText = if (r.state == RecordingState.DONE && r.bytes > 0) formatSize(r.bytes) else null,
            recording = r, series = r.seriesId?.let(rules::get),
            problem = r.state == RecordingState.FAILED || r.state == RecordingState.MISSED,
        )
    }

    val scheduled = shown.filter { it.state == RecordingState.SCHEDULED }.sortedBy { it.start }
    val clashing = scheduled.mapNotNull { r -> clashWith(r)?.let { r to it } }
    val clashIds = clashing.mapTo(HashSet()) { it.first.id }

    val seriesCollapsed = scheduled.filter { it.id !in clashIds }
        .groupBy { it.seriesId }
        .flatMap { (seriesId, group) -> if (seriesId == null) group else listOf(group.first()) }
        .sortedBy { it.start }

    val reminderRows = reminders.filter { it.stop > now }.sortedBy { it.start }.map { r ->
        RecordingRow(
            key = "rem:${r.id}", title = r.title, channel = r.channelName, whenText = whenText(r.start, now, zone),
            stateText = "", reminder = r, series = r.seriesId?.let(rules::get),
        )
    }

    return listOf(
        RecordingsSection(SectionKind.CLASHES, clashing.map { (r, other) -> row(r, listOf(noteClashesWith(other.title))) }),
        RecordingsSection(SectionKind.RECORDING_NOW, shown.filter { it.state == RecordingState.RECORDING }.sortedBy { it.start }.map { row(it) }),
        RecordingsSection(SectionKind.REMINDERS, reminderRows),
        RecordingsSection(SectionKind.SCHEDULED, seriesCollapsed.map { row(it) }),
        RecordingsSection(SectionKind.DONE, shown.filter { it.state == RecordingState.DONE }.sortedByDescending { it.start }.map { row(it) }),
        RecordingsSection(
            SectionKind.NOT_COMPLETED,
            shown.filter { it.state in NOT_COMPLETED }.sortedByDescending { it.start }.map { row(it) },
        ),
    ).filter { it.rows.isNotEmpty() }
}

private val NOT_COMPLETED = setOf(RecordingState.STOPPED, RecordingState.FAILED, RecordingState.SKIPPED, RecordingState.MISSED)

/**
 * A scheduled booking that cannot run beside the ones booked before it (or recording now) on its
 * provider's connections; the earliest of those it overlaps. Null when it fits.
 */
fun clashOf(r: RecordingEntity, all: List<RecordingEntity>, playlists: List<PlaylistEntity>): RecordingEntity? {
    val own = playlists.firstOrNull { it.id == (r.sourcePlaylistId ?: r.playlistId) } ?: return null
    val host = providerHost(own)
    val before = all.filter { other ->
        other.id != r.id &&
            (other.state == RecordingState.RECORDING || (other.state == RecordingState.SCHEDULED && other.id < r.id)) &&
            playlists.firstOrNull { it.id == (other.sourcePlaylistId ?: other.playlistId) }?.let(::providerHost) == host &&
            overlaps(other.window(), r.window())
    }
    if (before.isEmpty()) return null
    return before.minBy { it.start }.takeIf { peakConcurrency(r.window(), before.map { it.window() }) > own.effectiveConnections() }
}

/** What the hold-OK menu offers on [row]; [canCatchUp] is whether the channel's archive can supply the programme now. */
fun rowActions(row: RecordingRow, now: Instant, canCatchUp: Boolean): List<RowAction> {
    val reminder = row.reminder
    if (reminder != null) {
        return listOf(if (reminder.seriesId != null) RowAction.CANCEL_SERIES_REMINDER else RowAction.CANCEL_REMINDER)
    }
    val r = row.recording ?: return emptyList()
    val over = r.state !in setOf(RecordingState.SCHEDULED, RecordingState.RECORDING)
    return buildList {
        if (playable(r)) add(RowAction.PLAY)
        if (over && r.state != RecordingState.DONE && r.start <= now && r.stop > now) add(RowAction.RETRY)
        if (over && r.state != RecordingState.DONE && canCatchUp) add(RowAction.RECORD_FROM_CATCH_UP)
        if (r.state == RecordingState.SCHEDULED || r.state == RecordingState.RECORDING) add(RowAction.EDIT_PADDING)
        if (r.state == RecordingState.SCHEDULED) add(RowAction.CANCEL_RECORDING)
        if (r.state == RecordingState.SCHEDULED && r.seriesId != null) add(RowAction.CANCEL_SERIES)
        if (r.seriesId != null) add(RowAction.KEEP_LAST)
        if (r.state == RecordingState.SCHEDULED) add(RowAction.CHANGE_SOURCE)
        if (r.state != RecordingState.SCHEDULED) add(RowAction.DELETE)
    }
}

/** The recording OK opens: one with a file to play. */
fun playable(r: RecordingEntity): Boolean = r.fileName != null && r.state != RecordingState.SCHEDULED

/** Another TV and how many recordings it lists (null while unknown or when it could not be asked). */
data class OtherTv(val peer: CastPeer, val count: Int?)

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/** "Today, 20:00", "Tomorrow, 20:00", else "Sun 22 Nov, 20:00". */
fun whenText(at: Instant, now: Instant, zone: ZoneId): String {
    val date = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DAY.format(at.atZone(zone))
    }
    return "$day, ${TIME.format(at.atZone(zone))}"
}

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

/** "212 GB", "4.5 GB", "630 MB": whole numbers from 10 GB up, one decimal below. */
fun formatSize(bytes: Long): String = when {
    bytes >= 10 * GB -> "${Math.round(bytes.toDouble() / GB)} GB"
    bytes >= GB -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / GB)
    bytes >= MB -> "${Math.round(bytes.toDouble() / MB)} MB"
    else -> "${Math.round(bytes.toDouble() / KB)} KB"
}

fun freeText(freeBytes: Long?, where: String): String = "Free: ${freeBytes?.let(::formatSize) ?: "unknown"} ($where)"
