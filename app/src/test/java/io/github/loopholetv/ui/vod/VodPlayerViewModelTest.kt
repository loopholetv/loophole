package io.github.loopholetv.ui.vod

import androidx.media3.common.Player
import io.github.loopholetv.data.db.VodProgressDao
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.vod.COUNTDOWN_SECONDS
import io.github.loopholetv.data.vod.NEXT_PILL_HIDE_MS
import io.github.loopholetv.data.vod.SAVE_INTERVAL_MS
import io.github.loopholetv.data.vod.STILL_WATCHING_TIMEOUT_MS
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class VodPlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val dispatcher get() = main.dispatcher

    private val ref = VodRef(VodKind.MOVIE, 1, "film/1")
    private val item = VodPlayable(ref, "The Film", "http://example.test/f.mp4", null, null)
    private val hour = 3_600_000L

    private class FakeProgressDao : VodProgressDao {
        val upserts = mutableListOf<VodProgressEntity>()
        override fun observe(kind: VodKind, playlistId: Long, key: String): Flow<VodProgressEntity?> = emptyFlow()
        override fun observeForShow(kind: VodKind, playlistId: Long, showKey: String): Flow<List<VodProgressEntity>> = emptyFlow()
        /** While set, reads of this key wait for it: holds a start in flight. */
        var gateKey: String? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun get(kind: VodKind, playlistId: Long, key: String): VodProgressEntity? {
            if (key == gateKey) gate.await()
            return upserts.lastOrNull { it.kind == kind && it.playlistId == playlistId && it.key == key }
        }
        fun last(key: String) = upserts.last { it.key == key }
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
        override fun stop() { stops++; _mode.value = PlayMode.Live; _state.value = PlaybackState.Idle }
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

    private fun episode(n: Int) = VodRef(VodKind.EPISODE, 1, "e$n")
    private fun episodeItem(r: VodRef) = VodPlayable(r, "Episode ${r.key}", "http://example.test/${r.key}.mp4", null, null)

    /** Episodes e1 to e6 of one show, in order; e6 is the last. */
    private val nextOf: suspend (VodRef) -> VodRef? = { r ->
        val n = r.key.removePrefix("e").toIntOrNull()
        if (r.kind == VodKind.EPISODE && n != null && n < 6) episode(n + 1) else null
    }

    private fun TestScope.rig(
        startMs: Long = 0,
        playable: VodPlayable? = item,
        start: VodRef = ref,
        autoPlay: Boolean = true,
        dao: FakeProgressDao = FakeProgressDao(),
        beforePlay: suspend (VodRef) -> Boolean = { true },
        next: suspend (VodRef) -> VodRef? = nextOf,
        prefetchNext: suspend (VodRef) -> Unit = {},
    ): Rig {
        val playback = FakePlayback()
        val progress = VodProgressRepository(dao) { Instant.ofEpochSecond(1_000_000) }
        val items: suspend (VodRef) -> VodPlayable? = { r -> if (r.kind == VodKind.EPISODE) episodeItem(r) else playable }
        val vm = main.keep(
            VodPlayerViewModel(
                start, startMs, playback, items, progress,
                next = next,
                label = { "Next: ${it.key}" },
                autoPlay = { autoPlay },
                beforePlay = beforePlay,
                prefetchNext = prefetchNext,
            ) { testScheduler.currentTime },
        )
        val events = mutableListOf<VodPlayerEvent>()
        backgroundScope.launch(dispatcher) { vm.events.collect { events += it } }
        runCurrent()
        return Rig(playback, dao, vm, events)
    }

    @Test fun filmOpensOnlyOnceTheRecordingLetsGo() = runTest(dispatcher) {
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val asked = mutableListOf<VodRef>()
        val r = rig(startMs = 5_000, beforePlay = { asked += it; answer.await() })
        assertEquals(listOf(ref), asked)
        assertEquals(emptyList<Pair<VodPlayable, Long>>(), r.playback.opened)
        answer.complete(true)
        runCurrent()
        assertEquals(listOf(item to 5_000L), r.playback.opened)
    }

    @Test fun filmHeldBackOpensNothing() = runTest(dispatcher) {
        val r = rig(beforePlay = { false })
        assertEquals(emptyList<Pair<VodPlayable, Long>>(), r.playback.opened)
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

    @Test fun unknownLengthEndRecordsEnd() = runTest(dispatcher) {
        val r = rig()
        r.playback.playing(null, at = 5_000_000)
        passTime(r, 10_000)
        assertEquals(5_000_000L, r.dao.upserts.last().positionMs)
        r.playback.ended()
        passTime(r, 500)
        val row = r.dao.upserts.last()
        assertEquals(false, row.watched)
        assertEquals(0L, row.positionMs)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), r.events)
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
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), r.events)
    }

    @Test fun backBeforeTheItemLoadsNeverStartsPlayback() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<VodPlayable?>()
        val playback = FakePlayback()
        val progress = VodProgressRepository(FakeProgressDao()) { Instant.ofEpochSecond(1_000_000) }
        val vm = main.keep(VodPlayerViewModel(ref, 0, playback, { gate.await() }, progress, { null }, { "" }, { true }) { testScheduler.currentTime })
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
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), r.events)
    }

    @Test fun backBeforeAnythingPlayedKeepsTheSavedPosition() = runTest(dispatcher) {
        val r = rig(startMs = 600_000)
        r.vm.onBack()
        runCurrent()
        assertTrue(r.dao.upserts.isEmpty())
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), r.events)
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
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), r.events)
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

    // Auto-play run.

    /** Plays the current episode to its natural end at [at] of an hour, then lets the end be seen. */
    private fun TestScope.endEpisode(r: Rig, at: Long = hour - 1_000, durationMs: Long? = hour) {
        r.playback.playing(durationMs, at = at)
        runCurrent()
        r.playback.ended()
        passTime(r, VOD_TICK_MS)
    }

    /** Ends the current episode and lets the countdown run out. */
    private fun TestScope.endAndCountDown(r: Rig) {
        endEpisode(r)
        passTime(r, COUNTDOWN_SECONDS * 1_000L)
    }

    private fun Rig.openedKeys() = playback.opened.map { it.first.ref.key }

    @Test fun endOfEpisodeShowsCountdownThenStartsNext() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        assertEquals(AutoNextUi.Countdown("Next: e2", COUNTDOWN_SECONDS), r.vm.autoNext.value)
        assertEquals(1, r.playback.stops)
        assertTrue(r.dao.last("e1").watched)
        passTime(r, 5_000)
        assertEquals(AutoNextUi.Countdown("Next: e2", COUNTDOWN_SECONDS - 5), r.vm.autoNext.value)
        assertEquals(listOf("e1"), r.openedKeys())
        passTime(r, 5_000)
        assertEquals(episodeItem(episode(2)) to 0L, r.playback.opened.last())
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertTrue(r.events.isEmpty())
    }

    @Test fun cutShortEndExitsWithoutCountdown() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r, at = 20 * 60_000L)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertEquals(20 * 60_000L, r.dao.last("e1").positionMs)
        passTime(r, 15_000)
        assertEquals(listOf("e1"), r.openedKeys())
    }

    @Test fun unknownLengthEndStillCountsDown() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        r.playback.playing(null, at = 5_000_000)
        passTime(r, SAVE_INTERVAL_MS)
        endEpisode(r, at = 5_000_000, durationMs = null)
        assertEquals(AutoNextUi.Countdown("Next: e2", COUNTDOWN_SECONDS), r.vm.autoNext.value)
        assertEquals(0L, r.dao.last("e1").positionMs)
        passTime(r, COUNTDOWN_SECONDS * 1_000L)
        assertEquals(listOf("e1", "e2"), r.openedKeys())
    }

    @Test fun autoPlayOffExits() = runTest(dispatcher) {
        val r = rig(start = episode(1), autoPlay = false)
        endEpisode(r)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        passTime(r, 15_000)
        assertEquals(listOf("e1"), r.openedKeys())
    }

    @Test fun lastEpisodeExits() = runTest(dispatcher) {
        val r = rig(start = episode(6))
        endEpisode(r)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e6")), r.events)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertEquals(listOf("e6"), r.openedKeys())
    }

    @Test fun pipEndExits() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        r.vm.onPipChanged(true)
        endEpisode(r)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        passTime(r, 15_000)
        assertEquals(listOf("e1"), r.openedKeys())
    }

    @Test fun thirdAutoStartAsksStillWatching() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endAndCountDown(r)
        endAndCountDown(r)
        assertEquals(listOf("e1", "e2", "e3"), r.openedKeys())
        endAndCountDown(r)
        assertEquals(AutoNextUi.StillWatching, r.vm.autoNext.value)
        assertEquals(listOf("e1", "e2", "e3"), r.openedKeys())
        assertEquals(3, r.playback.stops)
        assertTrue(r.events.isEmpty())
    }

    @Test fun stillWatchingTimesOutAndExits() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        repeat(3) { endAndCountDown(r) }
        assertEquals(AutoNextUi.StillWatching, r.vm.autoNext.value)
        passTime(r, STILL_WATCHING_TIMEOUT_MS - 1_000)
        assertTrue(r.events.isEmpty())
        passTime(r, 1_000)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e3")), r.events)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertEquals(listOf("e1", "e2", "e3"), r.openedKeys())
    }

    @Test fun keepWatchingStartsNextAndResetsCount() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        repeat(3) { endAndCountDown(r) }
        r.vm.onKeepWatching()
        runCurrent()
        assertEquals(listOf("e1", "e2", "e3", "e4"), r.openedKeys())
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        // The count started again: e5 and e6 both start by themselves.
        endAndCountDown(r)
        endAndCountDown(r)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6"), r.openedKeys())
        assertTrue(r.events.isEmpty())
    }

    @Test fun userKeyResetsCount() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endAndCountDown(r)
        endAndCountDown(r)
        r.vm.onUserKey()
        endAndCountDown(r)
        assertEquals(listOf("e1", "e2", "e3", "e4"), r.openedKeys())
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
    }

    @Test fun backDuringCountdownExitsAndOpensNothing() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        r.vm.onBack()
        runCurrent()
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        passTime(r, 15_000)
        assertEquals(listOf("e1"), r.openedKeys())
        assertEquals(1, r.playback.stops)
        // The end stays recorded: Back does not save the old position over it.
        assertTrue(r.dao.last("e1").watched)
        assertEquals(0L, r.dao.last("e1").positionMs)
    }

    @Test fun backDuringStillWatchingExitsAndOpensNothing() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        repeat(3) { endAndCountDown(r) }
        r.vm.onBack()
        r.vm.onKeepWatching()
        passTime(r, 2_000)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e3")), r.events)
        assertEquals(listOf("e1", "e2", "e3"), r.openedKeys())
    }

    @Test fun doublePlayNowStartsOnce() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        r.vm.onPlayNow()
        r.vm.onPlayNow()
        r.vm.onNextPressed()
        r.vm.onKeepWatching()
        passTime(r, 15_000)
        assertEquals(listOf("e1", "e2"), r.openedKeys())
        assertEquals(1, r.playback.stops)
    }

    @Test fun pillThenSecondStartWhileTheFirstIsInFlightStartsOnce() = runTest(dispatcher) {
        val dao = FakeProgressDao()
        dao.gateKey = "e2"
        val r = rig(start = episode(1), dao = dao)
        r.playback.playing(hour, at = hour - 60_000)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Pill, r.vm.autoNext.value)
        r.vm.onNextPressed()
        runCurrent()
        // The start waits on e2's progress: every other start path must do nothing meanwhile.
        assertEquals(listOf("e1"), r.openedKeys())
        r.vm.onNextPressed()
        r.vm.onPlayNow()
        r.vm.onKeepWatching()
        passTime(r, 1_000)
        dao.gate.complete(Unit)
        runCurrent()
        passTime(r, 15_000)
        assertEquals(listOf("e1", "e2"), r.openedKeys())
        assertEquals(1, r.playback.stops)
    }

    @Test fun playPauseBetweenEpisodesKeepsTheRecordedEnd() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        r.vm.onPlayPause()
        runCurrent()
        assertEquals(0L, r.dao.last("e1").positionMs)
        assertTrue(r.dao.last("e1").watched)
        assertEquals(false, r.playback.paused.value)
        r.vm.retry()
        runCurrent()
        assertEquals(listOf("e1"), r.openedKeys())
    }

    @Test fun countdownAtZeroThenPlayNowStartsOnce() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        passTime(r, COUNTDOWN_SECONDS * 1_000L)
        r.vm.onPlayNow()
        r.vm.onCancelCountdown()
        runCurrent()
        assertEquals(listOf("e1", "e2"), r.openedKeys())
        assertTrue(r.events.isEmpty())
    }

    @Test fun backgroundDuringCountdownExitsOnReturn() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        r.vm.onScreenStopped()
        r.playback.stop()
        passTime(r, 15_000)
        assertEquals(listOf("e1"), r.openedKeys())
        assertTrue(r.events.isEmpty())
        r.vm.onScreenStarted()
        runCurrent()
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        passTime(r, 2_000)
        assertEquals(listOf("e1"), r.openedKeys())
        assertTrue(r.dao.last("e1").watched)
    }

    @Test fun nextPressedMarksWatchedAndStartsNext() = runTest(dispatcher) {
        val dao = FakeProgressDao()
        dao.upserts += VodProgressEntity(VodKind.EPISODE, 1, "e2", 300_000, hour, false, Instant.ofEpochSecond(1))
        val r = rig(start = episode(1), dao = dao)
        r.playback.playing(hour, at = hour - 60_000)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Pill, r.vm.autoNext.value)
        r.vm.onNextPressed()
        runCurrent()
        val row = r.dao.last("e1")
        assertTrue(row.watched)
        assertEquals(1, r.playback.stops)
        assertEquals(episodeItem(episode(2)) to 300_000L, r.playback.opened.last())
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertTrue(r.events.isEmpty())
    }

    @Test fun pillHidesWhenPausedAndAfterTenSecondsUnlessTheBarShows() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        r.playback.playing(hour, at = 30_000)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        r.playback.position = hour - 60_000
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Pill, r.vm.autoNext.value)
        r.vm.onPlayPause()
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        r.vm.onNextPressed()
        runCurrent()
        assertEquals(listOf("e1"), r.openedKeys())
        r.vm.onPlayPause()
        passTime(r, NEXT_PILL_HIDE_MS)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        r.vm.onBarShowing(true)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Pill, r.vm.autoNext.value)
    }

    @Test fun autoPlayOffShowsNoNextButtonAndOkPauses() = runTest(dispatcher) {
        val r = rig(start = episode(1), autoPlay = false)
        r.playback.playing(hour, at = hour - 60_000)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertFalse(okGoesToNext(r.vm.autoNext.value, r.playback.paused.value))
        r.vm.onBarShowing(true)
        passTime(r, VOD_TICK_MS)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        r.vm.onNextPressed()
        runCurrent()
        assertEquals(listOf("e1"), r.openedKeys())
        assertEquals(0, r.playback.stops)
    }

    @Test fun pipDuringCountdownExits() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endEpisode(r)
        r.vm.onPipChanged(true)
        passTime(r, 15_000)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e1")), r.events)
        assertEquals(listOf("e1"), r.openedKeys())
    }

    @Test fun exitCarriesLastEpisodeKey() = runTest(dispatcher) {
        val r = rig(start = episode(1))
        endAndCountDown(r)
        r.playback.playing(hour, at = 100_000)
        runCurrent()
        r.vm.onBack()
        runCurrent()
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e2")), r.events)
        assertEquals(100_000L, r.dao.last("e2").positionMs)
        val film = rig()
        film.vm.onBack()
        runCurrent()
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit(null)), film.events)
    }

    @Test fun missingNextPrefetchesThenFindsIt() = runTest(dispatcher) {
        // e6 has no next episode until the show's episodes are fetched again and bring e7.
        var fetched = false
        val asked = mutableListOf<VodRef>()
        val r = rig(
            start = episode(6),
            next = { if (it == episode(6) && fetched) episode(7) else nextOf(it) },
            prefetchNext = { asked += it; fetched = true },
        )
        assertEquals(listOf(episode(6)), asked)
        endEpisode(r)
        assertEquals(AutoNextUi.Countdown("Next: e7", COUNTDOWN_SECONDS), r.vm.autoNext.value)
        assertTrue(r.events.isEmpty())
    }

    @Test fun storedNextMeansNoPrefetch() = runTest(dispatcher) {
        val asked = mutableListOf<VodRef>()
        rig(start = episode(1), prefetchNext = { asked += it })
        val film = rig(prefetchNext = { asked += it })
        assertTrue(asked.isEmpty())
        film.vm.onBack()
        runCurrent()
    }

    @Test fun noPrefetchWithoutAutoPlayNext() = runTest(dispatcher) {
        val asked = mutableListOf<VodRef>()
        val r = rig(start = episode(6), autoPlay = false, prefetchNext = { asked += it })
        assertTrue(asked.isEmpty())
        r.vm.onBack()
        runCurrent()
    }

    @Test fun noPrefetchOnceTheItemChanged() = runTest(dispatcher) {
        // The lookup for e6 answers only after the screen has moved on (here: left).
        val answer = CompletableDeferred<VodRef?>()
        val asked = mutableListOf<VodRef>()
        val r = rig(start = episode(6), next = { if (it == episode(6)) answer.await() else nextOf(it) }, prefetchNext = { asked += it })
        r.vm.onBack()
        runCurrent()
        answer.complete(null)
        runCurrent()
        assertTrue(asked.isEmpty())
    }

    @Test fun failedPrefetchMeansNoNext() = runTest(dispatcher) {
        val r = rig(start = episode(6), prefetchNext = { throw java.io.IOException("offline") })
        endEpisode(r)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e6")), r.events)
    }

    @Test fun endBeforePrefetchFinishesMeansNoNext() = runTest(dispatcher) {
        val latch = CompletableDeferred<Unit>()
        var fetched = false
        val r = rig(
            start = episode(6),
            next = { if (it == episode(6) && fetched) episode(7) else nextOf(it) },
            prefetchNext = { latch.await(); fetched = true },
        )
        endEpisode(r)
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
        assertEquals(listOf<VodPlayerEvent>(VodPlayerEvent.Exit("e6")), r.events)
        latch.complete(Unit)
        passTime(r, 15_000)
        assertEquals(listOf("e6"), r.openedKeys())
        assertEquals(AutoNextUi.Hidden, r.vm.autoNext.value)
    }
}
