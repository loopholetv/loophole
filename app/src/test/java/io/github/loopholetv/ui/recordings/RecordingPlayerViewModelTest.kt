package io.github.loopholetv.ui.recordings

import android.app.Application
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.record.LIVE_EDGE_BEHIND_MS
import io.github.loopholetv.record.RecordingsClient
import io.github.loopholetv.record.RemoteRecording
import io.github.loopholetv.ui.MainDispatcherRule
import io.github.loopholetv.ui.vod.VOD_TICK_MS
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RecordingPlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val dispatcher get() = main.dispatcher

    private val base = Instant.ofEpochSecond(2_000_000)
    private val minute = 60_000L
    private lateinit var db: AppDatabase

    private fun TestScope.openDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
    }

    @After fun tearDown() {
        main.clear()
        if (::db.isInitialized) db.close()
    }

    private class FakePlayback : Playback {
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state
        private val _mode = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = _mode
        private val _paused = MutableStateFlow(false)
        override val paused: StateFlow<Boolean> = _paused
        val opened = mutableListOf<Pair<VodPlayable, Long>>()
        var stops = 0
        var position = 0L

        override fun play(channel: io.github.loopholetv.data.db.ChannelEntity) = Unit
        override fun stop() { stops++; _mode.value = PlayMode.Live; _state.value = PlaybackState.Idle }
        override fun playVod(item: VodPlayable, startMs: Long) {
            opened += item to startMs
            position = 0
            _mode.value = PlayMode.Vod(item.ref, durationMs = null, ended = false)
            _state.value = PlaybackState.Buffering(VOD_CHANNEL_ID)
        }
        override fun pause() { _paused.value = true }
        override fun resume() { _paused.value = false }
        override fun positionMs() = position
        override fun seekWithin(positionMs: Long) { position = positionMs }

        fun playing(at: Long) {
            position = at
            _state.value = PlaybackState.Playing(VOD_CHANNEL_ID, StreamTags.NONE)
        }
        fun ended() { _mode.value = (_mode.value as PlayMode.Vod).copy(ended = true) }
        fun fail() { _state.value = PlaybackState.Error(VOD_CHANNEL_ID, "can't play") }
    }

    private class Rig(val playback: FakePlayback, val vm: RecordingPlayerViewModel, val events: MutableList<RecordingPlayerEvent>)

    private val client = RecordingsClient(OkHttpClient())

    private fun row(
        state: RecordingState = RecordingState.DONE,
        positionMs: Long = 0,
        startedAgoMin: Long = 60,
        endedAgoMin: Long? = 30,
        playlistId: Long = 1,
    ) = RecordingEntity(
        playlistId = playlistId, channelKey = "c1", channelName = "News", epgChannelId = null, title = "Show",
        subTitle = null, episodeKey = null, description = null,
        start = base.minusSeconds(3600), stop = base.plusSeconds(3600), padBeforeMin = 1, padAfterMin = 5,
        state = state, fileName = "r.ts", storageId = "mem", bytes = 10,
        startedAt = base.minusSeconds(startedAgoMin * 60),
        endedAt = endedAgoMin?.let { base.minusSeconds(it * 60) },
        positionMs = positionMs, createdAt = base,
    )

    private suspend fun insert(r: RecordingEntity): Long = db.recordDao().insertRecording(r)

    private fun TestScope.rig(
        id: Long,
        live: Boolean = false,
        peer: CastPeer? = null,
        url: String? = "http://127.0.0.1:1/rec/t/file/1",
        remote: List<RemoteRecording> = emptyList(),
        channelLocked: suspend (Long?, String?, String) -> Boolean = { _, _, _ -> false },
    ): Rig {
        val playback = FakePlayback()
        val vm = main.keep(
            RecordingPlayerViewModel(
                id, peer, live, db, client, playback,
                clock = { base.plusMillis(testScheduler.currentTime) },
                localUrl = { url },
                listRemote = { remote },
                channelLocked = channelLocked,
            ),
        )
        val events = mutableListOf<RecordingPlayerEvent>()
        backgroundScope.launch(dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        return Rig(playback, vm, events)
    }

    private fun TestScope.passTime(r: Rig, ms: Long) {
        repeat((ms / VOD_TICK_MS).toInt()) {
            advanceTimeBy(VOD_TICK_MS)
            r.vm.tick()
            runCurrent()
        }
    }

    @Test fun resumesAtSavedPosition() = runTest(dispatcher) {
        openDb()
        val id = insert(row(positionMs = 600_000))
        val r = rig(id)
        assertEquals(1, r.playback.opened.size)
        assertEquals(600_000L, r.playback.opened.single().second)
        assertEquals(VodKind.RECORDING, r.playback.opened.single().first.ref.kind)
        assertEquals("Show", r.playback.opened.single().first.title)
    }

    @Test fun liveStartsBehindEdge() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null, positionMs = 1_000))
        val r = rig(id, live = true)
        assertEquals(10 * minute - LIVE_EDGE_BEHIND_MS, r.playback.opened.single().second)
        assertTrue(r.vm.recording.value)
    }

    @Test fun endedWhileRecordingReplaysFromPosition() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id)
        r.playback.playing(at = 120_000)
        passTime(r, 500)
        r.playback.ended()
        passTime(r, 1_000)
        assertEquals("waits before reopening", 1, r.playback.opened.size)
        passTime(r, 1_500)
        assertEquals(2, r.playback.opened.size)
        assertEquals(120_000L, r.playback.opened.last().second)
        assertEquals(emptyList<RecordingPlayerEvent>(), r.events)
        assertEquals(0, r.playback.stops)
    }

    @Test fun aRewindAtTheLiveEdgeSurvivesTheReplay() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id)
        r.playback.playing(at = 120_000)
        passTime(r, 500)
        r.vm.onSeekKey(-1, 0)
        r.vm.onSeekRelease()
        r.playback.ended()
        passTime(r, 3_000)
        assertEquals(2, r.playback.opened.size)
        assertEquals(110_000L, r.playback.opened.last().second)
    }

    @Test fun aHeldSeekWaitsWhileTheReadIsReopening() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id)
        r.playback.playing(at = 120_000)
        passTime(r, 500)
        r.playback.fail()
        r.vm.onSeekKey(-1, 0)
        r.vm.onSeekRelease()
        passTime(r, 3_000)
        // Still pending, not lost: it lands once the reopened read plays.
        r.playback.playing(at = 120_000)
        passTime(r, 1_000)
        assertEquals(110_000L, r.playback.position)
    }

    @Test fun leavingByAnExternalPopStopsTheRead() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        val r = rig(id)
        r.playback.playing(at = 1_000)
        main.clear()
        assertEquals(1, r.playback.stops)
    }

    @Test fun refusedReadWhileRecordingReplaysToo() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id, live = false)
        r.playback.playing(at = 90_000)
        passTime(r, 500)
        r.playback.fail()
        passTime(r, 2_500)
        assertEquals(2, r.playback.opened.size)
        assertEquals(90_000L, r.playback.opened.last().second)
        assertNull(r.vm.error.value)
    }

    @Test fun repeatedFailuresWhileRecordingStopRetryingAndShowAnError() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id)
        repeat(200) {
            r.playback.fail()
            passTime(r, 2_500)
        }
        assertTrue(r.playback.opened.size < 100)
        assertEquals("can't play", r.vm.error.value)
    }

    @Test fun endedAfterDoneStops() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.DONE))
        val r = rig(id)
        r.playback.playing(at = 1_700_000)
        passTime(r, 500)
        r.playback.ended()
        passTime(r, 500)
        assertEquals(1, r.playback.opened.size)
        assertEquals(1, r.playback.stops)
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.Exit), r.events)
    }

    @Test fun liveFinishOpensLiveChannel() = runTest(dispatcher) {
        openDb()
        val p = db.addPlaylist()
        val ch = db.insertChannels(channel(p, "c1", 1)).getValue("c1")
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null, playlistId = p))
        val r = rig(id, live = true)
        r.playback.playing(at = 10 * minute - 5_000)
        passTime(r, 1_000)
        val done = db.recordDao().recording(id)!!
        db.recordDao().updateRecording(done.copy(state = RecordingState.DONE, endedAt = base.plusMillis(testScheduler.currentTime)))
        passTime(r, 1_500)
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.OpenLive(ch.id)), r.events)
    }

    @Test fun liveFinishFarBehindJustKeepsPlaying() = runTest(dispatcher) {
        openDb()
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "c1", 1))
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null, playlistId = p))
        val r = rig(id, live = true)
        r.playback.playing(at = 2 * minute)
        passTime(r, 1_000)
        val done = db.recordDao().recording(id)!!
        db.recordDao().updateRecording(done.copy(state = RecordingState.DONE, endedAt = base.plusMillis(testScheduler.currentTime)))
        passTime(r, 1_500)
        assertEquals(emptyList<RecordingPlayerEvent>(), r.events)
        assertFalse(r.vm.recording.value)
    }

    @Test fun savesPositionEveryTenSecondsAndOnBack() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        val r = rig(id)
        r.playback.playing(at = 5_000)
        passTime(r, 10_500)
        assertEquals(5_000L, db.recordDao().recording(id)!!.positionMs)
        r.playback.position = 42_000
        r.vm.onBack()
        runCurrent()
        assertEquals(42_000L, db.recordDao().recording(id)!!.positionMs)
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.Exit), r.events)
    }

    @Test fun remoteRecordingDoesNotSavePosition() = runTest(dispatcher) {
        openDb()
        val id = insert(row(positionMs = 7_000))
        val peer = CastPeer(
            id = "tv2", name = "TV2", host = "192.168.1.20", port = 8080, status = CastStatus.IDLE, accepts = true,
            castId = null, token = null, castStartedAtMs = null, lastSeenMs = 0, rec = "tok",
        )
        val listed = RemoteRecording(
            id = id, title = "Remote show", channel = "News", start = 0, stop = 1, state = "DONE", reason = "NONE",
            bytes = 10, recording = false, durationMs = 3_600_000,
        )
        val r = rig(id, peer = peer, remote = listOf(listed))
        assertEquals("Remote show", r.playback.opened.single().first.title)
        assertEquals("rec:tv2:$id", r.playback.opened.single().first.ref.key)
        assertEquals(0L, r.playback.opened.single().second)
        r.playback.playing(at = 30_000)
        passTime(r, 11_000)
        r.vm.onBack()
        runCurrent()
        assertEquals(7_000L, db.recordDao().recording(id)!!.positionMs)
    }

    @Test fun noUrlShowsAMessageAndBackLeaves() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        val r = rig(id, url = null)
        assertEquals(0, r.playback.opened.size)
        assertEquals("Can't play this recording right now.", r.vm.error.value)
        r.vm.onBack()
        runCurrent()
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.Exit), r.events)
    }

    @Test fun playingARecordingWritesNoVodProgress() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        val r = rig(id)
        r.playback.playing(at = 50_000)
        passTime(r, 11_000)
        r.vm.onBack()
        runCurrent()
        assertEquals(0, db.rowCount("vod_progress"))
    }

    @Test fun barTotalIsTheRecordedLengthSoFar() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        val r = rig(id)
        r.playback.playing(at = 60_000)
        passTime(r, 1_000)
        assertEquals("10:01", r.vm.bar.value!!.total)
        assertEquals("1:00", r.vm.bar.value!!.elapsed)
    }

    @Test fun lockedRecordingRefused() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        val asked = mutableListOf<Triple<Long?, String?, String>>()
        val r = rig(id, channelLocked = { p, k, n -> asked += Triple(p, k, n); true })
        assertEquals(listOf(Triple<Long?, String?, String>(1L, "c1", "News")), asked)
        assertTrue(r.playback.opened.isEmpty())
        assertEquals(CANT_PLAY_RECORDING, r.vm.error.value)
    }

    @Test fun lockedRemoteRecordingIsMatchedByChannelName() = runTest(dispatcher) {
        openDb()
        val peer = CastPeer(
            id = "tv2", name = "TV2", host = "192.168.1.20", port = 8080, status = CastStatus.IDLE, accepts = true,
            castId = null, token = null, castStartedAtMs = null, lastSeenMs = 0, rec = "tok",
        )
        val remote = RemoteRecording(
            id = 7, title = "Show", channel = "News", start = 0, stop = 1, state = "DONE", reason = "NONE",
            bytes = 10, recording = false, durationMs = 60_000,
        )
        val asked = mutableListOf<Triple<Long?, String?, String>>()
        val r = rig(7, peer = peer, remote = listOf(remote), channelLocked = { p, k, n -> asked += Triple(p, k, n); true })
        assertEquals(listOf(Triple<Long?, String?, String>(null, null, "News")), asked)
        assertTrue(r.playback.opened.isEmpty())
    }

    @Test fun returningFromHomeToALockedRecordingLeaves() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        var locked = false
        val r = rig(id, channelLocked = { _, _, _ -> locked })
        assertEquals(1, r.playback.opened.size)
        r.vm.onScreenStopped()
        r.playback.stop()
        locked = true

        r.vm.onScreenStarted()
        runCurrent()

        assertEquals(1, r.playback.opened.size)
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.Exit), r.events)
    }

    @Test fun retryOfALockedRecordingLeaves() = runTest(dispatcher) {
        openDb()
        val id = insert(row())
        var locked = false
        val r = rig(id, channelLocked = { _, _, _ -> locked })
        r.playback.fail()
        locked = true
        r.vm.retry()
        runCurrent()
        assertEquals(1, r.playback.opened.size)
        assertEquals(listOf<RecordingPlayerEvent>(RecordingPlayerEvent.Exit), r.events)
    }

    @Test fun relockWhileAGrowingRecordingPlaysKeepsReplaying() = runTest(dispatcher) {
        openDb()
        val id = insert(row(state = RecordingState.RECORDING, startedAgoMin = 10, endedAgoMin = null))
        var locked = false
        val r = rig(id, channelLocked = { _, _, _ -> locked })
        r.playback.playing(at = 120_000)
        passTime(r, 500)
        locked = true // the PIN timed out while watching full screen
        r.playback.ended()
        passTime(r, 3_500)
        assertEquals(2, r.playback.opened.size)
        assertEquals(emptyList<RecordingPlayerEvent>(), r.events)
    }
}
