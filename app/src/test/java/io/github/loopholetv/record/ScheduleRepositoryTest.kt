package io.github.loopholetv.record

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ScheduleRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val now = Instant.parse("2026-11-22T20:00:00Z")
    private var changes = 0
    private val deleted = mutableListOf<Long>()
    private var free: Long? = null
    private var freeCalls = 0
    private var freeRead: suspend () -> Long? = { free }
    private var generation = 1L

    @Before fun setUp() {
        db = inMemoryDb()
        repo = ScheduleRepository(
            db, settings = { AppSettings() }, clock = { now },
            onChanged = { changes++ }, deleteFile = { deleted += it.id }, freeBytes = { freeCalls++; freeRead() },
        )
    }

    @After fun tearDown() = db.close()

    private fun min(n: Long) = now.plusSeconds(n * 60)
    private fun day(n: Long) = now.plusSeconds(n * 86_400)

    private suspend fun playlist(name: String, url: String, connections: Int? = null): PlaylistEntity {
        val id = db.playlistDao().insert(
            PlaylistEntity(
                name = name, type = PlaylistType.M3U, url = url, xtreamServer = null, xtreamUser = null,
                xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
                lastError = null, connectionsAllowed = connections,
            ),
        )
        return db.playlistDao().byId(id)!!
    }

    private suspend fun chan(p: PlaylistEntity, key: String, name: String, epg: String?): ChannelEntity =
        db.insertChannels(channel(p.id, key, key.hashCode() and 0xff, name = name, epgChannelId = epg)).getValue(key)

    private fun prog(
        epg: String, start: Instant, minutes: Long = 30, title: String = "News", episodeKey: String? = null,
    ) = ProgrammeEntity(
        epgChannelId = epg, start = start, stop = start.plusSeconds(minutes * 60), title = title, subTitle = null,
        description = "About", category = null, generation = generation, episodeKey = episodeKey,
    )

    /** What a guide refresh does: the old rows go, the new ones come with new ids and a new generation. */
    private suspend fun refreshGuide(vararg programmes: ProgrammeEntity) {
        db.programmeDao().deleteAll()
        generation++
        db.programmeDao().insertAll(programmes.map { it.copy(id = 0, generation = generation) })
    }

    private suspend fun recordings() = db.recordDao().observeRecordings().first()

    @Test fun recordBooksWithDefaultPadding() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", min(60), title = "Film")
        val result = repo.record(c, p) as BookResult.Booked
        assertNull(result.clash)
        assertNull(result.fallback)
        val row = db.recordDao().recording(result.id)!!
        assertEquals(RecordingState.SCHEDULED, row.state)
        assertEquals(1, row.padBeforeMin)
        assertEquals(5, row.padAfterMin)
        assertEquals("Film", row.title)
        assertEquals("c1", row.channelKey)
        assertEquals("One", row.channelName)
        assertEquals("e1", row.epgChannelId)
        assertEquals(p.start, row.start)
        assertEquals(p.stop, row.stop)
        assertTrue(changes > 0)
    }

    @Test fun recordTwiceIsAlreadyBooked() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", min(60))
        assertTrue(repo.record(c, p) is BookResult.Booked)
        assertEquals(BookResult.AlreadyBooked, repo.record(c, p))
        assertEquals(1, recordings().size)
    }

    @Test fun seriesBooksAllFutureMatchesSkippingRepeats() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(
            prog("e1", day(-1), episodeKey = "S01E00"), // already aired
            prog("e1", day(1), episodeKey = "S01E01"),
            prog("e1", day(2), episodeKey = "S01E01"), // repeat of the one before
            prog("e1", day(3), episodeKey = "S01E02"),
            prog("e1", day(1), title = "Weather"),
        )
        val ruleId = repo.recordSeries(c, prog("e1", day(1), episodeKey = "S01E01"))
        val rows = recordings().sortedBy { it.start }
        assertEquals(listOf(day(1), day(3)), rows.map { it.start })
        assertTrue(rows.all { it.seriesId == ruleId })
        assertEquals(SeriesKind.RECORD, db.recordDao().allSeries().single().kind)
    }

    @Test fun rematchAfterRefreshAddsNewShowingsWithoutDuplicates() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(prog("e1", day(1), episodeKey = "E1"), prog("e1", day(2), episodeKey = "E2"))
        repo.recordSeries(c, prog("e1", day(1), episodeKey = "E1"))
        val before = recordings().associate { it.start to it.id }
        assertEquals(2, before.size)

        refreshGuide(
            prog("e1", day(1), episodeKey = "E1"),
            prog("e1", day(2), episodeKey = "E2"),
            prog("e1", day(3), episodeKey = "E3"),
        )
        repo.rematchSeries()
        repo.rematchSeries(a.id)
        val after = recordings()
        assertEquals(3, after.size)
        assertEquals(before, after.filter { it.start in before }.associate { it.start to it.id })
        assertEquals(1, after.count { it.start == day(3) })
    }

    @Test fun rematchFollowsMovedProgramme() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val near = (repo.record(c, prog("e1", day(1), title = "Film")) as BookResult.Booked).id
        val far = (repo.record(c, prog("e1", day(2), title = "Show")) as BookResult.Booked).id
        refreshGuide(
            prog("e1", day(1).plusSeconds(600), title = "Film"),
            prog("e1", day(2).plusSeconds(7200), title = "Show"),
        )
        repo.rematchSeries()
        val moved = db.recordDao().recording(near)!!
        assertEquals(day(1).plusSeconds(600), moved.start)
        assertEquals(day(1).plusSeconds(600 + 1800), moved.stop)
        assertEquals(day(2), db.recordDao().recording(far)!!.start)
    }

    @Test fun runningRecordingUntouchedByRefresh() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val showing = prog("e1", min(-10))
        refreshGuide(showing)
        val ruleId = repo.recordSeries(c, showing)
        val row = recordings().single().copy(state = RecordingState.RECORDING, startedAt = min(-10))
        db.recordDao().updateRecording(row)

        // The guide now lists the same showing ten minutes later, without an episode marker.
        refreshGuide(prog("e1", min(0)))
        repo.rematchSeries()

        assertEquals(listOf(row), recordings())
        assertEquals(ruleId, recordings().single().seriesId)
    }

    @Test fun clashFallsBackToOtherProvider() = runTest {
        val a = playlist("A", "http://a.test/l.m3u", connections = 1)
        val b = playlist("B", "http://b.test/l.m3u", connections = 1)
        val a1 = chan(a, "c1", "One", "e1")
        val a2 = chan(a, "c2", "Two", "e2")
        chan(b, "x1", "One", "e1")
        val b2 = chan(b, "x2", "Two HD", "e2")
        assertTrue(repo.record(a1, prog("e1", min(60))) is BookResult.Booked)
        val second = repo.record(a2, prog("e2", min(70))) as BookResult.Booked
        assertNull(second.clash)
        assertEquals(b.id, second.fallback?.id)
        val row = db.recordDao().recording(second.id)!!
        assertEquals(b.id, row.sourcePlaylistId)
        assertEquals(b2.key, row.sourceChannelKey)
        assertEquals(a.id, row.playlistId)
    }

    @Test fun clashSameHostNoFallback() = runTest {
        val a = playlist("A", "http://a.test/one.m3u", connections = 1)
        val b = playlist("B", "http://a.test/two.m3u", connections = 1)
        val a1 = chan(a, "c1", "One", "e1")
        val a2 = chan(a, "c2", "Two", "e2")
        chan(b, "x2", "Two", "e2")
        val first = repo.record(a1, prog("e1", min(60))) as BookResult.Booked
        val second = repo.record(a2, prog("e2", min(70))) as BookResult.Booked
        assertEquals(first.id, second.clash?.id)
        assertNull(second.fallback)
        assertNull(db.recordDao().recording(second.id)!!.sourcePlaylistId)
    }

    @Test fun recordNowWithoutGuideIsOneHour() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", null)
        val result = repo.recordNow(c, null) as BookResult.Booked
        val row = db.recordDao().recording(result.id)!!
        assertEquals(now, row.start)
        assertEquals(now.plusMillis(NO_GUIDE_RECORD_MS), row.stop)
        assertEquals(0, row.padBeforeMin)
        assertEquals("One", row.title)
    }

    @Test fun recordNowOnAProgrammeRunsToItsEnd() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val onNow = prog("e1", min(-10), minutes = 40, title = "Film")
        val row = db.recordDao().recording((repo.recordNow(c, onNow) as BookResult.Booked).id)!!
        assertEquals("Film", row.title)
        assertEquals(onNow.start, row.start)
        assertEquals(onNow.stop, row.stop)
        assertEquals(BookResult.AlreadyBooked, repo.recordNow(c, onNow))
    }

    @Test fun extendAddsThirtyMinutes() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val id = (repo.record(c, prog("e1", min(60))) as BookResult.Booked).id
        repo.extend(id, EXTEND_STEP_MIN)
        assertEquals(min(120), db.recordDao().recording(id)!!.stop)
    }

    @Test fun keepLastDeletesOldest() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val ruleId = repo.recordSeries(c, prog("e1", day(5)))
        repo.setKeepLast(ruleId, 1)
        fun done(start: Instant, state: RecordingState = RecordingState.DONE) = RecordingEntity(
            playlistId = a.id, channelKey = "c1", channelName = "One", epgChannelId = "e1", title = "News",
            subTitle = null, episodeKey = null, description = null, start = start, stop = start.plusSeconds(1800),
            padBeforeMin = 1, padAfterMin = 5, seriesId = ruleId, state = state, createdAt = now, bytes = 1,
        )
        val old = db.recordDao().insertRecording(done(day(-3)))
        val mid = db.recordDao().insertRecording(done(day(-2)))
        val new = db.recordDao().insertRecording(done(day(-1)))
        val running = db.recordDao().insertRecording(done(now.minusSeconds(60), RecordingState.RECORDING))
        deleted.clear()

        repo.rematchSeries()

        assertEquals(listOf(old, mid), deleted)
        val left = recordings().map { it.id }
        assertTrue(new in left && running in left)
        assertTrue(old !in left && mid !in left)
        assertEquals(listOf(new), db.recordDao().inState(RecordingState.DONE).map { it.id })
        assertEquals(listOf(running), db.recordDao().inState(RecordingState.RECORDING).map { it.id })
    }

    @Test fun cancelledSeriesEpisodeIsNotBookedAgain() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(prog("e1", day(1)), prog("e1", day(2)))
        repo.recordSeries(c, prog("e1", day(1)))
        val first = recordings().minBy { it.start }
        repo.cancelRecording(first.id)
        repo.rematchSeries()
        val rows = recordings().sortedBy { it.start }
        assertEquals(2, rows.size)
        assertEquals(RecordingState.SKIPPED, rows[0].state)
        assertEquals(RecordingState.SCHEDULED, rows[1].state)
    }

    @Test fun cancelSeriesKeepsFinishedRecordings() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(prog("e1", day(1)))
        val ruleId = repo.recordSeries(c, prog("e1", day(1)))
        repo.cancelSeries(ruleId)
        assertTrue(db.recordDao().allSeries().isEmpty())
        assertTrue(recordings().isEmpty())
    }

    @Test fun remindersAreBookedOnceAndSeriesRemindersFollowTheGuide() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", day(1))
        val id = repo.remind(c, p)
        assertEquals(id, repo.remind(c, p))
        refreshGuide(p, prog("e1", day(2)))
        val ruleId = repo.remindSeries(c, p)
        repo.rematchSeries()
        val reminders = repo.upcomingReminders().first()
        assertEquals(2, reminders.size)
        assertTrue(reminders.all { it.seriesId == ruleId })
        assertEquals(SeriesKind.REMIND, db.recordDao().allSeries().single().kind)
        repo.cancelReminder(reminders.first().id)
        repo.rematchSeries()
        assertEquals(1, repo.upcomingReminders().first().size)
    }

    @Test fun marksShowBookingsByGuideCell() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(prog("e1", day(2)))
        repo.remind(c, prog("e1", day(1)))
        repo.record(c, prog("e1", min(60), title = "Film"))
        repo.recordSeries(c, prog("e1", day(2)))
        val marks = repo.marks().first()
        assertEquals(Mark.REMINDER, marks[MarkKey("e1", day(1).toEpochMilli())])
        assertEquals(Mark.RECORDING, marks[MarkKey("e1", min(60).toEpochMilli())])
        assertEquals(Mark.RECORDING_SERIES, marks[MarkKey("e1", day(2).toEpochMilli())])
    }

    @Test fun mayNotFitComparesEstimateWithFreeSpace() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        free = null
        assertEquals(false, (repo.record(c, prog("e1", min(60))) as BookResult.Booked).mayNotFit)
        free = 1_000L
        assertEquals(true, (repo.record(c, prog("e1", min(120))) as BookResult.Booked).mayNotFit)
        free = UNKNOWN_BYTES_PER_HOUR * 10
        assertEquals(false, (repo.record(c, prog("e1", min(180))) as BookResult.Booked).mayNotFit)
        assertNotNull(db.recordDao().recordingFor(a.id, "c1", min(180)))
    }

    @Test fun mayNotFitIsWorkedOutOutsideTheBookingLock() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        // A read that books something itself would wait forever for a lock still held.
        freeRead = {
            repo.extend(-1, 30)
            1_000L
        }
        // Real time for the bound: the database answers on its own threads.
        val booked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { repo.record(c, prog("e1", min(60))) }
        assertEquals(true, (booked as BookResult.Booked).mayNotFit)
    }

    @Test fun aFreeSpaceReadThatNeverEndsCountsAsUnknown() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        // Ignores cancellation, as a blocking network call does.
        freeRead = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.CompletableDeferred<Unit>().await() }
            1_000L
        }
        val booked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repo.record(c, prog("e1", min(60))) as BookResult.Booked
        }
        assertEquals(false, booked.mayNotFit)
        assertNotNull(db.recordDao().recording(booked.id))
    }

    @Test fun rematchFollowsAChangedEndTime() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val id = (repo.record(c, prog("e1", day(1), title = "Film")) as BookResult.Booked).id
        refreshGuide(prog("e1", day(1), minutes = 45, title = "Film"))
        repo.rematchSeries()
        val row = db.recordDao().recording(id)!!
        assertEquals(day(1), row.start)
        assertEquals(day(1).plusSeconds(45 * 60), row.stop)
    }

    @Test fun aCancelledSeriesEpisodeCanBeBookedAgainByHand() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", day(1))
        refreshGuide(p, prog("e1", day(2)))
        repo.recordSeries(c, p)
        val first = recordings().minBy { it.start }
        repo.cancelRecording(first.id)
        repo.rematchSeries()
        assertEquals(RecordingState.SKIPPED, db.recordDao().recording(first.id)!!.state)

        val result = repo.record(c, p) as BookResult.Booked
        assertEquals(first.id, result.id)
        val row = db.recordDao().recording(first.id)!!
        assertEquals(RecordingState.SCHEDULED, row.state)
        assertEquals(io.github.loopholetv.data.db.RecordingReason.NONE, row.reason)
        assertEquals(2, recordings().size)
    }

    @Test fun aFailedFutureBookingIsRevivedButAStartedOneIsNot() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val future = (repo.record(c, prog("e1", day(1))) as BookResult.Booked).id
        db.recordDao().updateRecording(
            db.recordDao().recording(future)!!.copy(state = RecordingState.FAILED, reason = io.github.loopholetv.data.db.RecordingReason.ERROR),
        )
        assertTrue(repo.record(c, prog("e1", day(1))) is BookResult.Booked)
        assertEquals(RecordingState.SCHEDULED, db.recordDao().recording(future)!!.state)

        val past = (repo.record(c, prog("e1", min(-30))) as BookResult.Booked).id
        db.recordDao().updateRecording(db.recordDao().recording(past)!!.copy(state = RecordingState.MISSED))
        assertEquals(BookResult.AlreadyBooked, repo.record(c, prog("e1", min(-30))))
    }

    @Test fun aDismissedReminderCanBeSetAgain() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", day(1))
        refreshGuide(p)
        repo.remindSeries(c, p)
        val id = repo.upcomingReminders().first().single().id
        repo.cancelReminder(id)
        assertTrue(repo.upcomingReminders().first().isEmpty())
        assertEquals(id, repo.remind(c, p))
        val back = repo.upcomingReminders().first().single()
        assertNull(back.seriesId)
        assertNull(back.notifiedAt)
    }

    @Test fun remindSeriesAfterCancelSeriesBooksAgain() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", day(1))
        refreshGuide(p, prog("e1", day(2)))
        val old = repo.remindSeries(c, p)
        repo.cancelReminder(repo.upcomingReminders().first().minBy { it.start }.id)
        repo.cancelSeries(old)
        val rule = repo.remindSeries(c, p)
        val reminders = repo.upcomingReminders().first()
        assertEquals(2, reminders.size)
        assertTrue(reminders.all { it.seriesId == rule })
    }

    @Test fun recordSeriesOnAOneOffBookingAttachesTheSeries() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val p = prog("e1", day(1))
        val id = (repo.record(c, p) as BookResult.Booked).id
        val rule = repo.recordSeries(c, p)
        assertEquals(rule, db.recordDao().recording(id)!!.seriesId)
        assertEquals(1, recordings().size)
    }

    @Test fun clashesAreCountedByPeakConcurrencyNotByTotal() = runTest {
        val a = playlist("A", "http://a.test/l.m3u", connections = 2)
        val c1 = chan(a, "c1", "One", "e1")
        val c2 = chan(a, "c2", "Two", "e2")
        val c3 = chan(a, "c3", "Three", "e3")
        val c4 = chan(a, "c4", "Four", "e4")
        assertNull((repo.record(c1, prog("e1", day(1), minutes = 60)) as BookResult.Booked).clash)
        assertNull((repo.record(c2, prog("e2", day(1).plusSeconds(90 * 60), minutes = 60)) as BookResult.Booked).clash)
        // Overlaps both, but never more than two streams at once.
        assertNull((repo.record(c3, prog("e3", day(1), minutes = 180)) as BookResult.Booked).clash)
        // Three at once from 30 minutes in.
        val clash = (repo.record(c4, prog("e4", day(1).plusSeconds(30 * 60), minutes = 70)) as BookResult.Booked).clash
        assertNotNull(clash)
    }

    @Test fun aRematchBooksAtMostFiftyShowingsWithinFourteenDays() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        refreshGuide(*(0 until 60).map { prog("e1", now.plusSeconds(3600L + it * 5 * 3600L)) }.toTypedArray())
        val far = prog("e1", day(20))
        repo.recordSeries(c, far)
        val near = recordings().filter { it.start < day(15) }
        assertEquals(SERIES_MAX_PER_RULE, near.size)
        assertEquals(0, freeCalls)

        refreshGuide(prog("e1", day(13)), prog("e1", day(15)), prog("e1", day(16)))
        db.recordDao().deleteScheduledOf(listOf(a.id))
        repo.rematchSeries()
        assertEquals(listOf(day(13)), recordings().map { it.start })
    }

    @Test fun setPaddingAndChangeSourceUpdateTheRow() = runTest {
        val a = playlist("A", "http://a.test/l.m3u")
        val c = chan(a, "c1", "One", "e1")
        val id = (repo.record(c, prog("e1", min(60))) as BookResult.Booked).id
        repo.setPadding(id, 3, 10)
        repo.changeSource(id, 99L, "zz")
        val row = db.recordDao().recording(id)!!
        assertEquals(3, row.padBeforeMin)
        assertEquals(10, row.padAfterMin)
        assertEquals(99L, row.sourcePlaylistId)
        assertEquals("zz", row.sourceChannelKey)
        repo.changeSource(id, null, null)
        assertNull(db.recordDao().recording(id)!!.sourcePlaylistId)
    }
}
