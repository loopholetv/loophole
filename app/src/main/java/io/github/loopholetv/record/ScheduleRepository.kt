package io.github.loopholetv.record

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.SeriesRuleEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.record.storage.FREE_SPACE_TIMEOUT_MS
import io.github.loopholetv.record.storage.freeBytesWithin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

sealed interface BookResult {
    /**
     * [clash] is the booking this one could not be separated from (null when there is none, or when the
     * clash was resolved by recording from [fallback], another provider's copy of the channel).
     */
    data class Booked(val id: Long, val clash: RecordingEntity?, val fallback: PlaylistEntity?, val mayNotFit: Boolean) : BookResult

    data object AlreadyBooked : BookResult
}

/** A guide cell: a programme of a guide channel, by its start. */
data class MarkKey(val epgChannelId: String, val startMs: Long)

enum class Mark { REMINDER, REMINDER_SERIES, RECORDING, RECORDING_SERIES, RECORDING_NOW }

private val REVIVABLE = setOf(RecordingState.SKIPPED, RecordingState.FAILED, RecordingState.MISSED)

/** The most streams open at once during [target] with [others], counting [target] itself. */
internal fun peakConcurrency(target: Window, others: List<Window>): Int {
    val points = listOf(target.from) + others.map { it.from }.filter { it > target.from && it < target.until }
    return points.maxOf { t -> 1 + others.count { it.from <= t && t < it.until } }
}

private data class Fallback(val playlist: PlaylistEntity, val channel: ChannelEntity)

/**
 * Books reminders and recordings and keeps series rules up to date. A booking copies the programme's
 * fields, so a guide refresh never changes one; [rematchSeries] only adds showings and moves bookings
 * that have not started. All writes are serialised, so a lookup is never stale when its row is inserted.
 */
class ScheduleRepository(
    private val db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Instant,
    private val onChanged: suspend () -> Unit = {},
    private val deleteFile: suspend (RecordingEntity) -> Unit = {},
    /** Free bytes on the chosen storage; null means unknown, and then nothing is flagged "May not fit". */
    private val freeBytes: suspend () -> Long? = { null },
) {
    private val recordDao = db.recordDao()
    private val channelDao = db.channelDao()
    private val playlistDao = db.playlistDao()
    private val programmeDao = db.programmeDao()
    private val lock = Mutex()

    // --- reminders ---

    private val _askNotifications = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a reminder or a series reminder is booked: the moment to ask for the notification permission. */
    val askNotifications: kotlinx.coroutines.flow.SharedFlow<Unit> = _askNotifications

    suspend fun remind(channel: ChannelEntity, p: ProgrammeEntity): Long =
        changing { remindLocked(channel, p, null, revive = true) }.also { _askNotifications.tryEmit(Unit) }

    suspend fun remindSeries(channel: ChannelEntity, p: ProgrammeEntity): Long = remindSeriesChanging(channel, p).also { _askNotifications.tryEmit(Unit) }

    private suspend fun remindSeriesChanging(channel: ChannelEntity, p: ProgrammeEntity): Long = changing {
        val rule = ensureRule(SeriesKind.REMIND, channel, p)
        remindLocked(channel, p, rule.id, revive = true)
        applyRule(rule, clock())
        rule.id
    }

    suspend fun cancelReminder(id: Long) = changing {
        val r = recordDao.reminder(id) ?: return@changing
        // A series' reminder is only dismissed: deleting it would bring it back at the next re-match.
        if (r.seriesId != null && recordDao.series(r.seriesId) != null) recordDao.markNotified(id, clock())
        else recordDao.deleteReminder(id)
    }

    // --- recordings ---

    suspend fun record(channel: ChannelEntity, p: ProgrammeEntity): BookResult = withFit(
        changing {
            val s = settings()
            bookRow(channel, recordingRow(channel, p, null, s.padBeforeMin, s.padAfterMin), revive = true)
        },
    )

    suspend fun recordSeries(channel: ChannelEntity, p: ProgrammeEntity): Long = changing {
        val s = settings()
        val rule = ensureRule(SeriesKind.RECORD, channel, p, s.padBeforeMin, s.padAfterMin)
        bookRow(channel, recordingRow(channel, p, rule.id, rule.padBeforeMin, rule.padAfterMin), revive = true)
        applyRule(rule, clock())
        rule.id
    }

    /** Records from now: the programme on now until its end, or without guide data one hour. */
    suspend fun recordNow(channel: ChannelEntity, onNow: ProgrammeEntity?): BookResult = withFit(changing { recordNowLocked(channel, onNow) })

    private suspend fun recordNowLocked(channel: ChannelEntity, onNow: ProgrammeEntity?): BookResult {
        val now = clock()
        val s = settings()
        val covering = recordDao.inState(RecordingState.SCHEDULED, RecordingState.RECORDING).any {
            it.playlistId == channel.playlistId && it.channelKey == channel.key &&
                it.window().let { w -> w.from <= now && now < w.until }
        }
        if (covering) return BookResult.AlreadyBooked
        val row = if (onNow != null && onNow.stop > now) {
            // Keeps the programme's own start, so it is the same booking as the guide's.
            recordingRow(channel, onNow, null, 0, s.padAfterMin)
        } else {
            recordingRow(channel, null, null, 0, s.padAfterMin, start = now, stop = now.plusMillis(NO_GUIDE_RECORD_MS))
        }
        return bookRow(channel, row)
    }

    suspend fun extend(recordingId: Long, byMinutes: Int) = changing {
        val r = recordDao.recording(recordingId) ?: return@changing
        if (byMinutes > 0 && r.isActive()) recordDao.updateRecording(r.copy(stop = r.stop.plusSeconds(byMinutes * 60L)))
    }

    /** A booking not yet started is dropped; one that belongs to a series is skipped, so a re-match does not book it again. */
    suspend fun cancelRecording(id: Long) = changing {
        val r = recordDao.recording(id) ?: return@changing
        if (r.state != RecordingState.SCHEDULED) return@changing
        if (r.seriesId != null) {
            recordDao.updateRecording(r.copy(state = RecordingState.SKIPPED, reason = RecordingReason.USER))
        } else {
            recordDao.deleteRecording(id)
        }
    }

    suspend fun cancelSeries(id: Long) = changing { recordDao.deleteSeries(id) }

    suspend fun setPadding(recordingId: Long, before: Int, after: Int) = changing {
        val r = recordDao.recording(recordingId) ?: return@changing
        if (r.isActive()) recordDao.updateRecording(r.copy(padBeforeMin = before.coerceAtLeast(0), padAfterMin = after.coerceAtLeast(0)))
    }

    /** Sets how many finished episodes a series keeps (null: all) and drops the older ones now. */
    suspend fun setKeepLast(seriesId: Long, n: Int?) = changing {
        val rule = recordDao.series(seriesId) ?: return@changing
        val updated = rule.copy(keepLast = n?.coerceAtLeast(1))
        recordDao.upsertSeries(updated)
        applyKeepLast(updated)
    }

    /** Records from another playlist's copy of the channel (both null: from the channel as booked). */
    suspend fun changeSource(recordingId: Long, playlistId: Long?, channelKey: String?) = changing {
        val r = recordDao.recording(recordingId) ?: return@changing
        if (r.state != RecordingState.RECORDING) {
            recordDao.updateRecording(r.copy(sourcePlaylistId = playlistId, sourceChannelKey = channelKey))
        }
    }

    // --- series ---

    /**
     * After a guide refresh: moves bookings not yet started to where their programme went, books the
     * showings of each rule that are neither repeats nor already booked, and trims each series to its
     * "keep the last". Running and finished recordings are never touched. [playlistId] limits it to that
     * playlist's rules and bookings.
     */
    suspend fun rematchSeries(playlistId: Long? = null) {
        val changed = lock.withLock {
            val now = clock()
            var any = followMoves(playlistId)
            for (rule in recordDao.allSeries().filter { playlistId == null || it.playlistId == playlistId }) {
                any = applyRule(rule, now) || any
                any = applyKeepLast(rule) || any
            }
            any
        }
        if (changed) onChanged()
    }

    // --- what the screens show ---

    fun marks(): Flow<Map<MarkKey, Mark>> =
        combine(recordDao.observeRecordings(), recordDao.observeReminders(clock())) { recordings, reminders ->
            val marks = HashMap<MarkKey, Mark>()
            for (r in reminders) {
                val epg = r.epgChannelId ?: continue
                if (r.notifiedAt != null) continue
                marks[MarkKey(epg, r.start.toEpochMilli())] = if (r.seriesId != null) Mark.REMINDER_SERIES else Mark.REMINDER
            }
            for (r in recordings) {
                val epg = r.epgChannelId ?: continue
                val mark = when (r.state) {
                    RecordingState.SCHEDULED -> if (r.seriesId != null) Mark.RECORDING_SERIES else Mark.RECORDING
                    RecordingState.RECORDING -> Mark.RECORDING_NOW
                    else -> continue
                }
                marks[MarkKey(epg, r.start.toEpochMilli())] = mark
            }
            marks
        }

    /** Reminders that have not been shown yet, for programmes that have not ended. */
    fun upcomingReminders(): Flow<List<ReminderEntity>> =
        recordDao.observeReminders(clock()).map { list -> list.filter { it.notifiedAt == null } }

    // --- internals ---

    /** Runs [block] with writes serialised, then tells the listener, outside the lock. */
    private suspend fun <T> changing(block: suspend () -> T): T {
        val result = lock.withLock { block() }
        onChanged()
        return result
    }

    private fun RecordingEntity.isActive() = state == RecordingState.SCHEDULED || state == RecordingState.RECORDING

    /**
     * [revive] (a person asking) brings back a dismissed reminder for a showing that has not started; the
     * automatic re-match leaves it dismissed.
     */
    private suspend fun remindLocked(channel: ChannelEntity, p: ProgrammeEntity, seriesId: Long?, revive: Boolean = false): Long {
        val existing = recordDao.reminderFor(channel.playlistId, channel.key, p.start)
        if (existing != null) {
            if (revive && existing.notifiedAt != null && existing.start > clock()) {
                recordDao.upsertReminder(existing.copy(notifiedAt = null, seriesId = seriesId))
            } else if (seriesId != null && existing.seriesId == null) {
                recordDao.upsertReminder(existing.copy(seriesId = seriesId))
            }
            return existing.id
        }
        return recordDao.upsertReminder(
            ReminderEntity(
                playlistId = channel.playlistId, channelKey = channel.key, channelName = channel.displayName,
                epgChannelId = p.epgChannelId, title = p.title, subTitle = p.subTitle, episodeKey = p.episodeKey,
                start = p.start, stop = p.stop, seriesId = seriesId,
            ),
        )
    }

    /** The rule for this title on this channel, created when missing (an existing row's id is reused: upsert does not merge). */
    private suspend fun ensureRule(
        kind: SeriesKind,
        channel: ChannelEntity,
        p: ProgrammeEntity,
        padBefore: Int = 0,
        padAfter: Int = 0,
    ): SeriesRuleEntity {
        recordDao.seriesFor(kind, channel.playlistId, channel.key, p.title.trim())?.let { return it }
        val draft = SeriesRuleEntity(
            kind = kind, playlistId = channel.playlistId, channelKey = channel.key, channelName = channel.displayName,
            epgChannelId = channel.guideEpgId ?: p.epgChannelId, title = p.title.trim(),
            padBeforeMin = padBefore, padAfterMin = padAfter, createdAt = clock(),
        )
        return draft.copy(id = recordDao.upsertSeries(draft))
    }

    private fun recordingRow(
        channel: ChannelEntity,
        p: ProgrammeEntity?,
        seriesId: Long?,
        padBefore: Int,
        padAfter: Int,
        start: Instant = p?.start ?: clock(),
        stop: Instant = p?.stop ?: clock(),
    ) = RecordingEntity(
        playlistId = channel.playlistId, channelKey = channel.key, channelName = channel.displayName,
        epgChannelId = p?.epgChannelId ?: channel.guideEpgId, title = p?.title ?: channel.displayName,
        subTitle = p?.subTitle, episodeKey = p?.episodeKey, description = p?.description,
        start = start, stop = stop, padBeforeMin = padBefore, padAfterMin = padAfter, seriesId = seriesId,
        createdAt = clock(),
    )

    /**
     * Inserts [row] unless that showing is already booked. A person asking ([revive]) brings back a
     * skipped, failed or missed booking of a showing that has not started, in place; the automatic
     * re-match leaves those alone. A clash is resolved by another provider's copy of the channel when
     * one has it. "May not fit" is left to [withFit], outside the lock.
     */
    private suspend fun bookRow(channel: ChannelEntity, row: RecordingEntity, revive: Boolean = false): BookResult {
        val existing = recordDao.recordingFor(row.playlistId, row.channelKey, row.start)
        var toBook = row
        if (existing != null) {
            val revivable = existing.state in REVIVABLE && existing.start > clock()
            if (!(revive && revivable)) {
                if (row.seriesId != null && existing.seriesId == null && existing.state == RecordingState.SCHEDULED) {
                    recordDao.updateRecording(existing.copy(seriesId = row.seriesId))
                }
                return BookResult.AlreadyBooked
            }
            toBook = row.copy(id = existing.id)
        }
        val playlists = playlistDao.all().first()
        val own = playlists.firstOrNull { it.id == toBook.playlistId }
        val active = recordDao.inState(RecordingState.SCHEDULED, RecordingState.RECORDING)
        var booked = toBook
        var clash: RecordingEntity? = null
        var fallback: Fallback? = null
        if (own != null) {
            val sameProvider = active.overlapping(toBook, providerHost(own), playlists)
            if (peakConcurrency(toBook.window(), sameProvider.map { it.window() }) > own.effectiveConnections()) {
                fallback = findFallback(channel, own, toBook, active, playlists)
                if (fallback != null) {
                    booked = toBook.copy(sourcePlaylistId = fallback.playlist.id, sourceChannelKey = fallback.channel.key)
                } else {
                    clash = sameProvider.minBy { it.start }
                }
            }
        }
        val id = if (existing != null) {
            recordDao.updateRecording(booked)
            existing.id
        } else {
            recordDao.insertRecording(booked).also { if (it < 0) return BookResult.AlreadyBooked }
        }
        return BookResult.Booked(id, clash, fallback?.playlist, mayNotFit = false)
    }

    private fun RecordingEntity.hostPlaylist(playlists: List<PlaylistEntity>): PlaylistEntity? =
        playlists.firstOrNull { it.id == sourcePlaylistId } ?: playlists.firstOrNull { it.id == playlistId }

    private fun List<RecordingEntity>.overlapping(row: RecordingEntity, host: String, playlists: List<PlaylistEntity>) =
        filter { other ->
            other.id != row.id && other.hostPlaylist(playlists)?.let(::providerHost) == host &&
                overlaps(other.window(), row.window())
        }

    /** Another enabled playlist on a different provider with the same channel and a free connection for the window. */
    private suspend fun findFallback(
        channel: ChannelEntity,
        own: PlaylistEntity,
        row: RecordingEntity,
        active: List<RecordingEntity>,
        playlists: List<PlaylistEntity>,
    ): Fallback? {
        val ownHost = providerHost(own)
        for (other in playlists) {
            if (!other.enabled || other.id == own.id || providerHost(other) == ownHost) continue
            val others = active.overlapping(row, providerHost(other), playlists).map { it.window() }
            if (peakConcurrency(row.window(), others) > other.effectiveConnections()) continue
            val match = channelDao.channelsOf(other.id).firstOrNull { sameChannel(channel, it) } ?: continue
            return Fallback(other, match)
        }
        return null
    }

    /**
     * Adds "May not fit" to a booking just made. After the commit and outside the lock, since the free space
     * may come from a slow network folder; a read past [FREE_SPACE_TIMEOUT_MS] counts as unknown (no warning).
     */
    private suspend fun withFit(result: BookResult): BookResult {
        if (result !is BookResult.Booked) return result
        val row = recordDao.recording(result.id) ?: return result
        val free = freeBytesWithin { freeBytes() }
        return result.copy(mayNotFit = mayNotFit(row, free, recordDao.inState(RecordingState.DONE)))
    }

    /** Bookings that have not started follow their programme when the guide moved it. */
    private suspend fun followMoves(playlistId: Long?): Boolean {
        var changed = false
        val scheduled = recordDao.inState(RecordingState.SCHEDULED).filter { playlistId == null || it.playlistId == playlistId }
        for (r in scheduled) {
            val channel = channelDao.byKey(r.playlistId, r.channelKey) ?: continue
            val epgId = r.epgChannelId ?: channel.guideEpgId ?: continue
            val shift = channel.epgShiftMinutes
            val from = r.start.minusMillis(TIME_FOLLOW_WINDOW_MS).minusSeconds(shift * 60L)
            val shown = programmeDao.upcomingFor(epgId, from).map { it.shiftedBy(shift) }
            val moved = followMove(r, shown) ?: continue
            if (moved.start == r.start && moved.stop == r.stop) continue
            if (moved.start != r.start && recordDao.recordingFor(r.playlistId, r.channelKey, moved.start) != null) continue
            recordDao.updateRecording(r.copy(start = moved.start, stop = moved.stop))
            changed = true
        }
        return changed
    }

    /** Books the rule's showings that have not started and are not booked, recording or repeats. */
    private suspend fun applyRule(rule: SeriesRuleEntity, now: Instant): Boolean {
        val channel = channelDao.byKey(rule.playlistId, rule.channelKey) ?: return false
        val epgId = channel.guideEpgId ?: rule.epgChannelId ?: return false
        val shift = channel.epgShiftMinutes
        val horizon = now.plus(java.time.Duration.ofDays(SERIES_HORIZON_DAYS.toLong()))
        val showings = programmeDao.upcomingFor(epgId, now.minusSeconds(shift * 60L))
            .map { it.shiftedBy(shift) }
            .filter { seriesMatches(rule, it) && it.start >= now && it.start < horizon }
        var changed = false
        var added = 0
        when (rule.kind) {
            SeriesKind.REMIND -> for (p in showings) {
                if (recordDao.reminderFor(rule.playlistId, rule.channelKey, p.start) != null) continue
                if (added >= SERIES_MAX_PER_RULE) break
                remindLocked(channel, p, rule.id)
                added++
                changed = true
            }
            SeriesKind.RECORD -> {
                val episodes = recordDao.seriesEpisodes(rule.id).toMutableList()
                for (p in showings) {
                    if (recordDao.recordingFor(rule.playlistId, rule.channelKey, p.start) != null) continue
                    if (episodes.any { it.state == RecordingState.RECORDING && isSameShowing(it, p) }) continue
                    if (isRepeat(p, episodes)) continue
                    if (added >= SERIES_MAX_PER_RULE) break
                    val result = bookRow(channel, recordingRow(channel, p, rule.id, rule.padBeforeMin, rule.padAfterMin))
                    if (result is BookResult.Booked) {
                        recordDao.recording(result.id)?.let { episodes += it }
                        added++
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    /** A recording in progress cannot follow its programme, so a guide that now lists it later is the same showing. */
    private fun isSameShowing(running: RecordingEntity, p: ProgrammeEntity): Boolean =
        running.title.trim().equals(p.title.trim(), ignoreCase = true) &&
            p.start < running.stop && running.start < p.stop &&
            kotlin.math.abs(p.start.toEpochMilli() - running.start.toEpochMilli()) <= TIME_FOLLOW_WINDOW_MS

    /** Deletes the files and rows of the oldest finished episodes beyond the rule's "keep the last". */
    private suspend fun applyKeepLast(rule: SeriesRuleEntity): Boolean {
        if (rule.kind != SeriesKind.RECORD) return false
        var changed = false
        for (r in keepLastDeletions(recordDao.seriesEpisodes(rule.id), rule.keepLast)) {
            try {
                deleteFile(r)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue // the row stays, so the next re-match tries again
            }
            recordDao.deleteRecording(r.id)
            changed = true
        }
        return changed
    }
}
