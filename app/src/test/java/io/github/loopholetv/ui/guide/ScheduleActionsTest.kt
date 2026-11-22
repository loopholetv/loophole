package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.record.BookResult
import io.github.loopholetv.record.Mark
import io.github.loopholetv.record.MarkKey
import io.github.loopholetv.record.ScheduleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The guide's bookings against a real database, on a channel whose guide is shifted. */
@RunWith(RobolectricTestRunner::class)
class ScheduleActionsTest {
    private lateinit var db: AppDatabase
    private lateinit var actions: RepositorySchedule
    private lateinit var repo: ScheduleRepository
    private lateinit var shifted: ChannelEntity
    private val now = Instant.parse("2026-11-22T20:00:00Z")
    private val stopped = mutableListOf<Pair<Long, RecordingReason>>()
    private var rearms = 0
    private var running = emptyList<Long>()

    /** What the programme table holds: an hour earlier than the guide shows it (the channel's shift is +60). */
    private val stored = ProgrammeEntity(
        epgChannelId = "e1", start = now.plusSeconds(3600), stop = now.plusSeconds(5400), title = "Match",
        subTitle = null, description = "About", category = null, generation = 1,
    )

    /** What the guide draws and hands to the buttons. */
    private val shown get() = stored.shiftedBy(60)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        val playlist = db.playlistDao().insert(
            PlaylistEntity(
                name = "P", type = PlaylistType.M3U, url = "http://p.example.test/a.m3u", xtreamServer = null,
                xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
                lastRefreshedAt = null, lastError = null,
            ),
        )
        shifted = db.insertChannels(channel(playlist, "one", 1, epgChannelId = "e1", shift = 60)).getValue("one")
        db.programmeDao().insertAll(listOf(stored))
        repo = ScheduleRepository(db, settings = { AppSettings() }, clock = { now })
        actions = RepositorySchedule(
            repo, db.recordDao(), onNow = { _, _ -> null }, runningOn = { running },
            stop = { id, reason -> stopped += id to reason }, rearm = { rearms++ }, clock = { now },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun marks() = repo.marks().first()

    private val shownKey get() = MarkKey("e1", shown.start.toEpochMilli())

    @Test fun markIsAtTheShownStartOnAShiftedChannel() = runTest {
        actions.record(shifted, shown)
        assertEquals(mapOf(shownKey to Mark.RECORDING), marks())
        assertEquals(stored.start.plusSeconds(3600), shown.start)
    }

    @Test fun aSeriesBookingOfTheSameShowingIsTheSameRow() = runTest {
        assertTrue(actions.record(shifted, shown) is BookResult.Booked)
        actions.recordSeries(shifted, shown)
        assertEquals("one booking, not two", 1, db.recordDao().observeRecordings().first().size)
        assertEquals(mapOf(shownKey to Mark.RECORDING_SERIES), marks())
        // The rule's own re-match (which reads shifted programmes) adds nothing for it either.
        repo.rematchSeries()
        assertEquals(1, db.recordDao().observeRecordings().first().size)
    }

    @Test fun aSeriesBookedFirstIsFoundAgainByTheOneOffRecord() = runTest {
        actions.recordSeries(shifted, shown)
        assertEquals(BookResult.AlreadyBooked, actions.record(shifted, shown))
        assertEquals(mapOf(shownKey to Mark.RECORDING_SERIES), marks())
    }

    @Test fun cancelFindsTheBookingsByTheShownCell() = runTest {
        actions.remind(shifted, shown)
        assertEquals(Mark.REMINDER, marks()[shownKey])
        actions.cancelFor(shifted, shown, Mark.REMINDER)
        assertNull(marks()[shownKey])

        actions.recordSeries(shifted, shown)
        actions.cancelFor(shifted, shown, Mark.RECORDING_SERIES)
        assertNull(marks()[shownKey])
        assertNull(db.recordDao().seriesFor(SeriesKind.RECORD, shifted.playlistId, shifted.key, "Match"))

        actions.record(shifted, shown)
        actions.cancelFor(shifted, shown, Mark.RECORDING)
        assertNull(marks()[shownKey])
    }

    @Test fun seriesReminderMarkAndCancel() = runTest {
        actions.remindSeries(shifted, shown)
        assertEquals(Mark.REMINDER_SERIES, marks()[shownKey])
        actions.cancelFor(shifted, shown, Mark.REMINDER_SERIES)
        assertNull(marks()[shownKey])
        assertNull(db.recordDao().seriesFor(SeriesKind.REMIND, shifted.playlistId, shifted.key, "Match"))
    }

    @Test fun stopRecordingStopsEachRunningOneThenRearms() = runTest {
        running = listOf(4L, 5L)
        actions.stopRecording(shifted)
        assertEquals(listOf(4L to RecordingReason.USER, 5L to RecordingReason.USER), stopped)
        assertEquals(1, rearms)
    }

    @Test fun extendRunsTheRunningRecordingLonger() = runTest {
        val id = (actions.recordNow(shifted) as io.github.loopholetv.record.BookResult.Booked).id
        val before = db.recordDao().recording(id)!!.stop
        running = listOf(id)
        actions.extend(shifted, 30)
        assertEquals(before.plusSeconds(30 * 60L), db.recordDao().recording(id)!!.stop)
    }

    private fun labels(b: CellBookings) = detailsButtons(b).map { it.label }

    @Test fun buttonsTurnIntoTheirCancelCopy() {
        assertEquals(listOf("Remind me", "Remind series", "Record", "Record series"), labels(CellBookings.NONE))
        assertEquals(listOf("Cancel reminder", "Remind series", "Record", "Record series"), labels(CellBookings.of(Mark.REMINDER)))
        assertEquals(listOf("Remind me", "Remind series", "Cancel recording", "Cancel series"), labels(CellBookings.of(Mark.RECORDING_SERIES)))
    }

    @Test fun aReminderUnderARecordingCanStillBeCancelled() = runTest {
        actions.remind(shifted, shown)
        actions.record(shifted, shown)
        assertEquals(Mark.RECORDING, marks()[shownKey])
        assertEquals(
            listOf("Cancel reminder", "Remind series", "Cancel recording", "Record series"),
            labels(actions.bookingsFor(shifted, shown)),
        )
        actions.cancelFor(shifted, shown, Mark.REMINDER)
        assertEquals(listOf("Remind me", "Remind series", "Cancel recording", "Record series"), labels(actions.bookingsFor(shifted, shown)))
    }

    @Test fun aSeriesReminderUnderARecordingOffersToCancelTheSeries() = runTest {
        actions.remindSeries(shifted, shown)
        actions.record(shifted, shown)
        assertEquals(Mark.RECORDING, marks()[shownKey])
        assertEquals(
            listOf("Cancel reminder", "Cancel series reminder", "Cancel recording", "Record series"),
            labels(actions.bookingsFor(shifted, shown)),
        )
        actions.cancelFor(shifted, shown, Mark.REMINDER_SERIES)
        assertEquals(listOf("Remind me", "Remind series", "Cancel recording", "Record series"), labels(actions.bookingsFor(shifted, shown)))
    }
}
