package io.github.loopholetv.player.catchup

import androidx.media3.common.Player
import io.github.loopholetv.data.catchup.CatchupMode
import io.github.loopholetv.data.catchup.CatchupSpec
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CatchupControllerTest {

    /** Records every call; opening an archive or a live stream replaces the mode, as the real player does. */
    private class FakePlayback : Playback {
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state
        private val _mode = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = _mode

        val calls = mutableListOf<String>()
        var seekable = false
        var durationMs: Long? = null
        var hls = false
        var position = 0L

        override fun play(channel: ChannelEntity) {
            calls += "play ${channel.id}"
            _mode.value = PlayMode.Live
            _state.value = PlaybackState.Buffering(channel.id)
        }

        override fun stop() {
            calls += "stop"
            _mode.value = PlayMode.Live
            _state.value = PlaybackState.Idle
        }

        override fun playArchive(channel: ChannelEntity, request: ArchiveRequest) {
            calls += archive(request.from, request.until)
            position = 0
            _mode.value = PlayMode.Archive(channel.id, request, seekable, durationMs, hls = hls)
            _state.value = PlaybackState.Buffering(channel.id)
        }

        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun positionMs(): Long = position
        override fun seekWithin(positionMs: Long) {
            calls += "seek $positionMs"
            position = positionMs
        }

        fun end() {
            val m = _mode.value as PlayMode.Archive
            _mode.value = m.copy(ended = true)
        }

        fun fail(id: Long) { _state.value = PlaybackState.Error(id, "Stream unavailable") }
        fun playing(id: Long) { _state.value = PlaybackState.Playing(id, StreamTags.NONE) }
        fun buffering(id: Long) { _state.value = PlaybackState.Buffering(id) }
    }

    private val channel = ChannelEntity(
        id = 7, playlistId = 1, key = "k7", number = 7, name = "Ch 7", streamUrl = "http://example.test/live/7",
        logo = null, groupTitle = "All", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = "shift", catchupDays = 3, catchupSource = null,
        epgChannelId = "e7",
    )

    /** Lag 120 s, archive addressed by the minute. */
    private val minuteSpec = CatchupSpec(CatchupMode.XC, 3, null, minuteGranular = true)

    /** Lag 60 s, archive addressed by the second. */
    private val secondSpec = CatchupSpec(CatchupMode.SHIFT, 3, null, minuteGranular = false)

    private val news = ProgrammeSpan("News", t("18:00:00"), t("19:00:00"))
    private val film = ProgrammeSpan("Film", t("19:00:00"), t("20:00:00"))
    private val show = ProgrammeSpan("Show", t("20:00:00"), t("21:00:00"))
    private var guide = listOf(news, film, show)

    /** When set, every guide lookup waits for it, as a slow database read would. */
    private var gate: CompletableDeferred<Unit>? = null

    /** Per-instant gates, to choose the order in which concurrent lookups finish. */
    private val gates = mutableMapOf<Instant, CompletableDeferred<Unit>>()

    private val fake = FakePlayback()

    /** "Now" starts at 20:00:30 and moves with virtual time. */
    private val start = t("20:00:30")

    private fun TestScope.controller(liveOpener: ((ChannelEntity) -> Unit)? = null) = CatchupController(
        playback = fake,
        programmes = { _, at ->
            gate?.await()
            gates[at]?.await()
            guide.firstOrNull { !at.isBefore(it.start) && at.isBefore(it.stop) }
        },
        clock = { start.plusMillis(testScheduler.currentTime) },
        elapsedMs = { testScheduler.currentTime },
        zone = ZoneOffset.UTC,
        scope = backgroundScope,
        liveOpener = liveOpener ?: { fake.play(it) },
    ).also { runCurrent() }

    private fun TestScope.watched(spec: CatchupSpec, span: ProgrammeSpan, from: Instant = span.start): CatchupController {
        val c = controller()
        c.watch(channel, spec, from, span.stop)
        runCurrent()
        fake.calls.clear()
        return c
    }

    @Test fun watchStartsArchiveWithProgramme() = runTest {
        val c = controller()
        c.watch(channel, minuteSpec, news.start, news.stop)
        runCurrent()
        assertEquals(listOf(archive(news.start, news.stop)), fake.calls)
        val ui = c.ui.value!!
        assertEquals(channel.id, ui.channelId)
        assertEquals(news, ui.programme)
        assertEquals(news.start, ui.position)
        assertFalse(ui.paused)
        assertFalse(ui.live)
        assertFalse(ui.failed)
        assertNull(ui.pendingTarget)
    }

    @Test fun heldRewindCommitsOneSeek() = runTest {
        val c = watched(minuteSpec, film)
        fake.position = Duration.ofMinutes(30).toMillis()
        for (n in 0..20) c.seekKey(channel, minuteSpec, -1, n)
        // 6 x 10 s + 10 x 30 s + 5 x 60 s = 11 min back from 19:30.
        assertEquals(t("19:19:00"), c.ui.value!!.pendingTarget)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
        assertEquals(t("19:19:00"), c.ui.value!!.pendingTarget)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(archive(t("19:19:00"), film.stop)), fake.calls)
        assertNull(c.ui.value!!.pendingTarget)
        assertEquals(t("19:19:00"), c.ui.value!!.position)
        advanceTimeBy(5_000)
        assertEquals(1, fake.calls.size)
    }

    @Test fun pressAfterReleaseKeepsBatching() = runTest {
        val c = watched(secondSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.seekKey(channel, secondSpec, 1, 0)
        c.seekKeyUp()
        advanceTimeBy(500)
        c.seekKey(channel, secondSpec, 1, 0)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf(archive(t("18:11:00"), news.stop)), fake.calls)
    }

    @Test fun seekWithinWhenSeekable() = runTest {
        fake.seekable = true
        fake.durationMs = Duration.ofSeconds(3600).toMillis()
        fake.hls = true
        val c = watched(secondSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.seekKey(channel, secondSpec, 1, 0)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("seek ${Duration.ofSeconds(630).toMillis()}"), fake.calls)
        assertEquals(t("18:10:30"), c.ui.value!!.position)
        assertEquals(news, c.ui.value!!.programme)
    }

    @Test fun reopenWhenOpenEnded() = runTest {
        val c = watched(secondSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.seekKey(channel, secondSpec, 1, 0)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf(archive(t("18:10:30"), news.stop)), fake.calls)
    }

    @Test fun rewindIntoEarlierProgrammeShowsIt() = runTest {
        val c = watched(secondSpec, film)
        fake.position = 20_000
        repeat(3) { c.seekKey(channel, secondSpec, -1, 0) }
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf(archive(t("18:59:50"), news.stop)), fake.calls)
        assertEquals(news, c.ui.value!!.programme)
    }

    @Test fun ffPastLiveGoesLive() = runTest {
        val c = watched(minuteSpec, film, from = t("19:58:00"))
        repeat(2) { c.seekKey(channel, minuteSpec, 1, 0) }
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun rewindFromLiveGoesBackAtLeastLag() = runTest {
        val c = controller()
        c.rewindLive(channel, minuteSpec)
        runCurrent()
        // 20:00:30 - 120 s = 19:58:30, floored to the minute.
        assertEquals(listOf(archive(t("19:58:00"), film.stop)), fake.calls)
        assertEquals(film, c.ui.value!!.programme)
        assertEquals(t("19:58:00"), c.ui.value!!.position)
    }

    @Test fun watchFromStartOpensAiringProgramme() = runTest {
        val c = controller()
        advanceTimeBy(Duration.ofMinutes(30).toMillis())
        c.watchFromStart(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(show.start, show.stop)), fake.calls)
        assertEquals(show, c.ui.value!!.programme)
    }

    @Test fun watchFromStartOfJustStartedProgrammeStaysBehindLag() = runTest {
        val c = controller()
        c.watchFromStart(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(t("19:58:00"), film.stop)), fake.calls)
        assertEquals(film, c.ui.value!!.programme)
    }

    @Test fun watchFromStartUnderLagThatEndsCloseToLiveGoesLive() = runTest {
        val c = controller()
        c.watchFromStart(channel, minuteSpec)
        runCurrent()
        fake.calls.clear()
        advanceTimeBy(Duration.ofMinutes(2).toMillis())
        fake.position = Duration.ofMinutes(2).toMillis()
        fake.end()
        runCurrent()
        // It stopped at 20:00, 2.5 min behind live: within the lag plus a minute, so it goes live.
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun rewindOnLiveThenStreamEndsGoesLiveWithoutReopening() = runTest {
        val c = controller()
        advanceTimeBy(Duration.ofMinutes(30).toMillis()) // 20:30:30
        c.rewindLive(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(t("20:28:00"), show.stop)), fake.calls)
        fake.calls.clear()
        // The provider served what it had when it was opened (2.5 min), then the stream ended.
        advanceTimeBy(Duration.ofSeconds(160).toMillis())
        fake.position = Duration.ofSeconds(150).toMillis()
        fake.end()
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun streamEndingFarBehindLiveReopensWhereItEnded() = runTest {
        val c = controller()
        advanceTimeBy(Duration.ofMinutes(30).toMillis()) // 20:30:30
        c.watchFromStart(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(show.start, show.stop)), fake.calls)
        fake.calls.clear()
        // Ended early, at 20:10, while live is at 20:40:30: carry on from there to the programme's stop.
        advanceTimeBy(Duration.ofMinutes(10).toMillis())
        fake.position = Duration.ofMinutes(10).toMillis()
        fake.end()
        runCurrent()
        assertEquals(listOf(archive(t("20:10:00"), show.stop)), fake.calls)
        assertEquals(show, c.ui.value!!.programme)
    }

    @Test fun openEndedStreamEndingUnwatchedDoesNotReplay() = runTest {
        val c = watched(minuteSpec, news)
        // Played on to 19:15 with the overlay hidden, so no tick ever rolled the programme.
        fake.position = Duration.ofMinutes(75).toMillis()
        fake.end()
        runCurrent()
        assertEquals(listOf(archive(t("19:15:00"), film.stop)), fake.calls)
        assertEquals(film, c.ui.value!!.programme)
    }

    @Test fun adoptTakesOverArchiveStartedElsewhere() = runTest {
        val c = controller()
        fake.playArchive(channel, ArchiveRequest(news.start, news.stop))
        fake.position = Duration.ofMinutes(5).toMillis()
        fake.calls.clear()
        c.adopt(channel, minuteSpec)
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
        assertEquals(news, c.ui.value!!.programme)
        assertEquals(t("18:05:00"), c.ui.value!!.position)
        fake.position = Duration.ofMinutes(60).toMillis()
        fake.end()
        runCurrent()
        assertEquals(listOf(archive(film.start, film.stop)), fake.calls)
    }

    @Test fun endedContinuesToNextProgramme() = runTest {
        val c = watched(minuteSpec, news)
        fake.end()
        runCurrent()
        assertEquals(listOf(archive(film.start, film.stop)), fake.calls)
        assertEquals(film, c.ui.value!!.programme)
    }

    @Test fun endedNearLiveGoesLive() = runTest {
        val c = watched(minuteSpec, film)
        fake.end()
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun nextProgrammeNearLiveGoesLive() = runTest {
        val c = watched(minuteSpec, film)
        c.nextProgramme()
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun nextProgrammeOpensItsStart() = runTest {
        val c = watched(minuteSpec, news)
        c.nextProgramme()
        runCurrent()
        assertEquals(listOf(archive(film.start, film.stop)), fake.calls)
        assertEquals(film, c.ui.value!!.programme)
    }

    @Test fun previousProgrammeOpensItsStart() = runTest {
        val c = watched(minuteSpec, film)
        fake.position = Duration.ofMinutes(20).toMillis()
        c.previousProgramme()
        runCurrent()
        assertEquals(listOf(archive(news.start, news.stop)), fake.calls)
        assertEquals(news, c.ui.value!!.programme)
    }

    @Test fun pauseLiveReleasesAndResumesViaArchive() = runTest {
        val c = controller()
        c.togglePause(channel, secondSpec)
        runCurrent()
        assertEquals(listOf("stop"), fake.calls)
        val paused = c.ui.value!!
        assertTrue(paused.live)
        assertTrue(paused.paused)
        assertEquals(start, paused.position)
        assertEquals(show, paused.programme)
        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        c.togglePause(channel, secondSpec)
        runCurrent()
        assertEquals(listOf("stop", archive(start, show.stop)), fake.calls)
        assertFalse(c.ui.value!!.live)
        assertFalse(c.ui.value!!.paused)
    }

    @Test fun quickPauseLiveResumesNoLaterThanLag() = runTest {
        val c = controller()
        c.togglePause(channel, minuteSpec)
        runCurrent()
        advanceTimeBy(10_000)
        c.togglePause(channel, minuteSpec)
        runCurrent()
        // Paused at 20:00:30, resumed at 20:00:40: the archive only reaches 19:58:40, floored to 19:58.
        assertEquals(listOf("stop", archive(t("19:58:00"), film.stop)), fake.calls)
    }

    @Test fun pauseOnLiveWithoutCatchupDoesNothing() = runTest {
        val c = controller()
        c.togglePause(channel, null)
        runCurrent()
        c.togglePause(channel, null)
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun shortArchivePauseResumesInPlace() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.togglePause(channel, minuteSpec)
        assertTrue(c.ui.value!!.paused)
        assertEquals(t("18:10:00"), c.ui.value!!.position)
        advanceTimeBy(PAUSE_RELEASE_MS - 1_000)
        c.togglePause(channel, minuteSpec)
        advanceTimeBy(PAUSE_RELEASE_MS * 2)
        runCurrent()
        assertEquals(listOf("pause", "resume"), fake.calls)
        assertFalse(c.ui.value!!.paused)
    }

    @Test fun longArchivePauseReleasesConnection() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.togglePause(channel, minuteSpec)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(listOf("pause"), fake.calls)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("pause", "stop"), fake.calls)
        assertTrue(c.ui.value!!.paused)
        assertEquals(t("18:10:00"), c.ui.value!!.position)
        c.togglePause(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf("pause", "stop", archive(t("18:10:00"), news.stop)), fake.calls)
        assertFalse(c.ui.value!!.paused)
    }

    @Test fun goLiveCallsPlayOnceAndClearsUi() = runTest {
        val c = watched(minuteSpec, news)
        c.seekKey(channel, minuteSpec, -1, 0)
        c.goLive(channel)
        assertNull(c.ui.value)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS * 3)
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun noGuideDataUsesHourSpan() = runTest {
        guide = emptyList()
        val c = controller()
        c.watch(channel, minuteSpec, t("18:20:00"), t("18:50:00"))
        runCurrent()
        assertEquals(listOf(archive(t("18:20:00"), t("18:50:00"))), fake.calls)
        assertEquals(ProgrammeSpan(null, t("18:00:00"), t("19:00:00")), c.ui.value!!.programme)
    }

    @Test fun openEndedStreamRollsProgrammeWithoutReconnect() = runTest {
        val c = watched(minuteSpec, news, from = t("18:50:00"))
        fake.position = Duration.ofMinutes(15).toMillis()
        c.tick()
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
        assertEquals(t("19:05:00"), c.ui.value!!.position)
        assertEquals(film, c.ui.value!!.programme)
    }

    @Test fun tickFollowsPosition() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(3).toMillis()
        advanceTimeBy(1_000)
        c.tick()
        runCurrent()
        assertEquals(t("18:03:00"), c.ui.value!!.position)
        assertEquals(t("20:00:31"), c.ui.value!!.now)
        assertEquals(news, c.ui.value!!.programme)
    }

    @Test fun failedFollowsArchiveError() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.tick()
        fake.fail(channel.id)
        runCurrent()
        assertTrue(c.ui.value!!.failed)
        fake.position = 0
        c.retry(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(t("18:10:00"), news.stop)), fake.calls)
        assertFalse(c.ui.value!!.failed)
        fake.fail(channel.id)
        runCurrent()
        fake.playing(channel.id)
        runCurrent()
        assertFalse(c.ui.value!!.failed)
    }

    @Test fun retryAfterLongUnwatchedPlayResumesWhereItFailed() = runTest {
        val c = watched(minuteSpec, news)
        advanceTimeBy(Duration.ofMinutes(40).toMillis())
        fake.position = Duration.ofMinutes(40).toMillis()
        fake.fail(channel.id)
        runCurrent()
        assertEquals(t("18:40:00"), c.ui.value!!.position)
        c.retry(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(t("18:40:00"), news.stop)), fake.calls)
    }

    @Test fun adoptSeesEarlierFailure() = runTest {
        val c = controller()
        fake.playArchive(channel, ArchiveRequest(news.start, news.stop))
        fake.fail(channel.id)
        runCurrent()
        fake.calls.clear()
        c.adopt(channel, minuteSpec)
        runCurrent()
        assertTrue(c.ui.value!!.failed)
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test fun adoptContinuesArchiveThatAlreadyEnded() = runTest {
        val c = controller()
        fake.playArchive(channel, ArchiveRequest(news.start, news.stop))
        fake.end()
        runCurrent()
        fake.calls.clear()
        c.adopt(channel, minuteSpec)
        runCurrent()
        assertEquals(listOf(archive(film.start, film.stop)), fake.calls)
    }

    @Test fun staleProgrammeRollDoesNotOverwriteNewerOpen() = runTest {
        val c = watched(minuteSpec, news, from = t("18:50:00"))
        fake.position = Duration.ofMinutes(15).toMillis()
        val openLookup = CompletableDeferred<Unit>().also { gates[t("18:54:00")] = it }
        val rollLookup = CompletableDeferred<Unit>().also { gates[t("19:05:00")] = it }
        for (n in 0..20) c.seekKey(channel, minuteSpec, -1, n)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        c.tick()
        runCurrent()
        // The newer open finishes first; the older roll's lookup only returns afterwards.
        openLookup.complete(Unit)
        runCurrent()
        rollLookup.complete(Unit)
        runCurrent()
        assertEquals(listOf(archive(t("18:54:00"), news.stop)), fake.calls)
        assertEquals(news, c.ui.value!!.programme)
        assertEquals(t("18:54:00"), c.ui.value!!.position)
    }

    @Test fun pressDuringCommitKeepsCommittedSeek() = runTest {
        val c = watched(secondSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        gate = CompletableDeferred()
        c.seekKey(channel, secondSpec, -1, 0)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        c.seekKey(channel, secondSpec, -1, 0)
        assertEquals(t("18:09:40"), c.ui.value!!.pendingTarget)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(archive(t("18:09:40"), news.stop)), fake.calls)
    }

    @Test fun pendingTargetStaysWhileEarlierSeekFinishes() = runTest {
        val c = watched(secondSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        gate = CompletableDeferred()
        c.seekKey(channel, secondSpec, -1, 0)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        c.seekKey(channel, secondSpec, -1, 0)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(archive(t("18:09:50"), news.stop)), fake.calls)
        assertEquals(t("18:09:40"), c.ui.value!!.pendingTarget)
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf(archive(t("18:09:50"), news.stop), archive(t("18:09:40"), news.stop)), fake.calls)
        assertNull(c.ui.value!!.pendingTarget)
    }

    @Test fun seekFromPausedLiveToArchiveStartStaysInsideWindow() = runTest {
        val c = controller()
        c.togglePause(channel, minuteSpec)
        runCurrent()
        fake.calls.clear()
        repeat(900) { c.seekKey(channel, minuteSpec, -1, 31) }
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS + 1)
        runCurrent()
        // The window starts at 20:00:30.7 three days ago; the first whole minute inside it is 20:01.
        val first = Instant.parse("2026-11-07T20:01:00Z")
        assertEquals(listOf(archive(first, Instant.parse("2026-11-07T21:00:00Z"))), fake.calls)
    }

    @Test fun nextProgrammeNearItsEndStillMoves() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofSeconds(59 * 60 + 58).toMillis()
        c.nextProgramme()
        runCurrent()
        assertEquals(listOf(archive(film.start, film.stop)), fake.calls)
    }

    @Test fun goLiveDuringLookupOpensNoArchive() = runTest {
        val c = controller()
        gate = CompletableDeferred()
        c.watch(channel, minuteSpec, news.start, news.stop)
        runCurrent()
        c.goLive(channel)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("play 7"), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun errorWhileLiveLeavesUiAlone() = runTest {
        val c = controller()
        fake.fail(channel.id)
        runCurrent()
        assertNull(c.ui.value)
    }

    @Test fun clearDropsUiAndResumePoint() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(4).toMillis()
        assertEquals(channel.id to t("18:04:00"), c.resumePoint())
        c.seekKey(channel, minuteSpec, -1, 0)
        c.clear()
        assertNull(c.ui.value)
        assertNull(c.resumePoint())
        c.seekKeyUp()
        advanceTimeBy(COMMIT_DELAY_MS * 3)
        fake.end()
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test fun resumeReopensAtSavedPoint() = runTest {
        val c = controller()
        c.resume(channel, minuteSpec, t("18:30:00"))
        runCurrent()
        assertEquals(listOf(archive(t("18:30:00"), news.stop)), fake.calls)
        assertEquals(news, c.ui.value!!.programme)
        assertEquals(t("18:30:00"), c.ui.value!!.position)
    }

    @Test fun pausedArchiveStoppedFromOutsideReopensOnPlay() = runTest {
        val c = watched(minuteSpec, news)
        fake.position = Duration.ofMinutes(10).toMillis()
        c.togglePause(channel, minuteSpec)
        fake.stop() // leaving the app stops the stream
        runCurrent()
        fake.calls.clear()
        c.togglePause(channel, minuteSpec)
        runCurrent()
        assertEquals("reopened, not resumed on an empty player", listOf(archive(t("18:10:00"), news.stop)), fake.calls)
        assertFalse(c.ui.value!!.paused)
        advanceTimeBy(PAUSE_RELEASE_MS * 2)
        runCurrent()
        assertEquals("no late release", listOf(archive(t("18:10:00"), news.stop)), fake.calls)
    }

    @Test fun errorClearsOnceTheStreamReopens() = runTest {
        val c = watched(minuteSpec, news)
        fake.fail(channel.id)
        runCurrent()
        assertTrue(c.ui.value!!.failed)
        fake.buffering(channel.id)
        runCurrent()
        assertFalse("the card goes while it buffers", c.ui.value!!.failed)
    }

    @Test fun goLiveUsesTheLiveOpener() = runTest {
        val opened = mutableListOf<Long>()
        val c = controller(liveOpener = { opened += it.id })
        c.watch(channel, minuteSpec, news.start, news.stop)
        runCurrent()
        fake.calls.clear()
        c.goLive(channel)
        assertEquals(listOf(channel.id), opened)
        assertEquals("live is not played behind the opener", emptyList<String>(), fake.calls)
        assertNull(c.ui.value)
    }

    @Test fun goLiveTwiceOpensLiveOnce() = runTest {
        val opened = mutableListOf<Long>()
        val c = controller(liveOpener = { opened += it.id })
        c.watch(channel, minuteSpec, news.start, news.stop)
        runCurrent()
        c.goLive(channel)
        c.goLive(channel)
        assertEquals(listOf(channel.id), opened)
    }

    @Test fun cancelSeekDropsThePendingSeek() = runTest {
        val c = watched(minuteSpec, news)
        c.seekKey(channel, minuteSpec, 1, 0)
        assertTrue(c.ui.value!!.pendingTarget != null)
        c.seekKeyUp()
        c.cancelSeek()
        advanceTimeBy(COMMIT_DELAY_MS * 2)
        runCurrent()
        assertEquals(emptyList<String>(), fake.calls)
        assertNull(c.ui.value!!.pendingTarget)
    }

    private companion object {
        fun t(hms: String): Instant = Instant.parse("2026-11-10T${hms}Z")
        fun archive(from: Instant, until: Instant) = "archive $from..$until"
    }
}
