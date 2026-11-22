package io.github.loopholetv.record

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.PreviewHold
import io.github.loopholetv.player.catchup.ArchiveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // ScheduledAlarm exposes its fields only as deprecated Java fields
// A plain Application: the app's own start-up would re-arm alarms from a real database in the background.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RecordingCoordinatorTest {
    private val base = Instant.parse("2026-11-22T20:00:00Z")

    @Before fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
    }

    // --- fakes ---

    /** Provider connections open on this TV, per host, and the most ever open at once. */
    private class Connections {
        val open = mutableMapOf<String, Int>()
        var max = 0
        fun open(host: String) {
            open[host] = (open[host] ?: 0) + 1
            max = maxOf(max, open.values.maxOrNull() ?: 0)
        }
        fun close(host: String) {
            open[host] = ((open[host] ?: 0) - 1).coerceAtLeast(0)
        }
    }

    /** One stream at a time, released before the next opens (as the real player does). */
    private class FakePlayback(private val conn: Connections, private val log: MutableList<String>) : Playback {
        val hosts = mutableMapOf<Long, String>()
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state
        private var holding: String? = null

        override fun play(channel: ChannelEntity) {
            release()
            log += "play ${channel.id}"
            holding = hosts.getValue(channel.id).also(conn::open)
            _state.value = PlaybackState.Buffering(channel.id)
        }

        override fun playArchive(channel: ChannelEntity, request: ArchiveRequest) = play(channel)

        override fun stop() {
            log += "stop"
            release()
            _state.value = PlaybackState.Idle
        }

        override fun reopen() {
            log += "reopen"
        }

        private fun release() {
            holding?.let(conn::close)
            holding = null
        }
    }

    private class FakeCast(private val log: MutableList<String>) : CastControls {
        private val _state = MutableStateFlow<CastState>(CastState.Off)
        override val state: StateFlow<CastState> = _state
        fun casting() { _state.value = CastState.Casting(1) }
        override fun startCasting() = casting()
        override fun stopCasting(reason: StopReason) {
            log += "stopCasting $reason"
            _state.value = CastState.Off
        }
    }

    /** Opens a connection on begin and closes it on stop, keeping the row's state as the real runner does. */
    private class FakeRunner(
        private val db: AppDatabase,
        private val conn: Connections,
        private val log: MutableList<String>,
        private val clock: () -> Instant,
        private val scope: CoroutineScope,
    ) : RecordingRuns {
        private val running = MutableStateFlow<Map<Long, ActiveRecording>>(emptyMap())
        override val active: StateFlow<Map<Long, ActiveRecording>> = running.asStateFlow()
        override var runGate: suspend (id: Long, fromArchive: Boolean) -> Unit = { id, a -> begin(id, a) }
        var firstBytesAtOnce = true

        /** Above zero: the run is listed in [active] only this long after begin returns, as the real runner may. */
        var listingDelayMs = 0L

        /** The provider refuses at once: the row fails without ever being listed. */
        var failOnBegin = false

        override suspend fun begin(id: Long, fromArchive: Boolean) {
            if (running.value.containsKey(id)) return
            log += "begin $id"
            val r = db.recordDao().recording(id) ?: return
            val channel = db.channelDao().byKey(r.playlistId, r.channelKey)
            val playlist = db.playlistDao().byId(r.playlistId)
            if (channel == null || playlist == null || failOnBegin) {
                db.recordDao().updateRecording(r.copy(state = RecordingState.FAILED, reason = RecordingReason.ERROR))
                return
            }
            val host = providerHost(playlist)
            conn.open(host)
            db.recordDao().updateRecording(r.copy(state = RecordingState.RECORDING, startedAt = clock()))
            val listed = ActiveRecording(id, channel.id, channel.id, host, "f.ts", "s", firstBytesAtOnce)
            if (listingDelayMs > 0) {
                scope.launch {
                    delay(listingDelayMs)
                    running.update { it + (id to listed) }
                }
            } else {
                running.update { it + (id to listed) }
            }
        }

        fun firstBytes(id: Long) = running.update { m -> m[id]?.let { m + (id to it.copy(firstBytes = true)) } ?: m }

        override suspend fun stop(id: Long, reason: RecordingReason) {
            log += "stopRecording $id $reason"
            running.value[id]?.let { conn.close(it.host) }
            running.update { it - id }
            val r = db.recordDao().recording(id) ?: return
            if (r.state == RecordingState.RECORDING) {
                db.recordDao().updateRecording(r.copy(state = RecordingState.STOPPED, reason = reason, endedAt = clock()))
            }
        }

        override suspend fun recover() {
            for (r in db.recordDao().inState(RecordingState.RECORDING)) {
                if (!running.value.containsKey(r.id)) runGate(r.id, false)
            }
        }
    }

    /** The wake lock and service calls the coordinator makes, in order. */
    private class FakePlatform(val log: MutableList<String>) : RecordingServiceControl, StartWakeLock {
        override fun started(id: Long, title: String) { log += "started $id" }
        override fun ended(id: Long) { log += "ended $id" }
        override fun hold(id: Long, timeoutMs: Long) { log += "hold $id" }
        override fun release(id: Long) { log += "release $id" }
    }

    private inner class Env(val test: TestScope, limit: Int, private val collectHandovers: Boolean = true) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(test.testScheduler))
            .build()
        val log = mutableListOf<String>()
        val conn = Connections()
        val clock: () -> Instant = { base.plusMillis(test.testScheduler.currentTime) }
        val playback = FakePlayback(conn, log)
        val cast = FakeCast(log)
        val runner = FakeRunner(db, conn, log, clock, test.backgroundScope)
        val hold = PreviewHold()
        val hostOf = mutableMapOf<Long, String>()
        val uses = StreamUses(playback, cast, runner, hostOfChannel = { hostOf[it] })
        var rearm: suspend () -> Unit = {}
        val handovers = mutableListOf<Handover>()
        val platformLog = mutableListOf<String>()
        val platform = FakePlatform(platformLog)
        var onEvict: suspend () -> Unit = {}
        val limit = limit
        lateinit var coordinator: RecordingCoordinator
        lateinit var a: ChannelEntity
        lateinit var b: ChannelEntity
        lateinit var c: ChannelEntity
        lateinit var d: ChannelEntity
        lateinit var e4: ChannelEntity

        suspend fun setUp() {
            val h1 = addPlaylist("H1", "http://h1.example.test/list.m3u", limit)
            val h2 = addPlaylist("H2", "http://h2.example.test/list.m3u", limit)
            val one = db.insertChannels(
                channel(h1, "a", 1, name = "Film4"), channel(h1, "b", 2, name = "BBC One"), channel(h1, "e", 5, name = "E4"),
            )
            a = one.getValue("a")
            b = one.getValue("b")
            e4 = one.getValue("e")
            val two = db.insertChannels(channel(h2, "c", 3, name = "ITV"), channel(h2, "d", 4, name = "Dave"))
            c = two.getValue("c")
            d = two.getValue("d")
            for (ch in listOf(a, b, e4)) {
                hostOf[ch.id] = "h1.example.test"
                playback.hosts[ch.id] = "h1.example.test"
            }
            for (ch in listOf(c, d)) {
                hostOf[ch.id] = "h2.example.test"
                playback.hosts[ch.id] = "h2.example.test"
            }
            coordinator = RecordingCoordinator(
                runner, db, uses, playback, hold, cast,
                evictIdle = {
                    log += "evict"
                    onEvict()
                },
                clock = clock,
                scope = test.backgroundScope,
                rearm = { rearm() },
                service = platform,
                startLock = platform,
            )
            runner.runGate = { id, fromArchive -> coordinator.startBudgeted(id, fromArchive) }
            if (collectHandovers) test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                coordinator.handover.filterNotNull().collect {
                    handovers += it
                    coordinator.consumeHandover()
                }
            }
        }

        private suspend fun addPlaylist(name: String, url: String, limit: Int): Long = db.playlistDao().insert(
            PlaylistEntity(
                name = name, type = PlaylistType.M3U, url = url, xtreamServer = null, xtreamUser = null,
                xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
                connectionsAllowed = limit,
            ),
        )

        /** A booking whose padded start is [fromInMs] from now (one minute of padding before, five after). */
        suspend fun book(
            channel: ChannelEntity,
            fromInMs: Long = 30_000,
            state: RecordingState = RecordingState.SCHEDULED,
        ): Long {
            val start = clock().plusMillis(fromInMs + 60_000)
            return db.recordDao().insertRecording(
                RecordingEntity(
                    playlistId = channel.playlistId, channelKey = channel.key, channelName = channel.name,
                    epgChannelId = null, title = "The Thing", subTitle = null, episodeKey = null, description = null,
                    start = start, stop = start.plusSeconds(1800), padBeforeMin = 1, padAfterMin = 5,
                    state = state, createdAt = clock(),
                ),
            )
        }

        suspend fun row(id: Long): RecordingEntity = db.recordDao().recording(id)!!

        /**
         * Fires the booking's alarms as [RecordAlarms] sets them: PREPARE now (when its padded start is still
         * ahead), START at the padded start.
         */
        suspend fun alarms(id: Long) {
            val wait = row(id).window().from.toEpochMilli() - clock().toEpochMilli()
            if (wait > 1_000) coordinator.onAlarm(AlarmAction.PREPARE, id)
            test.backgroundScope.launch {
                delay(wait.coerceAtLeast(0))
                coordinator.onAlarm(AlarmAction.START, id)
            }
        }

        fun begins(id: Long) = log.count { it == "begin $id" }

        fun assertWithinLimit() = assertTrue("max ${conn.max} over $limit", conn.max <= limit)
    }

    private fun test(limit: Int = 1, collectHandovers: Boolean = true, body: suspend TestScope.(Env) -> Unit) = runTest {
        val e = Env(this, limit, collectHandovers)
        try {
            e.setUp()
            body(e)
            e.assertWithinLimit()
        } finally {
            e.db.close()
        }
    }

    // --- PREPARE ---

    @Test fun idleStartsAtPaddedStart() = test { e ->
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(0, e.begins(id))
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(RecordingState.RECORDING, e.row(id).state)
        assertNull(e.coordinator.prompt.value)
        assertTrue(e.handovers.isEmpty())
    }

    @Test fun previewOnlyIsTakenWithoutWarning() = test { e ->
        e.uses.setPreviewVisible(true)
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(0, e.begins(id))
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(listOf("play ${e.b.id}", "stop", "evict", "begin $id"), e.log)
        assertEquals("Film4", e.coordinator.previewTaken.value)
        assertTrue(e.hold.consume())
        assertTrue(e.handovers.isEmpty())
    }

    @Test fun previewTakenClearsWhenTheRecordingEnds() = test { e ->
        e.uses.setPreviewVisible(true)
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Film4", e.coordinator.previewTaken.value)
        e.runner.stop(id, RecordingReason.USER)
        runCurrent()
        assertNull(e.coordinator.previewTaken.value)
    }

    @Test fun watchingSameChannelHandsOverWithoutWarning() = test { e ->
        e.playback.play(e.a)
        e.runner.firstBytesAtOnce = false
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(listOf("play ${e.a.id}", "stop", "evict", "begin $id"), e.log)
        // The player switches to the file only once it has something in it.
        assertTrue(e.handovers.isEmpty())
        e.runner.firstBytes(id)
        runCurrent()
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun watchingOtherChannelWarnsThirtySeconds() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertEquals(RecordPrompt.Warning(id, "Film4", "The Thing", Use.PLAYER, 30), e.coordinator.prompt.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(20, (e.coordinator.prompt.value as RecordPrompt.Warning).secondsLeft)
        assertEquals(0, e.begins(id))
        assertEquals(PlaybackState.Buffering(e.b.id), e.playback.state.value)
    }

    @Test fun timeoutSwitches() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, e.begins(id))
        advanceTimeBy(1)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(listOf("play ${e.b.id}", "stop", "evict", "begin $id"), e.log)
        assertEquals(PlaybackState.Idle, e.playback.state.value)
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun switchNowSwitchesAtOnce() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(5_000)
        e.coordinator.answer(id, PromptChoice.SWITCH_NOW)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(1, e.begins(id))
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun keepWatchingSkips() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(3_000)
        e.coordinator.answer(id, PromptChoice.KEEP_WATCHING)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(RecordingState.SKIPPED, e.row(id).state)
        assertEquals(RecordingReason.USER, e.row(id).reason)
        // Stopping the player later does not pick up the rest.
        e.playback.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, e.begins(id))
        assertTrue(e.handovers.isEmpty())
    }

    @Test fun castingWarnsWithStopsCastAndStopsCastNotPlayback() = test { e ->
        e.playback.play(e.b)
        e.cast.casting()
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertEquals(Use.CAST, (e.coordinator.prompt.value as RecordPrompt.Warning).stops)
        advanceTimeBy(30_000)
        runCurrent()
        // The cast ends without playback being reopened, then the player's stream is released.
        assertEquals(listOf("play ${e.b.id}", "stopCasting RECORDING", "stop", "evict", "begin $id"), e.log)
        assertFalse(e.log.contains("reopen"))
        assertEquals(CastState.Off, e.cast.state.value)
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun otherProviderPlayingNeedsNoWarning() = test { e ->
        e.playback.play(e.c)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(PlaybackState.Buffering(e.c.id), e.playback.state.value)
        assertFalse(e.log.contains("stop"))
    }

    @Test fun limitTwoRecordsAlongsidePlayer() = test(limit = 2) { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(PlaybackState.Buffering(e.b.id), e.playback.state.value)
        assertEquals(2, e.conn.max)
    }

    @Test fun overLimitByRecordingsIsMissedClash() = test { e ->
        val first = e.book(e.b, fromInMs = 0)
        e.runner.begin(first)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, e.begins(id))
        assertEquals(RecordingState.MISSED, e.row(id).state)
        assertEquals(RecordingReason.CLASH, e.row(id).reason)
        assertEquals(RecordingState.RECORDING, e.row(first).state)
    }

    @Test fun twoBookingsAtOnceOnOneConnectionStartOnlyOne() = test { e ->
        e.runner.listingDelayMs = 5_000
        val one = e.book(e.a)
        val two = e.book(e.b)
        e.alarms(one)
        e.alarms(two)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, e.begins(one) + e.begins(two))
        val states = setOf(e.row(one).state, e.row(two).state)
        assertEquals(setOf(RecordingState.RECORDING, RecordingState.MISSED), states)
    }

    @Test fun repeatedPrepareShowsOneWarning() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        e.alarms(id)
        runCurrent()
        e.coordinator.answer(id, PromptChoice.SWITCH_NOW)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertNull(e.coordinator.prompt.value)
    }

    // --- Review Focus 3 ---

    @Test fun twoWarningsAtOnceShowOneAndTheOtherClashes() = test { e ->
        e.playback.play(e.b)
        val first = e.book(e.a)
        val second = e.book(e.e4)
        e.alarms(first)
        e.alarms(second)
        runCurrent()
        assertEquals(first, (e.coordinator.prompt.value as RecordPrompt.Warning).id)
        e.coordinator.answer(first, PromptChoice.KEEP_WATCHING)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        advanceTimeBy(120_000)
        runCurrent()
        // Nothing takes the stream: the viewer kept watching, and the second booking was never shown.
        assertEquals(0, e.begins(first) + e.begins(second))
        assertEquals(PlaybackState.Buffering(e.b.id), e.playback.state.value)
        assertEquals(RecordingState.SKIPPED, e.row(first).state)
        assertEquals(RecordingState.MISSED, e.row(second).state)
        assertEquals(RecordingReason.CLASH, e.row(second).reason)
        assertNull(e.coordinator.prompt.value)
    }

    @Test fun twoWarningsAtOnceTimeoutTakesOnceAndTheOtherClashes() = test { e ->
        e.playback.play(e.b)
        val first = e.book(e.a)
        val second = e.book(e.e4)
        e.alarms(first)
        e.alarms(second)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, e.begins(first))
        assertEquals(0, e.begins(second))
        assertEquals(RecordingState.MISSED, e.row(second).state)
    }

    @Test fun firstAnswerWins() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        runCurrent()
        e.coordinator.answer(id, PromptChoice.SWITCH_NOW)
        e.coordinator.answer(id, PromptChoice.KEEP_WATCHING)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(RecordingState.RECORDING, e.row(id).state)
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun keepWatchingAtTheTimeoutGivesOneOutcome() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        // The timeout is due at this very instant; the answer arrives with it.
        e.coordinator.answer(id, PromptChoice.KEEP_WATCHING)
        runCurrent()
        e.coordinator.answer(id, PromptChoice.SWITCH_NOW)
        advanceTimeBy(60_000)
        runCurrent()
        val state = e.row(id).state
        val switched = e.begins(id) == 1 && state == RecordingState.RECORDING
        val kept = e.begins(id) == 0 && state == RecordingState.SKIPPED
        assertTrue("begins ${e.begins(id)}, state $state", switched != kept)
        assertNull(e.coordinator.prompt.value)
    }

    // --- user plays while a recording holds the stream ---

    @Test fun userPlayWhileHeldPrompts() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        val allowed = async { e.coordinator.beforeUserPlay(e.b) }
        runCurrent()
        val shown = e.coordinator.prompt.value as RecordPrompt.HoldsStream
        assertEquals(id, shown.id)
        assertEquals("The Thing", shown.title)
        assertEquals(e.row(id).window().until, shown.until)
        assertEquals(e.b, shown.pending)
        assertFalse(allowed.isCompleted)
        e.coordinator.answer(id, PromptChoice.CANCEL)
        runCurrent()
        assertFalse(allowed.await())
        assertNull(e.coordinator.prompt.value)
        assertEquals(RecordingState.RECORDING, e.row(id).state)
    }

    @Test fun stopRecordingThenPlays() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        val played = async { if (e.coordinator.beforeUserPlay(e.b)) e.playback.play(e.b) }
        runCurrent()
        e.coordinator.answer(id, PromptChoice.STOP_RECORDING)
        e.coordinator.answer(id, PromptChoice.CANCEL)
        runCurrent()
        played.await()
        assertEquals(listOf("begin $id", "stopRecording $id USER", "evict", "play ${e.b.id}"), e.log)
        assertEquals(RecordingState.STOPPED, e.row(id).state)
        assertEquals(RecordingReason.USER, e.row(id).reason)
        assertNull(e.coordinator.prompt.value)
    }

    @Test fun aQuestionLeftByALaterPlayDoesNothingOnStopRecording() = test { e ->
        val held = e.book(e.a, fromInMs = 0)
        e.runner.begin(held)
        val stale = async { e.coordinator.beforeUserPlay(e.b) }
        runCurrent()
        assertTrue(e.coordinator.prompt.value is RecordPrompt.HoldsStream)
        // The viewer moves on to another provider's channel, which needs no question.
        assertTrue(e.coordinator.beforeUserPlay(e.c))
        e.playback.play(e.c)
        assertFalse(stale.await())
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        e.coordinator.answer(held, PromptChoice.STOP_RECORDING)
        runCurrent()
        assertFalse(e.log.any { it.startsWith("stopRecording") })
        assertEquals(RecordingState.RECORDING, e.row(held).state)
    }

    @Test fun aWarningOverAPendingQuestionCancelsTheQuestion() = test { e ->
        e.playback.play(e.c)
        val held = e.book(e.a, fromInMs = 0)
        e.runner.begin(held)
        val allowed = async { e.coordinator.beforeUserPlay(e.b) }
        runCurrent()
        assertEquals(held, (e.coordinator.prompt.value as RecordPrompt.HoldsStream).id)
        val warned = e.book(e.d)
        e.alarms(warned)
        runCurrent()
        assertEquals(warned, (e.coordinator.prompt.value as RecordPrompt.Warning).id)
        assertFalse(allowed.await())
        assertEquals(RecordingState.RECORDING, e.row(held).state)
    }

    @Test fun openingRecordedChannelHandsOver() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        assertFalse(e.coordinator.beforeUserPlay(e.a))
        runCurrent()
        assertEquals(listOf(Handover(id)), e.handovers)
        assertNull(e.coordinator.prompt.value)
    }

    @Test fun userPlayOnAnotherProviderOrWithRoomIsAllowed() = test(limit = 2) { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        assertTrue(e.coordinator.beforeUserPlay(e.c))
        assertTrue(e.coordinator.beforeUserPlay(e.b))
        assertNull(e.coordinator.prompt.value)
    }

    @Test fun archiveOfTheRecordedChannelAsksInsteadOfHandingOver() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        val allowed = async { e.coordinator.beforeUserPlay(e.a, live = false) }
        runCurrent()
        assertEquals(id, (e.coordinator.prompt.value as RecordPrompt.HoldsStream).id)
        e.coordinator.answer(id, PromptChoice.CANCEL)
        assertFalse(allowed.await())
        assertTrue(e.handovers.isEmpty())
    }

    @Test fun aRecordingDueJustAfterAnAllowedPlayWarns() = test { e ->
        // The gate let a play through; the player has not opened it yet.
        assertTrue(e.coordinator.beforeUserPlay(e.b))
        val id = e.book(e.a, fromInMs = 0)
        e.alarms(id)
        runCurrent()
        assertEquals(Use.PLAYER, (e.coordinator.prompt.value as RecordPrompt.Warning).stops)
        assertEquals(0, e.begins(id))
    }

    @Test fun anAllowedPlayThatNeverOpensStopsCountingAfterFiveSeconds() = test { e ->
        assertTrue(e.coordinator.beforeUserPlay(e.b))
        advanceTimeBy(5_000)
        val id = e.book(e.a, fromInMs = 0)
        e.alarms(id)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(1, e.begins(id))
    }

    @Test fun aTakeOverThatFailsReportsTheWatchedChannel() = test { e ->
        e.playback.play(e.b)
        e.runner.failOnBegin = true
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(PlaybackState.Idle, e.playback.state.value)
        assertEquals(TakeFailed(id, e.b.id), e.coordinator.takeFailed.value)
        assertTrue(e.handovers.isEmpty())
        e.coordinator.consumeTakeFailed()
        assertNull(e.coordinator.takeFailed.value)
    }

    @Test fun aTakeOverStoppedByTheViewerIsNotReportedAsFailed() = test { e ->
        e.playback.play(e.b)
        e.runner.firstBytesAtOnce = false
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        runCurrent()
        e.runner.stop(id, RecordingReason.USER)
        runCurrent()
        assertNull(e.coordinator.takeFailed.value)
        assertTrue(e.handovers.isEmpty())
    }

    @Test fun aFailedTakeOverOfACastReportsNoChannel() = test { e ->
        e.playback.play(e.b)
        e.cast.casting()
        e.runner.failOnBegin = true
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(TakeFailed(id, null), e.coordinator.takeFailed.value)
    }

    @Test fun handoverWaitsForSomeoneToTakeIt() = test { e ->
        e.playback.play(e.a)
        val id = e.book(e.a)
        e.alarms(id)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(Handover(id)), e.handovers)
        assertNull("consumed by the collector", e.coordinator.handover.value)
    }

    // --- STOP, recovery and the alarms left behind ---

    @Test fun repeatedStopAlarmsDuringTheGraceStopOnce() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        advanceTimeBy(e.row(id).window().until.toEpochMilli() - e.clock().toEpochMilli())
        e.coordinator.onAlarm(AlarmAction.STOP, id)
        advanceTimeBy(10_000)
        e.coordinator.onAlarm(AlarmAction.STOP, id)
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(1, e.log.count { it == "stopRecording $id NONE" })
    }

    @Test fun stopAlarmStopsARunThatOverruns() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        advanceTimeBy(e.row(id).window().until.toEpochMilli() - e.clock().toEpochMilli())
        e.coordinator.onAlarm(AlarmAction.STOP, id)
        runCurrent()
        // The recorder gets a moment to end on its own first.
        assertEquals(RecordingState.RECORDING, e.row(id).state)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(e.log.contains("stopRecording $id NONE"))
        assertEquals(RecordingState.STOPPED, e.row(id).state)
    }

    @Test fun stopAlarmBeforeAMovedDeadlineLeavesTheRunAlone() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.runner.begin(id)
        e.coordinator.onAlarm(AlarmAction.STOP, id)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(e.log.any { it.startsWith("stopRecording") })
        assertEquals(RecordingState.RECORDING, e.row(id).state)
    }

    @Test fun recoverGoesThroughTheBudget() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a, fromInMs = -60_000, state = RecordingState.RECORDING)
        e.coordinator.recover()
        runCurrent()
        assertEquals(0, e.begins(id))
        assertEquals(Use.PLAYER, (e.coordinator.prompt.value as RecordPrompt.Warning).stops)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("play ${e.b.id}", "stop", "evict", "begin $id"), e.log)
    }

    @Test fun handledOverdueStartAndStopAreNotRearmed() = test { e ->
        val manager = e.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarms = RecordAlarms(e.context, e.db, settings = { AppSettings() }, clock = e.clock)
        e.rearm = { alarms.rearm() }
        fun armed() = shadowOf(manager).scheduledAlarms.mapNotNull { shadowOf(it.operation).savedIntent.alarm() }

        // Overdue: the padded start passed a minute ago.
        val id = e.book(e.a, fromInMs = -60_000)
        alarms.rearm()
        // Too late to warn ahead: only the start is armed.
        assertEquals(listOf(AlarmAction.START to id), armed())
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(RecordingState.RECORDING, e.row(id).state)
        alarms.rearm()
        assertEquals(listOf(AlarmAction.STOP to id), armed())

        advanceTimeBy(e.row(id).window().until.toEpochMilli() - e.clock().toEpochMilli() + 1_000)
        e.coordinator.onAlarm(AlarmAction.STOP, id)
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(e.row(id).state != RecordingState.RECORDING)
        alarms.rearm()
        assertEquals(emptyList<Pair<AlarmAction, Long>>(), armed())
    }

    @Test fun handledPrepareThatIsSkippedOrMissedIsNotRearmed() = test { e ->
        val manager = e.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarms = RecordAlarms(e.context, e.db, settings = { AppSettings() }, clock = e.clock)
        e.rearm = { alarms.rearm() }
        fun armed() = shadowOf(manager).scheduledAlarms.mapNotNull { shadowOf(it.operation).savedIntent.alarm() }

        e.playback.play(e.b)
        val skipped = e.book(e.a)
        e.alarms(skipped)
        runCurrent()
        e.coordinator.answer(skipped, PromptChoice.KEEP_WATCHING)
        runCurrent()
        assertEquals(RecordingState.SKIPPED, e.row(skipped).state)
        assertEquals(emptyList<Pair<AlarmAction, Long>>(), armed())
    }

    // --- the invariant ---

    @Test fun neverTwoStreamsOpen() = test { e ->
        // A warning that times out, then plays on other providers and on the held one.
        e.playback.play(e.b)
        val first = e.book(e.a)
        e.alarms(first)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, e.conn.max)
        if (e.coordinator.beforeUserPlay(e.c)) e.playback.play(e.c)
        val played = async { if (e.coordinator.beforeUserPlay(e.b)) e.playback.play(e.b) }
        runCurrent()
        e.coordinator.answer(first, PromptChoice.STOP_RECORDING)
        runCurrent()
        played.await()

        // The same channel handed over, then the preview taken, then a recording kept off by another.
        val second = e.book(e.b)
        e.alarms(second)
        advanceTimeBy(30_000)
        runCurrent()
        e.uses.setPreviewVisible(true)
        e.playback.play(e.c)
        val third = e.book(e.c)
        e.alarms(third)
        advanceTimeBy(30_000)
        runCurrent()
        val fourth = e.book(e.a)
        e.alarms(fourth)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(RecordingState.MISSED, e.row(fourth).state)
        assertEquals(1, e.conn.max)
    }

    // --- START: the run begins in standby ---

    @Test fun startWithNothingPendingBeginsTheRunAfterHoldingTheTvAwake() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        // Before the alarm's broadcast is released: the wake lock and the service, nothing ended.
        assertEquals(listOf("hold $id", "started $id"), e.platformLog.take(2))
        assertFalse(e.platformLog.contains("ended $id"))
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(RecordingState.RECORDING, e.row(id).state)
        // Recording: the service holds the TV awake from here.
        assertEquals(listOf("hold $id", "started $id", "release $id"), e.platformLog)
        e.runner.stop(id, RecordingReason.USER)
        runCurrent()
        assertEquals("ended $id", e.platformLog.last())
    }

    @Test fun startWhileTheWarningStillShowsTakesAsOnTimeout() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a)
        e.coordinator.onAlarm(AlarmAction.PREPARE, id)
        runCurrent()
        assertTrue(e.coordinator.prompt.value is RecordPrompt.Warning)
        // The warning's own countdown has not run out (it stood still while the TV slept).
        advanceTimeBy(10_000)
        e.coordinator.onAlarm(AlarmAction.START, id)
        assertEquals(listOf("hold $id", "started $id"), e.platformLog)
        runCurrent()
        assertNull(e.coordinator.prompt.value)
        assertEquals(listOf("play ${e.b.id}", "stop", "evict", "begin $id"), e.log)
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun aLateBookingWithOnlyAStartDecidesEverything() = test { e ->
        e.playback.play(e.b)
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(RecordPrompt.Warning(id, "Film4", "The Thing", Use.PLAYER, 30), e.coordinator.prompt.value)
        assertEquals(0, e.begins(id))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(listOf(Handover(id)), e.handovers)
    }

    @Test fun prepareAloneNeverBeginsARun() = test { e ->
        val id = e.book(e.a)
        e.coordinator.onAlarm(AlarmAction.PREPARE, id)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, e.begins(id))
        assertEquals(RecordingState.SCHEDULED, e.row(id).state)
        assertTrue(e.platformLog.isEmpty())
    }

    @Test fun aStartThatIsMissedEndsItsHold() = test { e ->
        val first = e.book(e.b, fromInMs = 0)
        e.runner.begin(first)
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(RecordingState.MISSED, e.row(id).state)
        assertEquals(listOf("hold $id", "started $id", "release $id", "ended $id"), e.platformLog)
    }

    @Test fun aRepeatedStartIsIgnored() = test { e ->
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(1, e.begins(id))
        assertEquals(1, e.platformLog.count { it == "started $id" })
    }

    // --- take-over bookkeeping ---

    @Test fun aPlayAskedForWhileTheViewerIsReleasedSeesTheRecording() = test { e ->
        e.playback.play(e.a)
        val id = e.book(e.a, fromInMs = 0)
        var asked: kotlinx.coroutines.Deferred<Boolean>? = null
        // Run at once, up to its first suspension: a play that sees no recording would be allowed right here.
        e.onEvict = {
            asked = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { e.coordinator.beforeUserPlay(e.b) }
        }
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(id, (e.coordinator.prompt.value as RecordPrompt.HoldsStream).id)
        e.coordinator.answer(id, PromptChoice.CANCEL)
        runCurrent()
        assertFalse(asked!!.await())
    }

    @Test fun aHandoverLeftUnconsumedGoesStale() = test(collectHandovers = false) { e ->
        e.playback.play(e.a)
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(Handover(id), e.coordinator.handover.value)
        advanceTimeBy(61_000)
        assertNull(e.coordinator.consumeHandover())
        assertNull(e.coordinator.handover.value)
    }

    @Test fun aHandoverToARecordingThatEndedIsDropped() = test(collectHandovers = false) { e ->
        e.playback.play(e.a)
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        e.runner.stop(id, RecordingReason.USER)
        assertNull(e.coordinator.consumeHandover())
    }

    @Test fun aFreshHandoverIsTaken() = test(collectHandovers = false) { e ->
        e.playback.play(e.a)
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        assertEquals(Handover(id), e.coordinator.consumeHandover())
        assertNull(e.coordinator.consumeHandover())
    }

    @Test fun aFailedTakeOverLeftTooLongIsDropped() = test { e ->
        e.playback.play(e.b)
        e.runner.failOnBegin = true
        val id = e.book(e.a, fromInMs = 0)
        e.coordinator.onAlarm(AlarmAction.START, id)
        runCurrent()
        e.coordinator.answer(id, PromptChoice.SWITCH_NOW)
        runCurrent()
        assertEquals(TakeFailed(id, e.b.id), e.coordinator.takeFailed.value)
        advanceTimeBy(61_000)
        assertNull(e.coordinator.consumeTakeFailed())
        assertNull(e.coordinator.takeFailed.value)
    }
}
