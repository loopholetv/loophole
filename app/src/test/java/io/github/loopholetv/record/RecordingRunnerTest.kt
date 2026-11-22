package io.github.loopholetv.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.SeriesRuleEntity
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamResolver
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.record.storage.LocalDirStorage
import io.github.loopholetv.record.storage.StorageFactory
import io.github.loopholetv.record.storage.StorageKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RecordingRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var runner: RecordingRunner
    private lateinit var storageDir: File
    private lateinit var local: LocalDirStorage
    private var now = Instant.parse("2026-11-22T20:00:00Z")
    private var settings = AppSettings(recordStorage = StorageKind.SMB)
    private val recorder = FakeRecorder()
    private val resolver = FakeResolver()
    private val service = FakeService()
    private val pauses = mutableListOf<Long>()
    private val channels = mutableMapOf<Pair<Long, String>, ChannelEntity>()
    private val channelLookups = CopyOnWriteArrayList<Pair<Long, String>>()
    private var playlistA = 0L
    private var playlistB = 0L

    @Before fun setUp() = runBlocking {
        db = inMemoryDb()
        storageDir = tmp.newFolder("rec")
        local = LocalDirStorage(storageDir, "Test", "test") { null }
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = StorageFactory(context, { local }, { local })
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playlistA = addPlaylist("A", "http://a.example.test/list.m3u")
        playlistB = addPlaylist("B", "http://b.example.test/list.m3u")
        runner = RecordingRunner(
            db, storage, { settings }, resolver, recorder,
            channelFor = { p, k -> channelLookups += p to k; channels[p to k] },
            service = service, clock = { now }, zone = ZoneOffset.UTC, scope = scope,
            elapsed = { 1_000L }, pause = { pauses += it; now = now.plusMillis(it) },
        )
        Unit
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    // --- outcomes ---

    @Test fun successfulRunIsDone() = runBlocking {
        val id = book()
        recorder.script = { writes(1000); RecordOutcome.Finished(1000, 0) }
        runner.begin(id)
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.DONE, r.state)
        assertEquals(RecordingReason.NONE, r.reason)
        assertEquals(1000L, r.bytes)
        assertEquals("test", r.storageId)
        assertTrue(r.fileName!!.endsWith(".ts"))
        assertEquals(1000L, File(File(storageDir, "Loophole Recordings"), r.fileName!!).length())
        assertEquals(now, r.startedAt)
        assertNotNull(r.endedAt)
        assertEquals(listOf("started $id", "ended $id"), service.events)
        assertTrue(runner.active.value.isEmpty())
    }

    @Test fun gapsGiveDoneWithGaps() = runBlocking {
        val id = book()
        recorder.script = { writes(500); RecordOutcome.Finished(500, 2) }
        runner.begin(id)
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.DONE, r.state)
        assertEquals(RecordingReason.GAPS, r.reason)
        assertEquals(2, r.gaps)
    }

    @Test fun lateStartKeepsStartedLateWhenThereAreNoGaps() = runBlocking {
        val id = book(startOffsetMin = -10)
        recorder.script = { writes(10); RecordOutcome.Finished(10, 0) }
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(RecordingState.DONE, row(id).state)
        assertEquals(RecordingReason.STARTED_LATE, row(id).reason)
    }

    @Test fun refusedIsProviderBusy() = runBlocking {
        val id = book()
        recorder.script = { RecordOutcome.Refused(503) }
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(RecordingState.FAILED, row(id).state)
        assertEquals(RecordingReason.PROVIDER_BUSY, row(id).reason)
    }

    @Test fun noStorageFailsAfterRetryWindow() = runBlocking {
        settings = AppSettings(recordStorage = StorageKind.NONE)
        val id = book()
        runner.begin(id)
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.FAILED, r.state)
        assertEquals(RecordingReason.STORAGE_UNREACHABLE, r.reason)
        assertTrue(pauses.sum() >= STORAGE_RETRY_MS)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun storageFullKeepsBytes() = runBlocking {
        val id = book()
        recorder.script = { writes(700); RecordOutcome.StorageFull }
        runner.begin(id)
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.STOPPED, r.state)
        assertEquals(RecordingReason.STORAGE_FULL, r.reason)
        assertEquals(700L, r.bytes)
    }

    @Test fun storageErrorIsStoppedError() = runBlocking {
        val id = book()
        recorder.script = { writes(20); RecordOutcome.StorageError("x") }
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(RecordingState.STOPPED, row(id).state)
        assertEquals(RecordingReason.ERROR, row(id).reason)
        assertEquals(20L, row(id).bytes)
    }

    @Test fun failedAfterBytesKeepsTheFile() = runBlocking {
        val id = book()
        recorder.script = { writes(64); RecordOutcome.Failed("x") }
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(RecordingState.FAILED, row(id).state)
        assertEquals(RecordingReason.ERROR, row(id).reason)
        assertEquals(64L, row(id).bytes)
        assertTrue(File(File(storageDir, "Loophole Recordings"), row(id).fileName!!).exists())
    }

    @Test fun nameCollisionGetsSuffix() = runBlocking {
        val id = book()
        val expected = io.github.loopholetv.record.storage.recordingFileName(
            "News One", "News", row(id).start, ZoneOffset.UTC, "ts",
        )
        local.openAppend(expected).use { it.write(1) }
        recorder.script = { writes(5); RecordOutcome.Finished(5, 0) }
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(expected.removeSuffix(".ts") + " (2).ts", row(id).fileName)
    }

    @Test fun everyOutcomeLeavesTheRecordingState() = runBlocking {
        val outcomes = listOf<RecordOutcome>(
            RecordOutcome.Finished(0, 0), RecordOutcome.Refused(null), RecordOutcome.StorageFull,
            RecordOutcome.StorageError("x"), RecordOutcome.Failed("x"),
        )
        outcomes.forEachIndexed { i, outcome ->
            val id = book(startOffsetMin = 1 + i * 60)
            recorder.script = { outcome }
            runner.begin(id)
            runner.awaitIdle()
            assertNotEquals("$outcome", RecordingState.RECORDING, row(id).state)
            assertNotEquals("$outcome", RecordingState.SCHEDULED, row(id).state)
            assertNotNull(row(id).endedAt)
        }
    }

    @Test fun anUnexpectedExceptionIsFailedError() = runBlocking {
        val id = book()
        recorder.script = { writes(30); throw IllegalStateException("boom") }
        runner.begin(id)
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.FAILED, r.state)
        assertEquals(RecordingReason.ERROR, r.reason)
        assertEquals(30L, r.bytes)
        assertEquals(listOf("started $id", "ended $id"), service.events)
        assertTrue(runner.active.value.isEmpty())
    }

    @Test fun missedByTheClockIsNotRecorded() = runBlocking {
        val id = book(startOffsetMin = -120)
        runner.begin(id)
        runner.awaitIdle()
        assertEquals(RecordingState.MISSED, row(id).state)
        assertEquals(RecordingReason.TV_OFF, row(id).reason)
        assertEquals(0, recorder.calls.get())
    }

    // --- stop, cancel, concurrency ---

    @Test fun stopWritesStoppedAndKeepsTheBytes() = runBlocking {
        val id = book()
        val running = CompletableDeferred<Unit>()
        recorder.script = { writes(256); running.complete(Unit); awaitCancellation() }
        runner.begin(id)
        withTimeout(10_000) { running.await() }
        assertEquals(RecordingState.RECORDING, row(id).state)
        runner.stop(id)
        val r = row(id)
        assertEquals(RecordingState.STOPPED, r.state)
        assertEquals(RecordingReason.USER, r.reason)
        assertEquals(256L, r.bytes)
        assertNotNull(r.endedAt)
        assertTrue(runner.active.value.isEmpty())
        assertEquals(listOf("started $id", "ended $id"), service.events)
    }

    @Test fun cancellingTheScopeStillSettlesTheRow() = runBlocking {
        val id = book()
        val running = CompletableDeferred<Unit>()
        recorder.script = { writes(8); running.complete(Unit); awaitCancellation() }
        runner.begin(id)
        withTimeout(10_000) { running.await() }
        scope.cancel()
        withTimeout(10_000) { while (row(id).state == RecordingState.RECORDING) kotlinx.coroutines.delay(20) }
        assertEquals(RecordingState.STOPPED, row(id).state)
        assertEquals(8L, row(id).bytes)
    }

    @Test fun beginForAnActiveRecordingIsANoOp() = runBlocking {
        val id = book()
        val gate = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        recorder.script = { running.complete(Unit); gate.await(); RecordOutcome.Finished(0, 0) }
        runner.begin(id)
        withTimeout(10_000) { running.await() }
        runner.begin(id)
        runner.begin(id)
        assertEquals(1, runner.active.value.size)
        gate.complete(Unit)
        runner.awaitIdle()
        assertEquals(1, recorder.calls.get())
        assertEquals(1, service.events.count { it.startsWith("started") })
    }

    @Test fun clashFallbackRecordsFromSourcePlaylist() = runBlocking {
        val own = channelOf(playlistA, "a1", "News One")
        val source = channelOf(playlistB, "b1", "News One")
        val id = book(sourcePlaylistId = playlistB, sourceChannelKey = "b1")
        val seen = CompletableDeferred<Map<Long, ActiveRecording>>()
        val gate = CompletableDeferred<Unit>()
        recorder.script = { call ->
            call.open()
            call.keepAlive()
            seen.complete(runner.active.value)
            gate.await()
            RecordOutcome.Finished(0, 0)
        }
        runner.begin(id)
        val active = withTimeout(10_000) { seen.await() }
        assertEquals(source.id, active.getValue(id).channelId)
        assertEquals("the booked channel, not the source", own.id, active.getValue(id).bookedChannelId)
        assertEquals("b.example.test", active.getValue(id).host)
        assertEquals(listOf(source.id), resolver.resolved.toList())
        assertEquals(listOf(source.id), resolver.keptAlive.toList())
        assertNotEquals(own.id, source.id)
        assertEquals(playlistB to "b1", channelLookups.first())
        gate.complete(Unit)
        runner.awaitIdle()
    }

    // --- recover ---

    @Test fun recoverResumesStillOnProgrammeIntoSameFile() = runBlocking {
        val name = "News One - News - 2026-11-22 2000.ts"
        local.openAppend(name).use { it.write(ByteArray(100)) }
        val id = book(
            state = RecordingState.RECORDING, startOffsetMin = -10, fileName = name, storageId = "test",
        )
        val gate = CompletableDeferred<Unit>()
        recorder.script = { writes(50, format = null); gate.await(); RecordOutcome.Finished(50, 0) }
        runner.recover()
        runner.recover() // idempotent: a second call while it runs starts nothing new
        withTimeout(10_000) { while (recorder.calls.get() < 1) kotlinx.coroutines.delay(10) }
        runner.recover()
        gate.complete(Unit)
        runner.awaitIdle()
        assertEquals(1, recorder.calls.get())
        val r = row(id)
        assertEquals(RecordingState.DONE, r.state)
        assertEquals(RecordingReason.GAPS, r.reason)
        assertEquals(1, r.gaps)
        assertEquals(name, r.fileName)
        assertEquals(150L, r.bytes)
        assertEquals(150L, File(File(storageDir, "Loophole Recordings"), name).length())
    }

    @Test fun recoverClosesEndedAsStopped() = runBlocking {
        val name = "old.ts"
        local.openAppend(name).use { it.write(ByteArray(123)) }
        val id = book(
            state = RecordingState.RECORDING, startOffsetMin = -200, fileName = name, storageId = "test",
        )
        runner.recover()
        runner.recover()
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.STOPPED, r.state)
        assertEquals(123L, r.bytes)
        assertNotNull(r.endedAt)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun recoverMarksMissedWhenTvWasOff() = runBlocking {
        val id = book(startOffsetMin = -200)
        runner.recover()
        runner.awaitIdle()
        assertEquals(RecordingState.MISSED, row(id).state)
        assertEquals(RecordingReason.TV_OFF, row(id).reason)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun recoverStartsALateBooking() = runBlocking {
        val id = book(startOffsetMin = -10)
        val future = book(startOffsetMin = 120, title = "Later")
        recorder.script = { writes(5); RecordOutcome.Finished(5, 0) }
        runner.recover()
        runner.awaitIdle()
        assertEquals(RecordingState.DONE, row(id).state)
        assertEquals(RecordingReason.STARTED_LATE, row(id).reason)
        assertEquals(RecordingState.SCHEDULED, row(future).state)
    }

    // --- retry, delete, keep-last ---

    @Test fun retryFromArchiveUsesArchiveUrl() = runBlocking {
        val ch = channelOf(playlistA, "a1", "News One", archive = true)
        val id = book(
            state = RecordingState.FAILED, startOffsetMin = -180, lengthMin = 60, reason = RecordingReason.ERROR,
        )
        val original = row(id)
        val opened = CompletableDeferred<ResolvedStream>()
        recorder.script = { call -> opened.complete(call.open()); writes(40); RecordOutcome.Finished(40, 0) }
        runner.retry(id, fromArchive = true)
        runner.awaitIdle()
        assertEquals("http://archive.example.test/x", withTimeout(10_000) { opened.await() }.url)
        val (forChannel, request) = resolver.archives.single()
        assertEquals(ch.id, forChannel)
        assertEquals(ArchiveRequest(original.start, original.stop), request)
        val fresh = db.recordDao().inState(RecordingState.DONE).single()
        assertNotEquals(id, fresh.id)
        assertEquals(now, fresh.start)
        assertEquals(original.start, fresh.archiveFrom)
        assertEquals(original.stop, fresh.archiveUntil)
        assertEquals(RecordingState.FAILED, row(id).state)
        assertEquals(0, resolver.resolved.size)
    }

    @Test fun retryFromArchiveDoesNothingWhenNotPlayable() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = false)
        val id = book(state = RecordingState.FAILED, startOffsetMin = -180)
        runner.retry(id, fromArchive = true)
        runner.awaitIdle()
        assertEquals(1, db.recordDao().observeRecordings().first().size)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun retryLiveBooksANewRowFromNow() = runBlocking {
        channelOf(playlistA, "a1", "News One")
        val id = book(state = RecordingState.FAILED, startOffsetMin = -5, lengthMin = 60, reason = RecordingReason.ERROR)
        recorder.script = { call -> call.open(); writes(9); RecordOutcome.Finished(9, 0) }
        runner.retry(id, fromArchive = false)
        runner.awaitIdle()
        val fresh = db.recordDao().inState(RecordingState.DONE).single()
        assertNotEquals(id, fresh.id)
        assertEquals(now, fresh.start)
        assertEquals(row(id).title, fresh.title)
        assertEquals(RecordingReason.NONE, fresh.reason)
        assertEquals(1, resolver.resolved.size)
        assertTrue(resolver.archives.isEmpty())
    }

    @Test fun deleteRemovesFileAndRow() = runBlocking {
        val name = "gone.ts"
        local.openAppend(name).use { it.write(ByteArray(10)) }
        val id = book(state = RecordingState.DONE, startOffsetMin = -200, fileName = name, storageId = "test")
        runner.delete(id)
        assertNull(local.size(name))
        assertNull(db.recordDao().recording(id))
    }

    @Test fun keepLastTrimsTheSeriesAfterARun() = runBlocking {
        val rule = db.recordDao().upsertSeries(
            SeriesRuleEntity(
                kind = SeriesKind.RECORD, playlistId = playlistA, channelKey = "a1", channelName = "News One",
                epgChannelId = null, title = "News", keepLast = 1, padBeforeMin = 1, padAfterMin = 5, createdAt = now,
            ),
        )
        val oldName = "old.ts"
        local.openAppend(oldName).use { it.write(1) }
        val old = book(
            state = RecordingState.DONE, startOffsetMin = -1000, fileName = oldName, storageId = "test", seriesId = rule,
            bytes = 1,
        )
        val empty = book(
            state = RecordingState.STOPPED, startOffsetMin = -500, fileName = "empty.ts", storageId = "test",
            seriesId = rule,
        )
        val id = book(seriesId = rule)
        recorder.script = { writes(5); RecordOutcome.Finished(5, 0) }
        runner.begin(id)
        runner.awaitIdle()
        assertNull(db.recordDao().recording(old))
        assertNull(local.size(oldName))
        assertNotNull(db.recordDao().recording(empty))
        assertEquals(RecordingState.DONE, row(id).state)
    }

    // --- fix round: catch-up runs, gate, extend ---

    private suspend fun archiveRow(startedAt: Instant, state: RecordingState = RecordingState.RECORDING, name: String? = "arch.ts"): Long {
        val from = now.minusSeconds(7200)
        return book(
            state = state, startOffsetMin = -1, lengthMin = 60, fileName = name, storageId = "test",
            startedAt = startedAt, archiveFrom = from, archiveUntil = from.plusSeconds(3600),
        )
    }

    @Test fun recoverResumesACatchUpRunFromTheArchiveNeverLive() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        local.openAppend("arch.ts").use { it.write(ByteArray(10)) }
        val id = archiveRow(startedAt = now)
        recorder.script = { call -> call.open(); writes(5, format = null); RecordOutcome.Finished(5, 0) }
        runner.recover()
        runner.awaitIdle()
        assertEquals(0, resolver.resolved.size)
        val r = row(id)
        assertEquals(ArchiveRequest(r.archiveFrom!!, r.archiveUntil!!), resolver.archives.single().second)
        assertEquals(RecordingState.DONE, r.state)
        assertEquals(15L, r.bytes)
    }

    @Test fun anArchiveDropMidProgrammeReopensFromTheOffset() = runBlocking {
        val ch = channelOf(playlistA, "a1", "News One", archive = true)
        val id = archiveRow(startedAt = now, state = RecordingState.SCHEDULED, name = null)
        val from = row(id).archiveFrom!!
        recorder.script = { call ->
            call.open()
            writes(10)
            now = now.plusSeconds(600)
            call.onDropped(false) // a broken connection
            call.open()
            writes(10, format = null)
            RecordOutcome.Finished(20, 1)
        }
        runner.begin(id, fromArchive = true)
        runner.awaitIdle()
        assertEquals(listOf(from, from.plusSeconds(600)), resolver.archives.map { it.second.from })
        assertEquals(ch.id, resolver.archives.first().first)
        assertEquals(RecordingReason.GAPS, row(id).reason)
        assertEquals(1, row(id).gaps)
    }

    @Test fun reopeningAfterTheWholeProgrammeEndsTheArchiveRun() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        val id = archiveRow(startedAt = now, state = RecordingState.SCHEDULED, name = null)
        var failure: Throwable? = null
        var until = -1L
        recorder.script = { call ->
            call.open()
            writes(10)
            now = now.plusSeconds(3600 - 30)
            call.onDropped(false)
            failure = runCatching { call.open() }.exceptionOrNull()
            until = call.until()
            RecordOutcome.Finished(10, 0)
        }
        runner.begin(id, fromArchive = true)
        runner.awaitIdle()
        assertTrue(failure is java.io.IOException)
        assertEquals(0L, until)
        assertEquals(1, resolver.archives.size)
    }

    @Test fun aFastArchiveThatEndsCleanlyIsDoneWithoutAReopen() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        val id = archiveRow(startedAt = now, state = RecordingState.SCHEDULED, name = null)
        var until = -1L
        recorder.script = { call ->
            call.open()
            writes(10)
            now = now.plusSeconds(10) // the whole programme arrived in ten seconds
            call.onDropped(true)
            until = call.until()
            RecordOutcome.Finished(10, 0)
        }
        runner.begin(id, fromArchive = true)
        runner.awaitIdle()
        assertEquals(0L, until)
        assertEquals(1, resolver.archives.size)
        assertEquals(RecordingState.DONE, row(id).state)
        assertEquals(0, row(id).gaps)
        assertEquals(RecordingReason.NONE, row(id).reason)
    }

    @Test fun aRecoveredCatchUpRunPastItsLengthIsDone() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        local.openAppend("arch.ts").use { it.write(ByteArray(10)) }
        val id = archiveRow(startedAt = now.minusSeconds(3590))
        recorder.script = { call ->
            runCatching { call.open() }
            RecordOutcome.Failed("x")
        }
        runner.recover()
        runner.awaitIdle()
        assertEquals(RecordingState.DONE, row(id).state)
        assertEquals(10L, row(id).bytes)
        assertEquals(0, resolver.archives.size)
    }

    @Test fun recoverStartsAScheduledCatchUpRowOnlyWhileTheArchiveHoldsIt() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        val gated = CopyOnWriteArrayList<Long>()
        runner.runGate = { id, _ -> gated += id }
        val old = now.minusSeconds(3 * 86_400)
        val playable = book(startOffsetMin = -300, archiveFrom = now.minusSeconds(7200), archiveUntil = now.minusSeconds(3600))
        val gone = book(startOffsetMin = -301, archiveFrom = old.minusSeconds(8 * 86_400), archiveUntil = old.minusSeconds(8 * 86_400 - 3600))
        runner.recover()
        assertEquals(listOf(playable), gated.toList())
        assertEquals(RecordingState.MISSED, row(gone).state)
    }

    @Test fun aFirstRefusalOfTheArchiveIsProviderBusy() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        val id = archiveRow(startedAt = now, state = RecordingState.SCHEDULED, name = null)
        recorder.script = { call -> call.open(); call.open(); call.open(); RecordOutcome.Refused(503) }
        runner.begin(id, fromArchive = true)
        runner.awaitIdle()
        assertEquals(RecordingState.FAILED, row(id).state)
        assertEquals(RecordingReason.PROVIDER_BUSY, row(id).reason)
        assertEquals(3, resolver.archives.size)
    }

    @Test fun recoverAndRetryStartRunsThroughTheGate() = runBlocking {
        channelOf(playlistA, "a1", "News One", archive = true)
        val gated = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        runner.runGate = { id, archive -> gated += id to archive }
        val late = book(startOffsetMin = -10)
        val resumable = archiveRow(startedAt = now)
        runner.recover()
        assertEquals(setOf(late to false, resumable to true), gated.toSet())
        gated.clear()
        val failed = book(state = RecordingState.FAILED, startOffsetMin = -180, lengthMin = 60)
        runner.retry(failed, fromArchive = true)
        now = now.plusSeconds(1) // a retry row starts now: two at the same instant would be one showing
        val live = book(state = RecordingState.FAILED, startOffsetMin = -20, lengthMin = 60)
        runner.retry(live, fromArchive = false)
        assertEquals(2, gated.size)
        assertEquals(listOf(true, false), gated.map { it.second })
        assertEquals(0, recorder.calls.get())
    }

    @Test fun liveRetryOfAnEndedProgrammeDoesNothing() = runBlocking {
        val id = book(state = RecordingState.FAILED, startOffsetMin = -180, lengthMin = 60)
        runner.retry(id, fromArchive = false)
        runner.awaitIdle()
        assertEquals(1, db.recordDao().observeRecordings().first().size)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun extendingMidRunMovesTheDeadlineAtOnce() = runBlocking {
        val id = book()
        val before = CompletableDeferred<Long>()
        val after = CompletableDeferred<Long>()
        recorder.script = { call ->
            before.complete(call.until())
            withTimeout(10_000) { while (call.until() <= before.getCompleted()) kotlinx.coroutines.delay(10) }
            after.complete(call.until())
            RecordOutcome.Finished(0, 0)
        }
        runner.begin(id)
        val first = withTimeout(10_000) { before.await() }
        db.recordDao().updateRecording(row(id).let { it.copy(stop = it.stop.plusSeconds(1800)) })
        val moved = withTimeout(10_000) { after.await() }
        assertEquals(1_800_000L, moved - first)
        runner.awaitIdle()
    }

    @Test fun resumeWithoutAFileStartsANewOne() = runBlocking {
        val id = book(state = RecordingState.RECORDING, startOffsetMin = -10)
        recorder.script = { writes(7); RecordOutcome.Finished(7, 0) }
        runner.recover()
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.DONE, r.state)
        assertNotNull(r.fileName)
        assertEquals(0, r.gaps)
        assertEquals(7L, r.bytes)
    }

    @Test fun resumeWithUnreachableStorageIsStoppedWithItsBytes() = runBlocking {
        local.openAppend("part.ts").use { it.write(ByteArray(33)) }
        val id = book(
            state = RecordingState.RECORDING, startOffsetMin = -10, fileName = "part.ts", storageId = "test", bytes = 20,
        )
        val broken = File(storageDir, "Loophole Recordings")
        // A file where the folder should be makes the storage's own test fail, so the 33 bytes can't be read either.
        broken.renameTo(File(storageDir, "moved"))
        File(storageDir, "Loophole Recordings").writeText("x")
        runner.recover()
        runner.awaitIdle()
        val r = row(id)
        assertEquals(RecordingState.STOPPED, r.state)
        assertEquals(20L, r.bytes)
        assertEquals(0, recorder.calls.get())
    }

    @Test fun deleteKeepsTheRowWhenItsStorageIsGone() = runBlocking {
        val id = book(state = RecordingState.DONE, startOffsetMin = -200, fileName = "x.ts", storageId = "elsewhere")
        assertEquals(false, runner.delete(id))
        assertNotNull(db.recordDao().recording(id))
    }

    // --- helpers ---

    private suspend fun addPlaylist(name: String, url: String): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name, type = PlaylistType.M3U, url = url, xtreamServer = null, xtreamUser = null,
            xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
        ),
    )

    private suspend fun channelOf(playlist: Long, key: String, name: String, archive: Boolean? = null): ChannelEntity {
        var c = db.insertChannels(channel(playlist, key, 1, name = name, epgChannelId = "e")).getValue(key)
        if (archive == true) {
            c = c.copy(catchupType = "default", catchupDays = 7, catchupSource = "http://c.example.test/{utc}.ts")
        }
        resolver.archiveUrl = "http://archive.example.test/x"
        resolver.archiveAvailable = archive == true
        channels[playlist to key] = c
        return c
    }

    private suspend fun row(id: Long): RecordingEntity = db.recordDao().recording(id)!!

    /** A booking whose programme starts [startOffsetMin] minutes from now (the default is on time, the first minute of padding). */
    private suspend fun book(
        state: RecordingState = RecordingState.SCHEDULED,
        startOffsetMin: Int = 1,
        lengthMin: Int = 30,
        title: String = "News",
        reason: RecordingReason = RecordingReason.NONE,
        fileName: String? = null,
        storageId: String? = null,
        seriesId: Long? = null,
        sourcePlaylistId: Long? = null,
        sourceChannelKey: String? = null,
        bytes: Long = 0,
        startedAt: Instant? = null,
        archiveFrom: Instant? = null,
        archiveUntil: Instant? = null,
    ): Long {
        if (channels[playlistA to "a1"] == null) channelOf(playlistA, "a1", "News One")
        val start = now.plusSeconds(startOffsetMin * 60L)
        return db.recordDao().insertRecording(
            RecordingEntity(
                playlistId = playlistA, channelKey = "a1", channelName = "News One", epgChannelId = "e", title = title,
                subTitle = null, episodeKey = null, description = null, start = start,
                stop = start.plusSeconds(lengthMin * 60L), padBeforeMin = 1, padAfterMin = 5, seriesId = seriesId,
                sourcePlaylistId = sourcePlaylistId, sourceChannelKey = sourceChannelKey, state = state,
                reason = reason, fileName = fileName, storageId = storageId, createdAt = now, bytes = bytes,
                startedAt = startedAt, archiveFrom = archiveFrom, archiveUntil = archiveUntil,
            ),
        )
    }

    private class Call(
        val open: suspend () -> ResolvedStream,
        val until: () -> Long,
        val openSink: suspend () -> RecordSink,
        val keepAlive: suspend () -> Unit,
        val onFormat: (String) -> Unit,
        val onDropped: (Boolean) -> Unit,
    ) {
        /** Writes [n] bytes the way the real recorder does: format first, then the sink. */
        suspend fun writes(n: Int, format: String? = "ts") {
            format?.let(onFormat)
            val sink = openSink()
            sink.write(ByteArray(n), 0, n)
            sink.close()
        }
    }

    private class FakeRecorder : Recorder(OkHttpClient()) {
        val calls = AtomicInteger()
        var script: suspend Call.(Call) -> RecordOutcome = { RecordOutcome.Finished(0, 0) }

        override suspend fun record(
            open: suspend () -> ResolvedStream,
            until: () -> Long,
            openSink: suspend () -> RecordSink,
            keepAlive: suspend () -> Unit,
            onFormat: (String) -> Unit,
            onDropped: (clean: Boolean) -> Unit,
        ): RecordOutcome {
            calls.incrementAndGet()
            val call = Call(open, until, openSink, keepAlive, onFormat, onDropped)
            return call.script(call)
        }
    }

    private class FakeResolver : StreamResolver {
        val resolved = CopyOnWriteArrayList<Long>()
        val keptAlive = CopyOnWriteArrayList<Long>()
        val archives = CopyOnWriteArrayList<Pair<Long, ArchiveRequest>>()
        var archiveUrl = ""
        var archiveAvailable = false

        override suspend fun resolve(channel: ChannelEntity): ResolvedStream {
            resolved += channel.id
            return ResolvedStream("http://live.example.test/${channel.key}", "", emptyMap())
        }

        override suspend fun keepAlive(channel: ChannelEntity) {
            keptAlive += channel.id
        }

        override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? {
            archives += channel.id to request
            return if (archiveAvailable) ResolvedStream(archiveUrl, "", emptyMap()) else null
        }
    }

    private class FakeService : RecordingServiceControl {
        val events = CopyOnWriteArrayList<String>()
        override fun started(id: Long, title: String) { events += "started $id" }
        override fun ended(id: Long) { events += "ended $id" }
    }
}
