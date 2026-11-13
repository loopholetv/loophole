package io.github.loopholetv.ui.vod

import androidx.media3.common.Player
import io.github.loopholetv.data.db.VodProgressDao
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.vod.SAVE_INTERVAL_MS
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class VodPlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val ref = VodRef(VodKind.MOVIE, 1, "film/1")
    private val item = VodPlayable(ref, "The Film", "http://example.test/f.mp4", null, null)
    private val hour = 3_600_000L

    private class FakeProgressDao : VodProgressDao {
        val upserts = mutableListOf<VodProgressEntity>()
        override fun observe(kind: VodKind, playlistId: Long, key: String): Flow<VodProgressEntity?> = emptyFlow()
        override fun observeForShow(kind: VodKind, playlistId: Long, showKey: String): Flow<List<VodProgressEntity>> = emptyFlow()
        override suspend fun get(kind: VodKind, playlistId: Long, key: String) = upserts.lastOrNull()
        override suspend fun upsert(row: VodProgressEntity) { upserts += row }
        override suspend fun rekey(kind: VodKind, p: Long, from: String, to: String) = Unit
        override suspend fun deleteOrphans(before: Instant) = Unit
        override suspend fun deleteForPlaylist(p: Long) = Unit
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
        val seeks = mutableListOf<Long>()
        var stops = 0
        var position = 0L

        override fun play(channel: io.github.loopholetv.data.db.ChannelEntity) = Unit
        override fun stop() { stops++; _state.value = PlaybackState.Idle }
        override fun playVod(item: VodPlayable, startMs: Long) {
            opened += item to startMs
            // The real player reports 0 until the new stream has loaded.
            position = 0
            _mode.value = PlayMode.Vod(item.ref, durationMs = null, ended = false)
            _state.value = PlaybackState.Buffering(VOD_CHANNEL_ID)
        }
        override fun pause() { _paused.value = true }
        override fun resume() { _paused.value = false }
        override fun positionMs() = position
        override fun seekWithin(positionMs: Long) { seeks += positionMs; position = positionMs }

        fun playing(durationMs: Long?, at: Long = position) {
            position = at
            val vod = _mode.value as PlayMode.Vod
            _mode.value = vod.copy(durationMs = durationMs)
            _state.value = PlaybackState.Playing(VOD_CHANNEL_ID, StreamTags.NONE)
        }
        fun ended() { _mode.value = (_mode.value as PlayMode.Vod).copy(ended = true) }
        fun fail() { _state.value = PlaybackState.Error(VOD_CHANNEL_ID, "The Film: can't play this stream") }
    }

    private class Rig(val playback: FakePlayback, val dao: FakeProgressDao, val vm: VodPlayerViewModel, val events: MutableList<VodPlayerEvent>)

    /** Lets [ms] of virtual time pass, ticking the view model as the screen does. */
    private fun TestScope.passTime(r: Rig, ms: Long) {
        repeat((ms / VOD_TICK_MS).toInt()) {
            advanceTimeBy(VOD_TICK_MS)
            r.vm.tick()
            runCurrent()
        }
    }

    private fun TestScope.rig(startMs: Long = 0, playable: VodPlayable? = item): Rig {
        val playback = FakePlayback()
        val dao = FakeProgressDao()
        val progress = VodProgressRepository(dao) { Instant.ofEpochSecond(1_000_000) }
        val vm = VodPlayerViewModel(ref, startMs, playback, { playable }, progress) { testScheduler.currentTime }
        val events = mutableListOf<VodPlayerEvent>()
        backgroundScope.launch(dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        return Rig(playback, dao, vm, events)
    }

    @Test fun retryThenBackBeforeItLoadsKeepsTheLastPosition() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 42_000)
        runCurrent()
        r.playback.fail()
        runCurrent()
        r.vm.retry()
        runCurrent()
        assertEquals(0L, r.playback.positionMs())
        r.vm.onBack()
        runCurrent()
        assertEquals(listOf(42_000L), r.dao.upserts.map { it.positionMs })
    }

    @Test fun backgroundedReplayThenBackBeforeItLoadsKeepsTheLastPosition() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 90_000)
        runCurrent()
        passTime(r, 250)
        r.vm.onScreenStopped()
        r.playback.stop()
        runCurrent()
        r.vm.onScreenStarted()
        runCurrent()
        assertEquals(item to 90_000L, r.playback.opened.last())
        assertEquals(0L, r.playback.positionMs())
        r.vm.onBack()
        runCurrent()
        assertTrue(r.dao.upserts.all { it.positionMs == 90_000L })
        assertEquals(90_000L, r.dao.upserts.last().positionMs)
    }

    @Test fun endWithUnknownLengthKeepsThePositionAndIsNotWatched() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(null, at = 5_000_000)
        runCurrent()
        r.playback.ended()
        passTime(r, 500)
        val row = r.dao.upserts.single()
        assertEquals(false, row.watched)
        assertEquals(5_000_000L, row.positionMs)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit), r.events)
    }

    @Test fun endWellShortOfTheLengthKeepsThePositionAndIsNotWatched() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 20 * 60_000L)
        runCurrent()
        r.playback.ended()
        passTime(r, 500)
        val row = r.dao.upserts.single()
        assertEquals(false, row.watched)
        assertEquals(20 * 60_000L, row.positionMs)
        assertEquals(hour, row.durationMs)
        assertEquals(1, r.playback.stops)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit), r.events)
    }

    @Test fun backBeforeTheItemLoadsNeverStartsPlayback() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<VodPlayable?>()
        val playback = FakePlayback()
        val progress = VodProgressRepository(FakeProgressDao()) { Instant.ofEpochSecond(1_000_000) }
        val vm = VodPlayerViewModel(ref, 0, playback, { gate.await() }, progress) { testScheduler.currentTime }
        runCurrent()
        vm.onBack()
        gate.complete(item)
        runCurrent()
        assertTrue(playback.opened.isEmpty())
    }

    @Test fun startsAtGivenPosition() = runTest(dispatcher) {
        val r = rig(startMs = 95_000)
        assertEquals(listOf(item to 95_000L), r.playback.opened)
    }

    @Test fun savesEveryTenSeconds() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 40_000)
        runCurrent()
        passTime(r, SAVE_INTERVAL_MS - 500)
        assertTrue(r.dao.upserts.isEmpty())
        r.playback.position = 50_000
        passTime(r, SAVE_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(50_000L), r.dao.upserts.map { it.positionMs })
        r.playback.position = 60_000
        passTime(r, SAVE_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(50_000L, 60_000L), r.dao.upserts.map { it.positionMs })
    }

    @Test fun pausedPlaybackIsNotSavedOnTheTimerButPausingSaves() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 70_000)
        runCurrent()
        r.vm.onPlayPause()
        runCurrent()
        assertEquals(listOf(70_000L), r.dao.upserts.map { it.positionMs })
        passTime(r, SAVE_INTERVAL_MS * 3)
        assertEquals(1, r.dao.upserts.size)
        r.vm.onPlayPause()
        assertEquals(false, r.playback.paused.value)
    }

    @Test fun savesOnBackAndExits() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 125_000)
        runCurrent()
        r.vm.onBack()
        runCurrent()
        assertEquals(listOf(125_000L), r.dao.upserts.map { it.positionMs })
        assertEquals(1, r.playback.stops)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit), r.events)
    }

    @Test fun backBeforeAnythingPlayedKeepsTheSavedPosition() = runTest(dispatcher) {
        val r = rig(startMs = 600_000)
        r.vm.onBack()
        runCurrent()
        assertTrue(r.dao.upserts.isEmpty())
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit), r.events)
    }

    @Test fun screenStopSavesPosition() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 80_000)
        runCurrent()
        r.vm.onScreenStopped()
        runCurrent()
        assertEquals(listOf(80_000L), r.dao.upserts.map { it.positionMs })
        assertEquals(0, r.playback.stops)
    }

    @Test fun endMarksWatchedAndExits() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = hour - 1_000)
        runCurrent()
        r.playback.ended()
        passTime(r, 500)
        runCurrent()
        val row = r.dao.upserts.single()
        assertTrue(row.watched)
        assertEquals(0L, row.positionMs)
        assertEquals(hour, row.durationMs)
        assertEquals(1, r.playback.stops)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit), r.events)
    }

    @Test fun seekBatchesThenCommits() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 600_000)
        runCurrent()
        r.vm.onSeekKey(1, 0)
        r.vm.onSeekKey(1, 1)
        r.vm.onSeekKey(1, 2)
        assertEquals("+1:30", r.vm.bar.value!!.pendingLabel)
        r.vm.onSeekRelease()
        passTime(r, 250)
        assertTrue(r.playback.seeks.isEmpty())
        passTime(r, 1_000)
        assertEquals(listOf(690_000L), r.playback.seeks)
        assertNull(r.vm.bar.value!!.pendingLabel)
    }

    @Test fun seekIsKeptInsideTheFilm() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 5_000)
        runCurrent()
        r.vm.onSeekKey(-1, 0)
        r.vm.onSeekRelease()
        passTime(r, 1_500)
        assertEquals(listOf(0L), r.playback.seeks)
    }

    @Test fun errorShowsRetryAndRetryResumes() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(hour, at = 42_000)
        runCurrent()
        r.playback.fail()
        runCurrent()
        assertEquals("The Film: can't play this stream", r.vm.error.value)
        r.vm.retry()
        runCurrent()
        assertEquals(item to 42_000L, r.playback.opened.last())
        assertNull(r.vm.error.value)
    }

    @Test fun missingItemShowsAnError() = runTest(dispatcher) {
        val r = rig(playable = null)
        assertTrue(r.playback.opened.isEmpty())
        assertTrue(r.vm.error.value != null)
    }

    @Test fun aspectCyclesForTheSessionAndOptionsListIt() = runTest(dispatcher) {
        val r = rig()
        assertEquals(AspectMode.FIT, r.vm.aspect.value)
        r.vm.options().single { it.label.startsWith("Aspect ratio") }.onClick()
        assertEquals(AspectMode.STRETCH, r.vm.aspect.value)
        repeat(AspectMode.entries.size - 1) { r.vm.cycleAspect() }
        assertEquals(AspectMode.FIT, r.vm.aspect.value)
    }
}
