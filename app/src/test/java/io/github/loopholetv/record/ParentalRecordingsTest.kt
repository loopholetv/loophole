package io.github.loopholetv.record

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.addPlaylist
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
import io.github.loopholetv.ui.MainDispatcherRule
import io.github.loopholetv.ui.recordings.RecordingsViewModel
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recording lists keep locked channels out of sight while the PIN is not entered, here and on other TVs. */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ParentalRecordingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-22T20:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var storage: StorageFactory
    private lateinit var runner: RecordingRunner
    private lateinit var schedule: ScheduleRepository
    private val settings = MutableStateFlow(AppSettings(recordStorage = StorageKind.SMB))
    private val peers = MutableStateFlow<List<CastPeer>>(emptyList())
    private val locked = MutableStateFlow(true)
    private var playlist = 0L

    @Before fun setUp() = runBlocking {
        db = inMemoryDb()
        playlist = db.addPlaylist("A")
        val local = LocalDirStorage(tmp.newFolder("rec"), "NAS", "nas") { 1L shl 40 }
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = StorageFactory(context, { local }, { local })
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runner = RecordingRunner(
            db, storage, { settings.value }, NoResolver, NoRecorder, channelFor = { _, _ -> null },
            service = NoService, clock = { now }, zone = ZoneOffset.UTC, scope = scope,
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
        RecordingsViewModel(db, schedule, runner, storage, settings, peers, remote, { now }, ZoneOffset.UTC, lockedNow = locked),
    )

    private fun rec(channelKey: String, title: String, channelName: String = channelKey) = RecordingEntity(
        playlistId = playlist, channelKey = channelKey, channelName = channelName, epgChannelId = null, title = title,
        subTitle = null, episodeKey = null, description = null, start = now.plusSeconds(3600), stop = now.plusSeconds(5400),
        padBeforeMin = 1, padAfterMin = 5, state = RecordingState.DONE, reason = RecordingReason.NONE,
        fileName = "$title.ts", storageId = "nas", bytes = 10, createdAt = now,
    )

    private suspend fun lock(key: String) = db.channelPrefsDao().upsert(ChannelPrefsEntity(playlist, key, locked = true))

    private fun titles(ui: io.github.loopholetv.ui.recordings.RecordingsUi) =
        ui.sections.flatMap { it.rows }.mapNotNull { it.recording?.title }

    @Test fun ownRecordingsOfLockedChannelHidden() = runTest {
        lock("c1")
        db.recordDao().insertRecording(rec("c1", "Secret"))
        db.recordDao().insertRecording(rec("c2", "Open"))
        val vm = vm()

        assertEquals(listOf("Open"), titles(vm.ui.first { it.loaded && titles(it).isNotEmpty() }))
        locked.value = false
        assertEquals(setOf("Secret", "Open"), titles(vm.ui.first { titles(it).size == 2 }).toSet())
    }

    @Test fun remoteLockedHiddenAndOldPeerMatchedByName() = runTest {
        db.insertChannelsNamed(playlist, "c1" to "Secret Channel", "c2" to "Open Channel")
        lock("c1")
        val tv = CastPeer(
            id = "tv2", name = "Bedroom", host = "192.168.1.20", port = 8080, status = CastStatus.IDLE, accepts = true,
            castId = null, token = null, castStartedAtMs = null, lastSeenMs = 0, rec = "secret",
        )
        peers.value = listOf(tv)
        fun remote(id: Long, channel: String, marked: Boolean?) = RemoteRecording(
            id = id, title = "T$id", channel = channel, start = 0, stop = 1, state = "DONE", reason = "NONE",
            bytes = 10, recording = false, durationMs = 1, locked = marked == true, lockKnown = marked != null,
        )
        val listed = listOf(
            remote(1, "Anything", marked = true), // the serving TV marked it
            remote(2, "Anything", marked = false), // marked as open: trusted
            remote(3, "Secret Channel", marked = null), // an older TV: matched by name against this TV's locks
            remote(4, "Open Channel", marked = null),
            remote(5, "Secret Channel", marked = false), // marked open by the peer, but locked here by name
        )
        val vm = vm(remote = { listed })

        vm.ui.first { it.otherTvs.singleOrNull()?.count != null }.let { assertEquals(2, it.otherTvs.single().count) }
        vm.openPeer(tv)
        assertEquals(listOf(2L, 4L), vm.ui.first { it.peerListing != null }.peerListing!!.items.map { it.id })

        locked.value = false
        vm.openPeer(tv)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), vm.ui.first { it.peerListing?.items?.size == 5 }.peerListing!!.items.map { it.id })
    }

    @Test fun routeMarksLockedRecordings() {
        val rows = listOf(rec("c1", "Secret").copy(id = 1), rec("c2", "Open").copy(id = 2))
        val server = LocalHttpServer(scope)
        val lockedKeys = setOf("c1")
        val route = RecordingsRoute(
            recordings = { rows }, recording = { id -> rows.firstOrNull { it.id == id } }, storageFor = { null },
            token = "tok", channelLocked = { it.channelKey in lockedKeys },
        )
        route.install(server)
        val port = server.start(listOf(InetAddress.getByName("127.0.0.1")))
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        try {
            val body = http.newCall(Request.Builder().url("http://127.0.0.1:$port/r/tok/list").build()).execute()
                .use { it.body.string() }
            val parsed = RemoteRecording.parseList(body)!!
            assertEquals(listOf(1L to true, 2L to false), parsed.map { it.id to it.locked })
            assertTrue(parsed.all { it.lockKnown })
            assertTrue(""""locked":true""" in body && """"locked":false""" in body)
        } finally {
            server.stop()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun oldListWithoutTheFieldIsNotMarkedKnown() {
        val json = """[{"id":1,"title":"t","channel":"c","start":0,"stop":1,"state":"DONE","reason":"NONE","bytes":1,"recording":false,"durationMs":1}]"""
        val parsed = RemoteRecording.parseList(json)!!.single()
        assertEquals(false, parsed.lockKnown)
        assertEquals(false, parsed.locked)
    }

    private suspend fun AppDatabase.insertChannelsNamed(p: Long, vararg keys: Pair<String, String>) {
        insertChannels(*keys.mapIndexed { i, (k, n) -> channel(p, k, i + 1, name = n) }.toTypedArray())
    }

    private object NoRecorder : Recorder(OkHttpClient()) {
        override suspend fun record(
            open: suspend () -> ResolvedStream,
            until: () -> Long,
            openSink: suspend () -> io.github.loopholetv.record.RecordSink,
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
