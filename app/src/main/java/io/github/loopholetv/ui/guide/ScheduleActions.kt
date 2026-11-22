package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordDao
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.record.BookResult
import io.github.loopholetv.record.Mark
import io.github.loopholetv.record.ScheduleRepository
import java.time.Instant

/** What is booked for one showing, each kind on its own (a cell's single marker shows only the strongest). */
data class CellBookings(
    val reminder: Boolean = false,
    val reminderSeries: Boolean = false,
    val recording: Boolean = false,
    val recordingSeries: Boolean = false,
) {
    companion object {
        val NONE = CellBookings()

        /** What a cell's marker alone says, until the bookings are looked up. */
        fun of(mark: Mark?) = CellBookings(
            reminder = mark == Mark.REMINDER || mark == Mark.REMINDER_SERIES,
            reminderSeries = mark == Mark.REMINDER_SERIES,
            recording = mark == Mark.RECORDING || mark == Mark.RECORDING_SERIES,
            recordingSeries = mark == Mark.RECORDING_SERIES,
        )
    }
}

/**
 * What the guide books and cancels. Programmes are passed as the guide shows them (at the channel's
 * guide time shift), which is how bookings store them, so a booking is found again by the cell's
 * own channel and start.
 */
interface ScheduleActions {
    suspend fun remind(c: ChannelEntity, p: ProgrammeEntity)
    suspend fun remindSeries(c: ChannelEntity, p: ProgrammeEntity)
    suspend fun record(c: ChannelEntity, p: ProgrammeEntity): BookResult
    suspend fun recordSeries(c: ChannelEntity, p: ProgrammeEntity)

    /** Records [c] from now: the programme on now to its end, or an hour without guide data. */
    suspend fun recordNow(c: ChannelEntity): BookResult

    /** Ends the recording running on [c], keeping what it has. */
    suspend fun stopRecording(c: ChannelEntity)

    /** Ends the recording running on [c] [byMinutes] later. */
    suspend fun extend(c: ChannelEntity, byMinutes: Int)

    /** Every booking on [p] of [c], looked up by the cell's channel and start. */
    suspend fun bookingsFor(c: ChannelEntity, p: ProgrammeEntity): CellBookings = CellBookings.NONE

    /** Cancels what [mark] says is booked for [p]: the showing's booking, or (a series mark) the whole series. */
    suspend fun cancelFor(c: ChannelEntity, p: ProgrammeEntity, mark: Mark)
}

/** Books nothing: the default for a guide without recording. */
object NoSchedule : ScheduleActions {
    override suspend fun remind(c: ChannelEntity, p: ProgrammeEntity) = Unit
    override suspend fun remindSeries(c: ChannelEntity, p: ProgrammeEntity) = Unit
    override suspend fun record(c: ChannelEntity, p: ProgrammeEntity): BookResult = BookResult.AlreadyBooked
    override suspend fun recordSeries(c: ChannelEntity, p: ProgrammeEntity) = Unit
    override suspend fun recordNow(c: ChannelEntity): BookResult = BookResult.AlreadyBooked
    override suspend fun stopRecording(c: ChannelEntity) = Unit
    override suspend fun extend(c: ChannelEntity, byMinutes: Int) = Unit
    override suspend fun cancelFor(c: ChannelEntity, p: ProgrammeEntity, mark: Mark) = Unit
}

/** [ScheduleActions] on the repository; bookings are found by (playlist, channel, start), series by title. */
class RepositorySchedule(
    private val repo: ScheduleRepository,
    private val dao: RecordDao,
    /** The programme airing at an instant on a channel, at its time shift. */
    private val onNow: suspend (ChannelEntity, Instant) -> ProgrammeEntity?,
    /** Ids of the recordings running on a channel. */
    private val runningOn: (ChannelEntity) -> List<Long>,
    private val stop: suspend (id: Long, reason: RecordingReason) -> Unit,
    /** Re-arms the alarms after a stop (a running recording's end alarm is no longer wanted). */
    private val rearm: suspend () -> Unit,
    private val clock: () -> Instant = Instant::now,
) : ScheduleActions {
    override suspend fun remind(c: ChannelEntity, p: ProgrammeEntity) {
        repo.remind(c, p)
    }

    override suspend fun remindSeries(c: ChannelEntity, p: ProgrammeEntity) {
        repo.remindSeries(c, p)
    }

    override suspend fun record(c: ChannelEntity, p: ProgrammeEntity): BookResult = repo.record(c, p)

    override suspend fun recordSeries(c: ChannelEntity, p: ProgrammeEntity) {
        repo.recordSeries(c, p)
    }

    override suspend fun recordNow(c: ChannelEntity): BookResult = repo.recordNow(c, onNow(c, clock()))

    override suspend fun stopRecording(c: ChannelEntity) {
        runningOn(c).forEach { stop(it, RecordingReason.USER) }
        rearm()
    }

    override suspend fun extend(c: ChannelEntity, byMinutes: Int) {
        runningOn(c).forEach { repo.extend(it, byMinutes) }
        rearm()
    }

    override suspend fun bookingsFor(c: ChannelEntity, p: ProgrammeEntity): CellBookings {
        val reminder = dao.reminderFor(c.playlistId, c.key, p.start)?.takeIf { it.notifiedAt == null }
        val recording = dao.recordingFor(c.playlistId, c.key, p.start)?.takeIf { it.state == RecordingState.SCHEDULED }
        return CellBookings(
            reminder = reminder != null,
            reminderSeries = reminder?.seriesId != null,
            recording = recording != null,
            recordingSeries = recording?.seriesId != null,
        )
    }

    override suspend fun cancelFor(c: ChannelEntity, p: ProgrammeEntity, mark: Mark) {
        when (mark) {
            Mark.REMINDER -> dao.reminderFor(c.playlistId, c.key, p.start)?.let { repo.cancelReminder(it.id) }
            Mark.REMINDER_SERIES -> seriesId(SeriesKind.REMIND, c, p)?.let { repo.cancelSeries(it) }
            Mark.RECORDING -> dao.recordingFor(c.playlistId, c.key, p.start)?.let { repo.cancelRecording(it.id) }
            Mark.RECORDING_SERIES -> seriesId(SeriesKind.RECORD, c, p)?.let { repo.cancelSeries(it) }
            // A running recording is ended from the channel's options.
            Mark.RECORDING_NOW -> Unit
        }
    }

    private suspend fun seriesId(kind: SeriesKind, c: ChannelEntity, p: ProgrammeEntity): Long? =
        dao.seriesFor(kind, c.playlistId, c.key, p.title.trim())?.id
}
