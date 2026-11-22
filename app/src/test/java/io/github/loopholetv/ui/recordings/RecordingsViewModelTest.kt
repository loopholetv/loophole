package io.github.loopholetv.ui.recordings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.SeriesRuleEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamResolver
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.record.Recorder
import io.github.loopholetv.record.RecordOutcome
import io.github.loopholetv.record.RecordSink
import io.github.loopholetv.record.RecordingRunner
import io.github.loopholetv.record.RecordingServiceControl
import io.github.loopholetv.record.RemoteRecording
import io.github.loopholetv.record.ScheduleRepository
import io.github.loopholetv.record.storage.LocalDirStorage
import io.github.loopholetv.record.storage.StorageFactory
import io.github.loopholetv.record.storage.StorageKind
import io.github.loopholetv.ui.MainDispatcherRule
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RecordingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-22T20:00:00Z")
    private val zone = ZoneOffset.UTC
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var storageDir: File
    private lateinit var local: LocalDirStorage
    private lateinit var storage: StorageFactory
    private lateinit var runner: RecordingRunner
    private lateinit var schedule: ScheduleRepository
    private val settings = MutableStateFlow(AppSettings(recordStorage = StorageKind.SMB))
    private val peers = MutableStateFlow<List<CastPeer>>(emptyList())
    private var playlist = 0L

    @Before fun setUp() = runBlocking {
        db = inMemoryDb()
        playlist = db.addPlaylist("A")
        storageDir = tmp.newFolder("rec")
        local = LocalDirStorage(storageDir, "NAS", "nas") { 212L * 1024 * 1024 * 1024 }
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = StorageFactory(context, { local }, { local })
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runner = RecordingRunner(
            db, storage, { settings.value }, NoResolver, NoRecorder, channelFor = { _, _ -> null },
            service = NoService, clock = { now }, zone = zone, scope = scope,
        )
        schedule = ScheduleRepository(db, { settings.value }, { now })
        Unit
    }

    @After fun tearDown() {
        main.clear()
        scope.cancel()
        db.close()
    }

    private fun vm(remote: suspend (CastPeer) -> List<RemoteRecording> = { emptyList() }) = main.keep(
        RecordingsViewModel(db, schedule, runner, storage, settings, peers, remote, { now }, zone),
    )

    private fun rec(
        id: Long = 0,
        title: String = "News",
        state: RecordingState = RecordingState.SCHEDULED,
        reason: RecordingReason = RecordingReason.NONE,
        startMin: Long = 60,
        seriesId: Long? = null,
        fileName: String? = null,
        storageId: String? = null,
        bytes: Long = 0,
    ) = RecordingEntity(
        id = id, playlistId = playlist, channelKey = "c1", channelName = "Channel 1", epgChannelId = null, title = title,
        subTitle = null, episodeKey = null, description = null,
        start = now.plusSeconds(startMin * 60), stop = now.plusSeconds((startMin + 30) * 60), padBeforeMin = 1, padAfterMin = 5,
        seriesId = seriesId, state = state, reason = reason, fileName = fileName, storageId = storageId, bytes = bytes,
        createdAt = now,
    )

    private fun reminder(id: Long = 0, title: String = "Film", startMin: Long = 30, seriesId: Long? = null) = ReminderEntity(
        id = id, playlistId = playlist, channelKey = "c1", channelName = "Channel 1", epgChannelId = null, title = title,
        subTitle = null, episodeKey = null, start = now.plusSeconds(startMin * 60), stop = now.plusSeconds((startMin + 60) * 60),
        seriesId = seriesId,
    )

    private fun rule(id: Long) = SeriesRuleEntity(
        id = id, kind = SeriesKind.RECORD, playlistId = playlist, channelKey = "c1", channelName = "Channel 1",
        epgChannelId = null, title = "Series", padBeforeMin = 1, padAfterMin = 5, createdAt = now,
    )

    private fun sections(
        rows: List<RecordingEntity>,
        reminders: List<ReminderEntity> = emptyList(),
        series: List<SeriesRuleEntity> = emptyList(),
        inGuide: (RecordingEntity) -> Boolean = { true },
        clashWith: (RecordingEntity) -> RecordingEntity? = { null },
    ) = recordingsSections(rows, reminders, series, inGuide, now, zone, clashWith = clashWith)

    // --- the pure sections ---

    @Test fun sectionsOrderAndSeriesCollapsed() {
        val clash = rec(1, "Clash", startMin = 50)
        val running = rec(2, "Running", RecordingState.RECORDING, startMin = -10)
        val ep1 = rec(3, "Ep", seriesId = 7, startMin = 600)
        val ep2 = rec(4, "Ep", seriesId = 7, startMin = 120)
        val single = rec(5, "Single", startMin = 300)
        val done = rec(6, "Done", RecordingState.DONE, startMin = -300)
        val failed = rec(7, "Failed", RecordingState.FAILED, startMin = -200)

        val result = sections(
            listOf(done, failed, single, ep1, ep2, running, clash), listOf(reminder(9)), listOf(rule(7)),
            clashWith = { if (it.id == 1L) running else null },
        )

        assertEquals(
            listOf(
                SectionKind.CLASHES, SectionKind.RECORDING_NOW, SectionKind.REMINDERS, SectionKind.SCHEDULED,
                SectionKind.DONE, SectionKind.NOT_COMPLETED,
            ),
            result.map { it.kind },
        )
        val scheduled = result.first { it.kind == SectionKind.SCHEDULED }.rows
        assertEquals(listOf(4L, 5L), scheduled.map { it.recording!!.id })
        assertEquals(7L, scheduled[0].series?.id)
    }

    @Test fun clashesFirst() {
        val running = rec(2, "Running", RecordingState.RECORDING, startMin = -10)
        val late = rec(3, "Late", startMin = 5)

        val result = sections(listOf(running, late), clashWith = { if (it.id == 3L) running else null })

        assertEquals(SectionKind.CLASHES, result.first().kind)
        val row = result.first().rows.single()
        assertEquals("Late", row.title)
        assertTrue("Clashes with Running" in row.notes)
        assertEquals("Scheduled", row.stateText)
        assertTrue(result.none { it.kind == SectionKind.SCHEDULED })
    }

    @Test fun stateTexts() {
        fun text(state: RecordingState, reason: RecordingReason = RecordingReason.NONE) = stateText(rec(state = state, reason = reason))
        assertEquals("Recording", text(RecordingState.RECORDING))
        assertEquals("Scheduled", text(RecordingState.SCHEDULED))
        assertEquals("Done", text(RecordingState.DONE))
        assertEquals("Done, with gaps", text(RecordingState.DONE, RecordingReason.GAPS))
        assertEquals("Started late", text(RecordingState.DONE, RecordingReason.STARTED_LATE))
        assertEquals("Stopped", text(RecordingState.STOPPED, RecordingReason.USER))
        assertEquals("Stopped: storage full", text(RecordingState.STOPPED, RecordingReason.STORAGE_FULL))
        assertEquals("Failed: provider busy", text(RecordingState.FAILED, RecordingReason.PROVIDER_BUSY))
        assertEquals("Failed: storage unreachable", text(RecordingState.FAILED, RecordingReason.STORAGE_UNREACHABLE))
        assertEquals("Failed", text(RecordingState.FAILED, RecordingReason.ERROR))
        assertEquals("Skipped", text(RecordingState.SKIPPED, RecordingReason.USER))
        assertEquals("Missed: clash", text(RecordingState.MISSED, RecordingReason.CLASH))
        assertEquals("Missed: TV was off", text(RecordingState.MISSED, RecordingReason.TV_OFF))
    }

    @Test fun notInGuideShown() {
        val gone = rec(1, "Gone", startMin = 90)
        val there = rec(2, "There", startMin = 120)

        val rows = sections(listOf(gone, there), inGuide = { it.id != 1L }).single().rows

        assertEquals(listOf(NOTE_NOT_IN_GUIDE), rows.first { it.title == "Gone" }.notes)
        assertTrue(rows.first { it.title == "There" }.notes.isEmpty())
    }

    @Test fun holdOkOffersOnlyWhatApplies() {
        fun actions(r: RecordingEntity, catchUp: Boolean = false) =
            rowActions(sections(listOf(r)).single().rows.single(), now, catchUp)

        assertEquals(
            listOf(RowAction.EDIT_PADDING, RowAction.CANCEL_RECORDING, RowAction.CHANGE_SOURCE),
            actions(rec(1)),
        )
        assertEquals(
            listOf(RowAction.PLAY, RowAction.KEEP_LAST, RowAction.DELETE),
            actions(rec(2, state = RecordingState.DONE, startMin = -300, fileName = "a.ts", seriesId = 3)),
        )
        // Over, but the programme is still on and the archive can supply it.
        val failed = rec(3, state = RecordingState.FAILED, startMin = -10)
        assertEquals(listOf(RowAction.RETRY, RowAction.DELETE), actions(failed))
        assertEquals(listOf(RowAction.RETRY, RowAction.RECORD_FROM_CATCH_UP, RowAction.DELETE), actions(failed, catchUp = true))
        // Failed part-way: what it wrote can be played.
        assertEquals(
            listOf(RowAction.PLAY, RowAction.DELETE),
            actions(rec(7, state = RecordingState.FAILED, startMin = -300, fileName = "b.ts", storageId = "s", bytes = 5)),
        )
        assertEquals(
            listOf(RowAction.DELETE),
            actions(rec(8, state = RecordingState.FAILED, startMin = -300, fileName = "c.ts", storageId = "s", bytes = 0)),
        )
        // Over: no live retry.
        assertEquals(listOf(RowAction.DELETE), actions(rec(4, state = RecordingState.MISSED, startMin = -300)))
        assertEquals(
            listOf(RowAction.RECORD_FROM_CATCH_UP, RowAction.DELETE),
            actions(rec(5, state = RecordingState.MISSED, startMin = -300), catchUp = true),
        )
        val series = rec(6, seriesId = 3)
        assertTrue(RowAction.CANCEL_SERIES in actions(series) && RowAction.KEEP_LAST in actions(series))
    }

    @Test fun sizeAndFreeText() {
        assertEquals("Free: 212 GB (NAS)", freeText(212L * 1024 * 1024 * 1024, "NAS"))
        assertEquals("4.5 GB", formatSize(4_831_838_208L))
        assertEquals("630 MB", formatSize(630L * 1024 * 1024))
        assertEquals("Free: unknown (USB drive)", freeText(null, "USB drive"))
    }

    // --- the view model ---

    @Test fun uiShowsSectionsAndFreeSpace() = runTest {
        db.recordDao().insertRecording(rec(title = "Later", startMin = 120))
        db.recordDao().upsertReminder(reminder(title = "Film"))

        val ui = vm().ui.first { it.loaded && it.freeText != null }

        assertEquals(listOf(SectionKind.REMINDERS, SectionKind.SCHEDULED), ui.sections.map { it.kind })
        assertEquals("Free: 212 GB (NAS)", ui.freeText)
        assertFalse(ui.storageMissing)
    }

    @Test fun deleteCallsRunner() = runTest {
        local.openAppend("a.ts").use { it.write(ByteArray(10)) }
        val id = db.recordDao().insertRecording(
            rec(state = RecordingState.DONE, startMin = -300, fileName = "a.ts", storageId = "nas", bytes = 10),
        )
        val vm = vm()
        vm.ui.first { it.loaded }

        vm.delete(id)
        vm.ui.first { it.sections.isEmpty() }

        assertNull(db.recordDao().recording(id))
        assertNull(local.size("a.ts"))
        assertNull(vm.ui.value.removePrompt)
    }

    @Test fun deleteOfAnUnreachableFileOffersToRemoveTheRow() = runTest {
        val id = db.recordDao().insertRecording(
            rec(state = RecordingState.DONE, startMin = -300, fileName = "a.ts", storageId = "elsewhere", bytes = 10),
        )
        val vm = vm()
        vm.ui.first { it.loaded }

        vm.delete(id)
        assertEquals(id, vm.ui.first { it.removePrompt != null }.removePrompt)
        assertNotNull(db.recordDao().recording(id))

        vm.removeFromList(id)
        vm.ui.first { it.sections.isEmpty() }
        assertNull(db.recordDao().recording(id))
        assertNull(vm.ui.value.removePrompt)
    }

    @Test fun deleteOnAStorageThatThrowsOffersToRemoveTheRow() = runTest {
        val broken = object : io.github.loopholetv.record.storage.RecordingStorage {
            override val id = "nas"
            override val label = "NAS"
            override suspend fun test() = io.github.loopholetv.record.storage.StorageResult.Ok
            override suspend fun freeBytes(): Long? = null
            override suspend fun openAppend(name: String): java.io.OutputStream = throw java.io.IOException("unreachable")
            override suspend fun size(name: String): Long? = throw java.io.IOException("unreachable")
            override suspend fun openRead(name: String, offset: Long): java.io.InputStream = throw java.io.IOException("unreachable")
            override suspend fun delete(name: String): Boolean = throw java.io.IOException("unreachable")
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        val brokenFactory = StorageFactory(context, { broken }, { broken })
        val brokenRunner = RecordingRunner(
            db, brokenFactory, { settings.value }, NoResolver, NoRecorder, channelFor = { _, _ -> null },
            service = NoService, clock = { now }, zone = zone, scope = scope,
        )
        val id = db.recordDao().insertRecording(
            rec(state = RecordingState.DONE, startMin = -300, fileName = "a.ts", storageId = "nas", bytes = 10),
        )
        val vm = main.keep(RecordingsViewModel(db, schedule, brokenRunner, brokenFactory, settings, peers, { emptyList() }, { now }, zone))
        vm.ui.first { it.loaded }

        vm.delete(id)

        assertEquals(id, vm.ui.first { it.removePrompt != null }.removePrompt)
        assertNotNull(db.recordDao().recording(id))
    }

    @Test fun aFailingActionShowsAMessageAndDoesNotCrash() = runTest {
        val failing = ScheduleRepository(db, { settings.value }, { now }, onChanged = { throw java.io.IOException("disk") })
        val id = db.recordDao().insertRecording(rec(startMin = 90))
        val vm = main.keep(RecordingsViewModel(db, failing, runner, storage, settings, peers, { emptyList() }, { now }, zone, noticeTime = { kotlinx.coroutines.awaitCancellation() }))
        vm.ui.first { it.loaded }

        vm.cancelRecording(id)

        assertEquals("Couldn't change the recording.", vm.ui.first { it.message != null }.message)
        assertTrue(vm.ui.value.loaded)
    }

    @Test fun otherTvsListed() = runTest {
        val withRecordings = peer("tv2", "Bedroom", rec = "secret")
        val without = peer("tv3", "Kitchen", rec = null)
        peers.value = listOf(withRecordings, without)

        val vm = vm(remote = { listOf(remote(1), remote(2)) })
        val ui = vm.ui.first { it.otherTvs.singleOrNull()?.count != null }

        assertEquals(listOf(OtherTv(withRecordings, 2)), ui.otherTvs)
    }

    @Test fun openPeerListsItsRecordings() = runTest {
        val tv = peer("tv2", "Bedroom", rec = "secret")
        peers.value = listOf(tv)
        val vm = vm(remote = { listOf(remote(7, "Remote show")) })
        vm.ui.first { it.otherTvs.isNotEmpty() }

        vm.openPeer(tv)
        val listing = vm.ui.first { it.peerListing != null }.peerListing!!

        assertEquals(tv, listing.peer)
        assertEquals(listOf("Remote show"), listing.items.map { it.title })
        vm.closePeer()
        assertNull(vm.ui.value.peerListing)
    }

    @Test fun anUnreachablePeerShowsAMessage() = runTest {
        val tv = peer("tv2", "Bedroom", rec = "secret")
        val vm = main.keep(
            RecordingsViewModel(db, schedule, runner, storage, settings, peers, { throw java.io.IOException("x") }, { now }, zone, noticeTime = { kotlinx.coroutines.awaitCancellation() }),
        )
        vm.ui.first { it.loaded }

        vm.openPeer(tv)

        assertEquals("Couldn't reach that TV.", vm.ui.first { it.message != null }.message)
    }

    @Test fun storageMissingShowsSetup() = runTest {
        settings.value = AppSettings(recordStorage = StorageKind.NONE)

        val ui = vm().ui.first { it.loaded }

        assertTrue(ui.storageMissing)
        assertTrue(ui.sections.isEmpty())
        assertNull(ui.freeText)
    }

    @Test fun notInGuideIsComputedFromTheCommittedProgrammes() = runTest {
        val a = db.recordDao().insertRecording(rec(title = "Gone", startMin = 90).copy(epgChannelId = "e1"))

        val ui = vm().ui.first { it.loaded }

        val row = ui.sections.single().rows.single()
        assertEquals(a, row.recording!!.id)
        assertTrue(NOTE_NOT_IN_GUIDE in row.notes)
    }

    // --- helpers ---

    private fun peer(id: String, name: String, rec: String?) = CastPeer(
        id = id, name = name, host = "192.168.1.20", port = 8080, status = CastStatus.IDLE, accepts = true,
        castId = null, token = null, castStartedAtMs = null, lastSeenMs = 0, rec = rec,
    )

    private fun remote(id: Long, title: String = "Show") = RemoteRecording(
        id = id, title = title, channel = "News", start = 0, stop = 1, state = "DONE", reason = "NONE",
        bytes = 10, recording = false, durationMs = 3_600_000,
    )

    private object NoRecorder : Recorder(OkHttpClient()) {
        override suspend fun record(
            open: suspend () -> ResolvedStream,
            until: () -> Long,
            openSink: suspend () -> RecordSink,
            keepAlive: suspend () -> Unit,
            onFormat: (String) -> Unit,
            onDropped: (clean: Boolean) -> Unit,
        ): RecordOutcome = RecordOutcome.Finished(0, 0)
    }

    private object NoResolver : StreamResolver {
        override suspend fun resolve(channel: ChannelEntity): ResolvedStream = ResolvedStream("http://x.example.test", "", emptyMap())
        override suspend fun keepAlive(channel: ChannelEntity) = Unit
        override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? = null
    }

    private object NoService : RecordingServiceControl {
        override fun started(id: Long, title: String) = Unit
        override fun ended(id: Long) = Unit
    }
}
