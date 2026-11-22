package io.github.loopholetv.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RecordDaoTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.recordDao()
    private val t = Instant.parse("2030-01-01T20:00:00Z")

    @Before fun setUp() { db = inMemoryDb() }

    @After fun tearDown() { db.close() }

    private fun recording(
        playlistId: Long = 1,
        key: String = "k",
        start: Instant = t,
        seriesId: Long? = null,
        state: RecordingState = RecordingState.SCHEDULED,
    ) = RecordingEntity(
        playlistId = playlistId, channelKey = key, channelName = "Ch", epgChannelId = "e", title = "Show",
        subTitle = null, episodeKey = null, description = null, start = start, stop = start.plusSeconds(3600),
        padBeforeMin = 1, padAfterMin = 5, seriesId = seriesId, state = state, createdAt = t,
    )

    private fun reminder(playlistId: Long = 1, start: Instant = t, seriesId: Long? = null) = ReminderEntity(
        playlistId = playlistId, channelKey = "k", channelName = "Ch", epgChannelId = "e", title = "Show",
        subTitle = null, episodeKey = null, start = start, stop = start.plusSeconds(3600), seriesId = seriesId,
    )

    private fun series(playlistId: Long = 1) = SeriesRuleEntity(
        kind = SeriesKind.RECORD, playlistId = playlistId, channelKey = "k", channelName = "Ch",
        epgChannelId = "e", title = "Show", padBeforeMin = 1, padAfterMin = 5, createdAt = t,
    )

    @Test fun insertRecordingIgnoresDuplicateKey() = runTest {
        assertTrue(dao.insertRecording(recording()) > 0)
        assertEquals(-1L, dao.insertRecording(recording()))
        assertEquals(1, dao.observeRecordings().first().size)
    }

    @Test fun deleteSeriesRemovesFutureScheduledOnly() = runTest {
        val id = dao.upsertSeries(series())
        dao.insertRecording(recording(start = t.minusSeconds(86_400), seriesId = id, state = RecordingState.DONE))
        dao.insertRecording(recording(start = t.plusSeconds(86_400), seriesId = id))
        dao.insertRecording(recording(start = t.plusSeconds(2 * 86_400), seriesId = id))
        dao.upsertReminder(reminder(start = t.plusSeconds(3 * 86_400), seriesId = id))

        dao.deleteSeries(id)

        val left = dao.observeRecordings().first()
        assertEquals(listOf(RecordingState.DONE), left.map { it.state })
        assertTrue(dao.allSeries().isEmpty())
        assertTrue(dao.observeReminders(Instant.EPOCH).first().isEmpty())
    }

    @Test fun deletePendingForPlaylistsKeepsFinished() = runTest {
        dao.insertRecording(recording(start = t.plusSeconds(86_400)))
        dao.insertRecording(recording(start = t.minusSeconds(86_400), state = RecordingState.DONE))
        dao.insertRecording(recording(playlistId = 2, start = t.plusSeconds(86_400)))
        dao.upsertReminder(reminder())
        dao.upsertSeries(series())

        dao.deletePendingForPlaylists(listOf(1))

        val left = dao.observeRecordings().first()
        assertEquals(setOf(1L to RecordingState.DONE, 2L to RecordingState.SCHEDULED), left.map { it.playlistId to it.state }.toSet())
        assertTrue(dao.observeReminders(Instant.EPOCH).first().isEmpty())
        assertTrue(dao.allSeries().isEmpty())
    }

    @Test fun remindersDueWindow() = runTest {
        dao.upsertReminder(reminder(start = t.plusSeconds(60)))
        dao.upsertReminder(reminder(start = t.plusSeconds(20 * 60)))

        val due = dao.remindersDue(until = t.plusSeconds(120), after = t)

        assertEquals(listOf(t.plusSeconds(60)), due.map { it.start })
    }
}
