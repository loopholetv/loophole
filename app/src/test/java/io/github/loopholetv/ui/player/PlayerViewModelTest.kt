package io.github.loopholetv.ui.player

import io.github.loopholetv.ui.splash.StartGate

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.NoCast
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.player.Handoff
import io.github.loopholetv.player.PipGate
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.PlayerTracks
import io.github.loopholetv.player.PreviewHold
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.SleepState
import io.github.loopholetv.player.SleepTimer
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.TrackChoice
import io.github.loopholetv.player.TrackId
import io.github.loopholetv.player.TrackOption
import io.github.loopholetv.player.TrackType
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.player.catchup.COMMIT_DELAY_MS
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.data.settings.KeyBindings
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.ui.guide.GuideFormat
import java.time.ZoneOffset
import io.github.loopholetv.ui.manage.ChannelEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Follows the [Playback] contract: play(active channel) resumes, it never reloads. */
    private class FakePlayback(initial: PlaybackState = PlaybackState.Idle) : Playback {
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<PlaybackState> = _state
        val played = mutableListOf<Long>()
        val loads = mutableListOf<Long>()
        var stops = 0
        override fun play(channel: ChannelEntity) {
            played += channel.id
            val s = _state.value
            val fromArchive = _mode.value is PlayMode.Archive
            _mode.value = PlayMode.Live
            val active = (s is PlaybackState.Buffering && s.channelId == channel.id) ||
                (s is PlaybackState.Playing && s.channelId == channel.id)
            if (active && !fromArchive) return
            loads += channel.id
            _state.value = PlaybackState.Buffering(channel.id)
        }
        override fun stop() { stops++; log += "stop"; _mode.value = PlayMode.Live; _state.value = PlaybackState.Idle }

        val _mode = MutableStateFlow<PlayMode>(PlayMode.Live)
        override val mode: StateFlow<PlayMode> = _mode
        val archives = mutableListOf<ArchiveRequest>()
        var position = 0L
        var pauses = 0
        override fun playArchive(channel: ChannelEntity, request: ArchiveRequest) {
            archives += request
            position = 0
            _mode.value = PlayMode.Archive(channel.id, request)
            _state.value = PlaybackState.Buffering(channel.id)
        }
        override fun pause() { pauses++ }
        override fun positionMs(): Long = position
        val restarts = mutableListOf<Long>()
        override fun restart(channel: ChannelEntity) {
            restarts += channel.id
            stop()
            play(channel)
        }
        fun fail(id: Long, message: String) { _state.value = PlaybackState.Error(id, message) }
        fun playing(id: Long) { _state.value = PlaybackState.Playing(id, StreamTags.NONE) }

        val log = mutableListOf<String>()
        val _tracks = MutableStateFlow(PlayerTracks.NONE)
        override val tracks: StateFlow<PlayerTracks> = _tracks
        val selected = mutableListOf<Pair<TrackType, TrackChoice>>()
        override fun selectTrack(type: TrackType, choice: TrackChoice) { selected += type to choice }
        val delays = mutableListOf<Int>()
        override fun setAudioDelay(ms: Int) { delays += ms }
        val handOffs = mutableListOf<Long>()
        var handOffResult: Handoff? = Handoff(ResolvedStream("http://example.test/live.m3u8", "UA", emptyMap()), hls = true)
        override suspend fun handOff(channel: ChannelEntity): Handoff? {
            handOffs += channel.id
            stop()
            return handOffResult
        }
    }

    private fun channel(id: Long, group: String = "All", number: Int = id.toInt()) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = number, name = "Ch $id", streamUrl = "http://x/$id",
        logo = null, groupTitle = group, groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = "e$id",
    )

    /** In-memory channel source with the DAO's ordering and wrap-around. */
    private class FakeChannels(var all: List<ChannelEntity>, val favourites: List<ChannelEntity> = emptyList()) : PlayerChannels {
        var aroundCalls = 0
        /** Custom labels (a renamed group); others fall back to [PlayerChannels.label]'s default. */
        var labels: Map<ChannelScope, String> = emptyMap()
        override suspend fun label(scope: ChannelScope) = labels[scope] ?: scope.label()
        /** Visible channels only, like the DAO. */
        private fun inScope(scope: ChannelScope) = when (scope) {
            ChannelScope.All -> all.sortedBy { it.number }
            ChannelScope.Favourites -> favourites.sortedBy { it.number }
            is ChannelScope.Group -> all.filter { it.playlistId == scope.playlistId && it.groupTitle == scope.title }.sortedBy { it.number }
        }.filter { it.visible }
        /** Edits the stored channel [id] (what a ChannelEdits write does to the row). */
        fun edit(id: Long, f: (ChannelEntity) -> ChannelEntity) { all = all.map { if (it.id == id) f(it) else it } }
        override suspend fun byId(id: Long) = all.firstOrNull { it.id == id }
        override suspend fun byNumber(n: Int) = all.firstOrNull { it.guideNumber == n && it.visible }
        override suspend fun contains(channel: ChannelEntity, scope: ChannelScope) = inScope(scope).any { it.id == channel.id }
        override suspend fun neighbour(current: ChannelEntity, scope: ChannelScope, direction: Int): ChannelEntity? {
            val list = inScope(scope)
            if (list.isEmpty()) return null
            return if (direction >= 0) {
                list.firstOrNull { it.number > current.number } ?: list.first()
            } else {
                list.lastOrNull { it.number < current.number } ?: list.last()
            }
        }
        override suspend fun around(current: ChannelEntity, scope: ChannelScope, each: Int): List<ChannelEntity> {
            aroundCalls++
            val list = inScope(scope)
            return list.filter { it.number < current.number }.takeLast(each) + current +
                list.filter { it.number > current.number }.take(each)
        }
    }

    private val now = Instant.parse("2026-11-08T01:47:00Z")
    private val twenty = (1L..20L).map { channel(it) }
    private val mixed = listOf(channel(1, "News"), channel(2, "Sport"), channel(3, "News"), channel(4, "Sport"), channel(5, "News"))
    private lateinit var source: FakeChannels

    private fun vm(
        playback: FakePlayback,
        channels: List<ChannelEntity> = twenty,
        start: Long = 1,
        scope: ChannelScope = ChannelScope.All,
        favourites: List<ChannelEntity> = emptyList(),
        favouriteRefs: MutableStateFlow<Set<ChannelRef>> = MutableStateFlow(emptySet()),
        recent: List<ChannelEntity> = emptyList(),
        labels: Map<ChannelScope, String> = emptyMap(),
        nowAndNext: suspend (ChannelEntity, Instant) -> List<ProgrammeEntity> = { _, _ -> emptyList() },
        startGate: StartGate = StartGate(),
        sleepTimer: SleepTimer? = null,
        previewHold: PreviewHold = PreviewHold(),
        pipSupported: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(),
        pipGate: PipGate? = null,
        remote: RemoteSettings = RemoteSettings(),
        archiveFrom: Instant? = null,
        elapsedMs: () -> Long = { 0L },
        cast: CastControls = NoCast,
    ): PlayerViewModel {
        source = FakeChannels(channels, favourites).also { it.labels = labels }
        return PlayerViewModel(
            channelId = start,
            scope = scope,
            playback = playback,
            channels = source,
            nowAndNext = nowAndNext,
            clock = { now },
            formatClock = { "clock" },
            favouriteRefs = favouriteRefs,
            toggleFavourite = { c ->
                val added = c.ref !in favouriteRefs.value
                favouriteRefs.value = if (added) favouriteRefs.value + c.ref else favouriteRefs.value - c.ref
                added
            },
            recent = flowOf(recent),
            startGate = startGate,
            settings = settings,
            updatePlayback = { t -> settingsWrites += 1; settings.value = t(settings.value) },
            channelPlayback = { c ->
                flow {
                    prefsGate?.await()
                    emitAll(prefs.map { it[c.id] ?: ChannelPlaybackPrefs.NONE })
                }
            },
            setAspect = { c, mode ->
                aspectFailure?.let { throw it }
                aspectWrites += c.id to mode
                prefs.value = prefs.value + (c.id to (prefs.value[c.id] ?: ChannelPlaybackPrefs.NONE).copy(aspect = mode))
            },
            sleepTimer = sleepTimer,
            previewHold = previewHold,
            pipSupported = pipSupported,
            savedState = savedState,
            pipGate = pipGate,
            remote = flowOf(remote),
            archiveFrom = archiveFrom,
            elapsedMs = elapsedMs,
            format = GuideFormat(true, ZoneOffset.UTC),
            cast = cast,
        )
    }

    private val settings = MutableStateFlow(PlaybackSettings())
    private var settingsWrites = 0
    private val prefs = MutableStateFlow<Map<Long, ChannelPlaybackPrefs>>(emptyMap())
    /** When set, reading a channel's playback settings waits for it. */
    private var prefsGate: CompletableDeferred<Unit>? = null
    private val aspectWrites = mutableListOf<Pair<Long, AspectMode?>>()
    /** When set, saving an aspect throws it. */
    private var aspectFailure: Throwable? = null

    /** Clears [vm] as leaving the screen does (its scope is cancelled, then onCleared runs). */
    private fun clear(vm: PlayerViewModel) {
        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
        })[PlayerViewModel::class.java]
        store.clear()
    }

    /** The current channel's options, opened from the quick menu (held OK / Menu, then Channel options). */
    private fun openOptions(vm: PlayerViewModel) {
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.OPTIONS)
    }

    private fun TestScope.settle() { advanceTimeBy(ZAP_SETTLE_MS + 1); runCurrent() }

    private fun TestScope.key(vm: PlayerViewModel, code: Int): Boolean = vm.onKey(code).also { runCurrent() }

    private fun PlayerViewModel.channelId() = ui.value!!.channel.id
    private fun PlayerViewModel.state() = ui.value!!

    private val dpadUp = KeyEvent.KEYCODE_DPAD_UP
    private val chUp = KeyEvent.KEYCODE_CHANNEL_UP
    private val dpadDown = KeyEvent.KEYCODE_DPAD_DOWN
    private val chDown = KeyEvent.KEYCODE_CHANNEL_DOWN
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER

    @Test fun playbackWaitsForTheStartGate() = runTest {
        val playback = FakePlayback()
        val gate = StartGate().apply { close() }
        val vm = vm(playback, start = 3, startGate = gate)
        runCurrent()
        assertEquals("the screen is ready behind the gate", 3L, vm.channelId())
        assertEquals("nothing plays while the gate is closed", emptyList<Long>(), playback.played)
        vm.onScreenStarted() // the screen starting under the splash must not play either
        runCurrent()
        assertEquals(emptyList<Long>(), playback.played)

        gate.open()
        runCurrent()
        assertEquals(listOf(3L), playback.played)
        vm.onScreenStarted() // already playing: no second start
        runCurrent()
        assertEquals(listOf(3L), playback.played)
    }

    @Test fun channelKeysMoveWithinScopeAndWrap() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, mixed, start = 1, scope = ChannelScope.Group(1, "News"))
        runCurrent()

        assertTrue(key(vm, chDown)) // Down = lower number, wrapping from the first to the last of the group
        assertEquals(5L, vm.channelId())
        assertTrue("a zap shows the banner", vm.state().bannerVisible)
        assertFalse("not the full overlay", vm.state().overlayVisible)
        settle()
        assertEquals(listOf(1L, 5L), playback.played)

        assertTrue(key(vm, chUp)) // Up = higher number, wrapping back to the first
        settle()
        assertEquals(1L, vm.channelId())
        assertTrue(key(vm, chUp))
        settle()
        assertEquals(3L, vm.channelId())
        assertEquals(listOf(1L, 5L, 1L, 3L), playback.played)
    }

    @Test fun channelKeysZapAndCoalesceIntoOnePlay() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        repeat(5) {
            assertTrue("Channel Up keeps zapping while the banner shows", key(vm, KeyEvent.KEYCODE_CHANNEL_UP))
            advanceTimeBy(100)
            runCurrent()
        }
        assertEquals("the target moves per press", 6L, vm.channelId())
        assertTrue(vm.state().bannerVisible)
        assertFalse(vm.state().overlayVisible)
        assertEquals("nothing played while the key is held", listOf(1L), playback.played)
        settle()
        assertEquals(listOf(1L, 6L), playback.played)

        assertTrue(key(vm, ok)) // OK opens the full overlay with the row
        assertTrue(vm.state().overlayVisible)
        assertEquals(6L, vm.row.value.currentId)
        assertFalse("with the full overlay the D-pad belongs to the row", key(vm, dpadDown))
        settle()
        assertEquals(6L, vm.channelId())
        assertEquals(listOf(1L, 6L), playback.played)
    }

    @Test fun dpadUpDownOnBareVideoOpensOverlayWithoutZapping() = runTest {
        listOf(dpadUp, dpadDown).forEach { code ->
            val playback = FakePlayback()
            val vm = vm(playback, start = 5)
            runCurrent()
            vm.onBack() // bare video
            assertFalse(vm.state().overlayVisible)
            assertTrue(key(vm, code))
            settle()
            assertEquals(5L, vm.channelId())
            assertEquals("no zap", listOf(5L), playback.played)
            assertTrue(vm.state().overlayVisible)
            assertEquals(5L, vm.row.value.currentId)
        }
    }

    @Test fun dpadUpDownWhileBannerShowsOpensOverlayWithoutZapping() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent() // entry banner is up
        assertTrue(vm.state().bannerVisible)
        assertTrue(key(vm, dpadUp))
        settle()
        assertEquals(1L, vm.channelId())
        assertEquals(listOf(1L), playback.played)
        assertTrue(vm.state().overlayVisible)
    }

    @Test fun dpadUpDownOnErrorCardOpensOverlayOkStaysRetry() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        playback.fail(1, "x")
        runCurrent()
        vm.onBack()
        assertTrue("OK is Try again", key(vm, ok))
        assertEquals("retried", listOf(1L, 1L), playback.played)
        assertFalse(vm.state().overlayVisible)
        assertTrue(key(vm, dpadDown))
        assertTrue(vm.state().overlayVisible)
        assertEquals(listOf(1L, 1L), playback.played)
    }

    @Test fun digitsJumpAfterTimeout() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, KeyEvent.KEYCODE_1)
        key(vm, KeyEvent.KEYCODE_2)
        assertEquals("12", vm.state().numberEntry)
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS - 1)
        runCurrent()
        assertEquals("12", vm.state().numberEntry)
        assertEquals(1L, vm.channelId())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(12L, vm.channelId())
        assertNull(vm.state().numberEntry)
        assertEquals(listOf(1L, 12L), playback.played)
    }

    @Test fun fourDigitsJumpImmediately() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, twenty + channel(99, number = 1234))
        runCurrent()
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_4).forEach { key(vm, it) }
        assertEquals(99L, vm.channelId())
        assertNull(vm.state().numberEntry)
        assertEquals(listOf(1L, 99L), playback.played)
    }

    @Test fun okCommitsNumberEntry() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, KeyEvent.KEYCODE_1)
        key(vm, KeyEvent.KEYCODE_2)
        assertTrue(key(vm, ok))
        assertEquals("no 1.5 s wait", 12L, vm.channelId())
        assertFalse("OK committed the number, it did not open the overlay", vm.state().overlayVisible)
        assertEquals(listOf(1L, 12L), playback.played)
    }

    @Test fun unknownNumberShowsMessage() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, KeyEvent.KEYCODE_9)
        key(vm, KeyEvent.KEYCODE_9)
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals("No channel 99", vm.state().message)
        assertEquals(1L, vm.channelId())
        advanceTimeBy(MESSAGE_MS + 1)
        runCurrent()
        assertNull(vm.state().message)
        assertEquals(listOf(1L), playback.played)
    }

    @Test fun bannerShowsRenamedGroupLabel() = runTest {
        val playback = FakePlayback()
        val news = ChannelScope.Group(1, "News")
        val vm = vm(playback, mixed, start = 1, scope = news, labels = mapOf(news to "Headlines"))
        runCurrent()
        assertEquals("Headlines", vm.state().groupLabel)
        key(vm, KeyEvent.KEYCODE_4) // channel 4 is in Sport: switches to All channels
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals("All channels", vm.state().groupLabel)
    }

    @Test fun numberJumpOutsideGroupSwitchesToAllChannels() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, mixed, start = 1, scope = ChannelScope.Group(1, "News"))
        runCurrent()
        assertEquals("News", vm.state().groupLabel)
        key(vm, KeyEvent.KEYCODE_4) // channel 4 is in Sport
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals(4L, vm.channelId())
        assertEquals("All channels", vm.state().groupLabel)
        key(vm, chUp)
        settle()
        assertEquals("zapping now runs through all channels", 5L, vm.channelId())
        key(vm, chUp)
        settle()
        assertEquals(1L, vm.channelId())
        key(vm, chUp)
        settle()
        assertEquals(2L, vm.channelId())
    }

    @Test fun numberJumpOutsideFavouritesSwitchesToAllChannels() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, mixed, start = 3, scope = ChannelScope.Favourites, favourites = listOf(mixed[0], mixed[2]))
        runCurrent()
        key(vm, KeyEvent.KEYCODE_1) // a favourite: the scope stays
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals(1L, vm.channelId())
        assertEquals("Favourites", vm.state().groupLabel)
        key(vm, KeyEvent.KEYCODE_2) // not a favourite
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals(2L, vm.channelId())
        assertEquals("All channels", vm.state().groupLabel)
        key(vm, chUp)
        settle()
        assertEquals(3L, vm.channelId())
    }

    @Test fun favouritesScopeLabel() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, mixed, start = 3, scope = ChannelScope.Favourites, favourites = listOf(mixed[0], mixed[2], mixed[4]))
        runCurrent()
        assertEquals("Favourites", vm.state().groupLabel)
        key(vm, chUp)
        settle()
        assertEquals(5L, vm.channelId())
        key(vm, chUp)
        settle()
        assertEquals("wraps within the favourites", 1L, vm.channelId())
    }

    @Test fun backHidesOverlayThenExits() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        key(vm, ok)
        assertTrue(vm.state().overlayVisible)
        assertTrue(vm.onBack())
        assertFalse(vm.state().overlayVisible)
        assertFalse(vm.state().bannerVisible)
        assertFalse(vm.onBack())
    }

    @Test fun overlayAutoHidesAfterFiveSeconds() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        key(vm, ok)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(vm.state().overlayVisible)
        assertFalse(key(vm, KeyEvent.KEYCODE_DPAD_LEFT)) // any key restarts the 5 s
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(vm.state().overlayVisible)
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(vm.state().overlayVisible)
    }

    @Test fun bannerAutoHidesAfterFiveSeconds() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        key(vm, chUp)
        assertTrue(vm.state().bannerVisible)
        advanceTimeBy(OVERLAY_TIMEOUT_MS + 1)
        runCurrent()
        assertFalse(vm.state().bannerVisible)
    }

    @Test fun errorStateKeepsChannelSwitching() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        playback.fail(1, "Ch 1: can't play this stream")
        runCurrent()
        assertEquals("Ch 1: can't play this stream", vm.state().error)
        assertTrue("with the banner up, OK opens the full overlay", key(vm, ok))
        assertTrue(vm.state().overlayVisible)
        assertTrue(vm.onBack()) // bare video with the error card
        assertTrue("on bare video OK is the error card's Try again", key(vm, ok))
        assertFalse(vm.state().overlayVisible)

        assertTrue(key(vm, chUp))
        settle()
        assertEquals(2L, vm.channelId())
        assertNull("the old channel's error is not shown for the new one", vm.state().error)
        assertEquals(listOf(1L, 1L, 2L), playback.played)

        playback.fail(2, "Ch 2: can't play this stream")
        runCurrent()
        vm.retry()
        assertEquals(listOf(1L, 1L, 2L, 2L), playback.loads)
    }

    @Test fun sameChannelFromPreviewDoesNotReplay() = runTest {
        val playback = FakePlayback(PlaybackState.Playing(1, StreamTags("FHD", "50 FPS", "STEREO")))
        val vm = vm(playback)
        runCurrent()
        assertTrue("no reload of the previewing channel", playback.loads.isEmpty())
        assertEquals(0, playback.stops)
        assertEquals(StreamTags("FHD", "50 FPS", "STEREO"), vm.state().tags)
    }

    @Test fun channelUpKeyAlwaysZaps() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, ok)
        assertFalse(key(vm, dpadDown))
        assertEquals(1L, vm.channelId())
        assertTrue(key(vm, KeyEvent.KEYCODE_CHANNEL_UP))
        settle()
        assertEquals(2L, vm.channelId())
        assertTrue(key(vm, KeyEvent.KEYCODE_PAGE_DOWN))
        settle()
        assertEquals(1L, vm.channelId())
        assertEquals(listOf(1L, 2L, 1L), playback.played)
    }

    @Test fun exitFlushesPendingZapThenIgnoresKeys() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, chUp)
        assertTrue(vm.onBack()) // hides the banner
        assertFalse(vm.onBack()) // back to the guide: the pending target plays now
        assertEquals(listOf(1L, 2L), playback.played)
        key(vm, chUp)
        key(vm, KeyEvent.KEYCODE_5)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("nothing switches after leaving", listOf(1L, 2L), playback.played)
        assertEquals(2L, vm.channelId())
    }

    @Test fun guideTileLeavesAndIgnoresLaterZaps() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, KeyEvent.KEYCODE_CHANNEL_UP)
        vm.leave()
        assertEquals(listOf(1L, 2L), playback.played)
        settle()
        key(vm, KeyEvent.KEYCODE_CHANNEL_UP)
        settle()
        assertEquals(listOf(1L, 2L), playback.played)
    }

    @Test fun screenStopCancelsPendingSettleAndResumeShowsTarget() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        key(vm, chUp)
        vm.onScreenStopped()
        playback.stop() // MainActivity stops playback when the app leaves the foreground
        settle()
        assertEquals("the settle was cancelled", listOf(1L), playback.played)
        vm.onScreenStarted()
        assertEquals(listOf(1L, 2L), playback.played)
    }

    @Test fun zapLoadsRowOnce() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        assertEquals("the banner needs no row", 0, source.aroundCalls)
        key(vm, ok)
        assertEquals(1, source.aroundCalls)
        key(vm, KeyEvent.KEYCODE_CHANNEL_UP)
        settle()
        assertEquals(2L, vm.row.value.currentId)
        assertEquals(2, source.aroundCalls)
    }

    @Test fun rowExtendsAtEitherEnd() = runTest {
        val sixty = (1L..60L).map { channel(it) }
        val vm = vm(FakePlayback(), sixty, start = 30)
        runCurrent()
        key(vm, ok)
        assertEquals((15L..45L).toList(), vm.row.value.channels.map { it.id })
        vm.onRowEdge(atEnd = true)
        runCurrent()
        assertEquals((15L..60L).toList(), vm.row.value.channels.map { it.id })
        vm.onRowEdge(atEnd = true) // already at the group's real end
        runCurrent()
        assertEquals(46, vm.row.value.channels.size)
        vm.onRowEdge(atEnd = false)
        runCurrent()
        assertEquals((1L..60L).toList(), vm.row.value.channels.map { it.id })
        assertEquals(30L, vm.row.value.currentId)
    }

    @Test fun okWhileHiddenShowsOverlayAndNumpadEntersNumbers() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        vm.onBack() // hide the entry banner
        assertTrue(key(vm, ok))
        assertTrue(vm.state().overlayVisible)
        assertTrue(key(vm, KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals("7", vm.state().numberEntry)
    }

    // --- Favourites and History ---

    @Test fun toggleFavouriteShowsMessage() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        assertFalse(vm.state().favourite)
        vm.toggleFavourite()
        runCurrent()
        assertEquals("Added to favourites", vm.state().message)
        assertTrue(vm.state().favourite)
        vm.toggleFavourite()
        runCurrent()
        assertEquals("Removed from favourites", vm.state().message)
        assertFalse(vm.state().favourite)
        advanceTimeBy(MESSAGE_MS + 1)
        runCurrent()
        assertNull(vm.state().message)
    }

    @Test fun favouriteFlagFollowsCurrentChannel() = runTest {
        val refs = MutableStateFlow(setOf(channel(2).ref))
        val vm = vm(FakePlayback(), start = 1, favouriteRefs = refs)
        runCurrent()
        assertFalse(vm.state().favourite)
        key(vm, chUp)
        assertTrue("channel 2 is a favourite", vm.state().favourite)
        refs.value = emptySet()
        runCurrent()
        assertFalse("follows the favourites flow", vm.state().favourite)
        refs.value = setOf(channel(2).ref)
        runCurrent()
        assertTrue(vm.state().favourite)
        key(vm, chUp)
        assertFalse(vm.state().favourite)
    }

    @Test fun historyTileSwapsRowAndChannelsTileRestores() = runTest {
        val recent = listOf(channel(7), channel(1), channel(12))
        val vm = vm(FakePlayback(), start = 1, recent = recent)
        runCurrent()
        key(vm, ok)
        assertEquals(RowMode.CHANNELS, vm.row.value.mode)
        vm.showHistory()
        runCurrent()
        assertEquals(RowMode.HISTORY, vm.row.value.mode)
        assertEquals(listOf(7L, 1L, 12L), vm.row.value.channels.map { it.id })
        assertEquals("the current channel is focused", 1L, vm.row.value.currentId)
        vm.showChannels()
        runCurrent()
        assertEquals(RowMode.CHANNELS, vm.row.value.mode)
        assertEquals(1L, vm.row.value.currentId)
        assertEquals((1L..16L).toList(), vm.row.value.channels.map { it.id })
    }

    @Test fun historyWithoutCurrentFocusesFirstTileAndEmptyHistoryIsEmpty() = runTest {
        val vm = vm(FakePlayback(), start = 1, recent = listOf(channel(7), channel(9)))
        runCurrent()
        key(vm, ok)
        vm.showHistory()
        runCurrent()
        assertEquals(0, vm.row.value.currentIndex)
        assertEquals(7L, vm.row.value.currentId)

        val empty = vm(FakePlayback(), start = 1)
        runCurrent()
        key(empty, ok)
        empty.showHistory()
        runCurrent()
        assertEquals(RowMode.HISTORY, empty.row.value.mode)
        assertTrue(empty.row.value.channels.isEmpty())
    }

    @Test fun playFromHistoryOutsideScopeSwitchesToAll() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, mixed, start = 1, scope = ChannelScope.Group(1, "News"), recent = listOf(channel(2, "Sport"), channel(3, "News")))
        runCurrent()
        key(vm, ok)
        vm.showHistory()
        runCurrent()
        vm.playFromHistory(channel(3, "News"))
        runCurrent()
        assertEquals(3L, vm.channelId())
        assertEquals("in scope: unchanged", "News", vm.state().groupLabel)
        vm.playFromHistory(channel(2, "Sport"))
        runCurrent()
        assertEquals(2L, vm.channelId())
        assertEquals(listOf(1L, 3L, 2L), playback.played)
        assertEquals(ChannelScope.All.label(), vm.state().groupLabel)
        key(vm, chUp)
        assertEquals("zaps within all channels now", 3L, vm.channelId())
    }

    @Test fun playFromHistoryKeepsHistoryRow() = runTest {
        val recent = listOf(channel(7), channel(1), channel(12))
        val vm = vm(FakePlayback(), start = 1, recent = recent)
        runCurrent()
        key(vm, ok)
        vm.showHistory()
        runCurrent()
        vm.playFromHistory(channel(12))
        runCurrent()
        assertEquals(12L, vm.channelId())
        assertEquals(RowMode.HISTORY, vm.row.value.mode)
        assertEquals(listOf(7L, 1L, 12L), vm.row.value.channels.map { it.id })
        assertEquals(12L, vm.row.value.currentId)
        key(vm, chUp) // a zap goes back to the channel row
        settle()
        assertEquals(RowMode.CHANNELS, vm.row.value.mode)
        assertEquals(13L, vm.row.value.currentId)
    }

    @Test fun reopeningOverlayShowsChannels() = runTest {
        val vm = vm(FakePlayback(), start = 1, recent = listOf(channel(7), channel(1)))
        runCurrent()
        key(vm, ok)
        vm.showHistory()
        runCurrent()
        vm.hideOverlay()
        key(vm, ok)
        runCurrent()
        assertEquals(RowMode.CHANNELS, vm.row.value.mode)
        assertEquals(1L, vm.row.value.currentId)
    }

    @Test fun historyRowEdgeDoesNotLoad() = runTest {
        val recent = listOf(channel(7), channel(1), channel(12))
        val vm = vm(FakePlayback(), start = 1, recent = recent)
        runCurrent()
        key(vm, ok)
        vm.showHistory()
        runCurrent()
        val calls = source.aroundCalls
        vm.onRowEdge(atEnd = true)
        vm.onRowEdge(atEnd = false)
        runCurrent()
        assertEquals(calls, source.aroundCalls)
        assertEquals(listOf(7L, 1L, 12L), vm.row.value.channels.map { it.id })
    }

    @Test fun optionsFromQuickMenuOrTileLongPress() = runTest {
        val vm = vm(FakePlayback(), start = 4)
        runCurrent()
        vm.onBack() // bare video
        assertNull(vm.optionsFor.value)
        openOptions(vm)
        assertEquals(4L, vm.optionsFor.value?.id)
        assertFalse("the options don't open the overlay", vm.state().overlayVisible)
        vm.dismissOptions()
        assertNull(vm.optionsFor.value)

        key(vm, ok) // overlay up; a long press on a tile opens that tile's channel
        vm.onTileLongPress(channel(6))
        assertEquals(6L, vm.optionsFor.value?.id)
        assertFalse("the overlay hides under the dialog", vm.state().overlayVisible)
        vm.toggleFavourite(vm.optionsFor.value!!)
        vm.dismissOptions()
        runCurrent()
        assertEquals("Added to favourites", vm.state().message)
        assertFalse("the current channel is unchanged", vm.state().favourite)
    }

    @Test fun shortOkStillOpensOverlay() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        vm.onBack()
        assertTrue(key(vm, ok))
        assertTrue(vm.state().overlayVisible)
        assertNull(vm.optionsFor.value)
    }

    @Test fun optionsOpenIgnoresFurtherLongPresses() = runTest {
        val vm = vm(FakePlayback(), start = 4)
        runCurrent()
        key(vm, ok) // overlay up
        vm.onTileLongPress(channel(6))
        vm.onLongOk() // a leaked repeat reaching the root
        assertNull("no quick menu over the options", vm.quickMenu.value)
        vm.onTileLongPress(channel(7))
        assertEquals("the tile's channel stays", 6L, vm.optionsFor.value?.id)
        vm.dismissOptions()
        openOptions(vm)
        assertEquals(4L, vm.optionsFor.value?.id)
    }

    // --- Display values, shifted now/next, channel edits ------------------------------

    private fun programme(id: Long, epgId: String, start: String, stop: String, title: String) = ProgrammeEntity(
        id = id, epgChannelId = epgId, start = Instant.parse(start), stop = Instant.parse(stop), title = title,
        subTitle = null, description = null, category = null, generation = 1,
    )

    /** Stored programmes read like ShiftedProgrammes.nowAndNext: by guideEpgId, at shown time, shifted. */
    private fun shiftedNowAndNext(stored: List<ProgrammeEntity>): suspend (ChannelEntity, Instant) -> List<ProgrammeEntity> =
        { c, at ->
            stored.filter { it.epgChannelId == c.guideEpgId }.map { it.shiftedBy(c.epgShiftMinutes) }
                .filter { it.stop > at }.sortedBy { it.start }.take(2)
        }

    @Test fun nowAndNextShifted() = runTest {
        // now = 01:47Z. Stored 00:00–01:00 and 01:00–02:00 on the manual guide id "g1"; +60 min shift.
        val stored = listOf(
            programme(1, "g1", "2026-11-08T00:00:00Z", "2026-11-08T01:00:00Z", "Early"),
            programme(2, "g1", "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "Late"),
            programme(3, "e1", "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "Automatic match"),
        )
        val c1 = channel(1).copy(guideEpgId = "g1", epgShiftMinutes = 60)
        val vm = vm(FakePlayback(), listOf(c1, channel(2)), nowAndNext = shiftedNowAndNext(stored))
        runCurrent()
        assertEquals("Early", vm.state().now?.title)
        assertEquals(Instant.parse("2026-11-08T01:00:00Z"), vm.state().now?.start)
        assertEquals("Late", vm.state().next?.title)
        assertEquals(Instant.parse("2026-11-08T02:00:00Z"), vm.state().next?.start)

        key(vm, ok) // the row's tiles use the same lookup
        runCurrent()
        assertEquals("Early", vm.row.value.titles[1L])
    }

    @Test fun renameUpdatesInfoBarName() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        openOptions(vm)
        val options = vm.optionsFor.value!!
        vm.pickEdit(options, ChannelEdit.Rename)
        assertNull("the options close", vm.optionsFor.value)
        assertEquals(ChannelEditing(options, ChannelEdit.Rename), vm.editing.value)
        source.edit(3) { it.copy(customName = "My Three", customLogo = "http://example.test/3.png") }
        vm.onEditDone()
        runCurrent()
        assertNull(vm.editing.value)
        assertEquals("My Three", vm.state().channel.displayName)
        assertEquals("http://example.test/3.png", vm.state().channel.displayLogo)
        assertEquals(3L, vm.channelId())
    }

    @Test fun renameRelabelsTheShownErrorCard() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        val oldName = vm.state().channel.displayName
        playback.fail(3, "$oldName: can't play this stream")
        runCurrent()
        assertEquals("$oldName: can't play this stream", vm.state().error)
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Rename)
        source.edit(3) { it.copy(customName = "My Three") }
        vm.onEditDone()
        runCurrent()
        assertEquals("My Three: can't play this stream", vm.state().error)
    }

    @Test fun renameOfATileUpdatesTheRow() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        key(vm, ok)
        runCurrent()
        vm.onTileLongPress(channel(4))
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Rename)
        source.edit(4) { it.copy(customName = "Four") }
        vm.onEditDone()
        runCurrent()
        assertEquals("Four", vm.row.value.channels.first { it.id == 4L }.displayName)
        assertEquals("the current channel stays", 3L, vm.channelId())
    }

    @Test fun groupLabelUsesCustomName() = runTest {
        val sport = ChannelScope.Group(1, "Sport")
        val vm = vm(FakePlayback(), mixed, start = 2, scope = sport, labels = mapOf(sport to "Football"))
        runCurrent()
        assertEquals("Football", vm.state().groupLabel)
        key(vm, chUp) // zapping within the group keeps the custom name
        settle()
        assertEquals(4L, vm.channelId())
        assertEquals("Football", vm.state().groupLabel)
    }

    @Test fun numberEntryUsesGuideNumber() = runTest {
        // Playlist 2 continues the numbering: its 1..3 are guide numbers 4..6.
        val first = (1L..3L).map { channel(it) }
        val second = (1..3).map { n ->
            channel(10L + n, number = n).copy(playlistId = 2, key = "p2-$n", guideNumber = 3 + n)
        }
        val hidden = channel(20, number = 7).copy(visible = false)
        val playback = FakePlayback()
        val vm = vm(playback, first + second + hidden, start = 1)
        runCurrent()
        key(vm, KeyEvent.KEYCODE_5)
        assertEquals("5", vm.state().numberEntry)
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals("guide number 5 is playlist 2's channel 2", 12L, vm.channelId())
        assertEquals(5, vm.state().channel.guideNumber)

        key(vm, KeyEvent.KEYCODE_7) // hidden: unreachable by number
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals("No channel 7", vm.state().message)
        assertEquals(12L, vm.channelId())
        assertEquals(listOf(1L, 12L), playback.played)
    }

    @Test fun hideCurrentChannelZapsToNext() = runTest {
        val playback = FakePlayback()
        val news = ChannelScope.Group(1, "News")
        val vm = vm(playback, mixed, start = 3, scope = news)
        runCurrent()
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Hide)
        source.edit(3) { it.copy(visible = false) }
        vm.onEditDone()
        runCurrent()
        assertEquals("the next channel of the group", 5L, vm.channelId())
        assertEquals("played at once", listOf(3L, 5L), playback.played)
        assertFalse(vm.exitToGuide.value)

        key(vm, ok) // the row no longer lists the hidden channel
        runCurrent()
        assertFalse(vm.row.value.channels.any { it.id == 3L })
        key(vm, chDown) // zapping skips it too
        settle()
        assertEquals(1L, vm.channelId())
    }

    @Test fun hideLastChannelInScopeExits() = runTest {
        val playback = FakePlayback()
        val sport = ChannelScope.Group(1, "Sport")
        val vm = vm(playback, mixed.filter { it.id != 4L }, start = 2, scope = sport)
        runCurrent()
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Hide)
        source.edit(2) { it.copy(visible = false) }
        vm.onEditDone()
        runCurrent()
        assertTrue("back to the guide", vm.exitToGuide.value)
        assertEquals("the hidden channel's stream stops", 1, playback.stops)
        assertFalse("keys are ignored after leaving", key(vm, chUp))
        settle()
        assertEquals(listOf(2L), playback.played)
    }

    @Test fun hideOtherChannelFromTileRemovesItFromRow() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        key(vm, ok)
        runCurrent()
        vm.onTileLongPress(channel(4))
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Hide)
        source.edit(4) { it.copy(visible = false) }
        vm.onEditDone()
        runCurrent()
        assertEquals(3L, vm.channelId())
        assertFalse(vm.row.value.channels.any { it.id == 4L })
        assertEquals(3L, vm.row.value.currentId)
    }

    @Test fun editingBlocksOptionsUntilDone() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.TimeShift)
        vm.onLongOk() // a held OK leaking past the dialog
        assertNull("no second options panel while a dialog is open", vm.optionsFor.value)
        assertNull("no quick menu while a dialog is open", vm.quickMenu.value)
        vm.onEditDone()
        runCurrent()
        openOptions(vm)
        assertEquals(3L, vm.optionsFor.value?.id)
    }

    @Test fun shiftEditReloadsNowAndNext() = runTest {
        val stored = listOf(
            programme(1, "e1", "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "One"),
            programme(2, "e1", "2026-11-08T02:00:00Z", "2026-11-08T03:00:00Z", "Two"),
        )
        val vm = vm(FakePlayback(), listOf(channel(1)), nowAndNext = shiftedNowAndNext(stored))
        runCurrent()
        assertEquals("One", vm.state().now?.title)
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.TimeShift)
        source.edit(1) { it.copy(epgShiftMinutes = -60) } // One airs 00:00–01:00 now, Two 01:00–02:00
        vm.onEditDone()
        runCurrent()
        assertEquals("Two", vm.state().now?.title)
        assertNull(vm.state().next)
    }

    // --- Edit keeps now/next, start-channel checks, exact-now shifts ------------------------------------------------------------------------

    @Test fun editKeepsNowAndNextUntilReloaded() = runTest {
        val stored = listOf(
            programme(1, "e1", "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "One"),
            programme(2, "e1", "2026-11-08T02:00:00Z", "2026-11-08T03:00:00Z", "Two"),
        )
        val read = shiftedNowAndNext(stored)
        var gate: CompletableDeferred<Unit>? = null
        val vm = vm(FakePlayback(), listOf(channel(1)), nowAndNext = { c, at -> gate?.await(); read(c, at) })
        runCurrent()
        assertEquals("One", vm.state().now?.title)
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Rename)
        source.edit(1) { it.copy(customName = "Renamed") }
        gate = CompletableDeferred()
        vm.onEditDone()
        runCurrent()
        assertEquals("the new name shows at once", "Renamed", vm.state().channel.displayName)
        assertEquals("no \"No information\" flash while reloading", "One", vm.state().now?.title)
        assertEquals("Two", vm.state().next?.title)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals("One", vm.state().now?.title)
    }

    @Test fun removingTheGuideClearsNowAndNext() = runTest {
        val stored = listOf(programme(1, "e1", "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "One"))
        val vm = vm(FakePlayback(), listOf(channel(1)), nowAndNext = shiftedNowAndNext(stored))
        runCurrent()
        assertEquals("One", vm.state().now?.title)
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.ChooseGuide)
        source.edit(1) { it.copy(guideEpgId = null) }
        vm.onEditDone()
        runCurrent()
        assertNull(vm.state().now)
        assertNull(vm.state().next)
    }

    @Test fun hiddenStartChannelExitsToGuide() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, listOf(channel(1), channel(2).copy(visible = false)), start = 2)
        runCurrent()
        assertNull("nothing is shown", vm.ui.value)
        assertTrue("back to the guide", vm.exitToGuide.value)
        assertTrue("the hidden channel never plays", playback.played.isEmpty())
        assertEquals(1, playback.stops)
    }

    @Test fun missingStartChannelExitsToGuide() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, listOf(channel(1)), start = 99)
        runCurrent()
        assertNull(vm.ui.value)
        assertTrue(vm.exitToGuide.value)
        assertTrue(playback.played.isEmpty())
    }

    @Test fun shiftedProgrammeStartingExactlyNowIsNow() = runTest {
        // now = 01:47Z. Stored 00:47–01:47 and 01:47–02:47; +60 min shows them 01:47–02:47 and 02:47–03:47.
        val stored = listOf(
            programme(1, "e1", "2026-11-08T00:47:00Z", "2026-11-08T01:47:00Z", "Starts now"),
            programme(2, "e1", "2026-11-08T01:47:00Z", "2026-11-08T02:47:00Z", "Later"),
        )
        val vm = vm(FakePlayback(), listOf(channel(1).copy(epgShiftMinutes = 60)), nowAndNext = shiftedNowAndNext(stored))
        runCurrent()
        assertEquals("Starts now", vm.state().now?.title)
        assertEquals("Later", vm.state().next?.title)
    }

    // --- Quick menu ---------------------------------------------------------------------

    private fun PlayerViewModel.menu() = quickMenu.value!!
    private fun PlayerViewModel.entry(item: QuickItem) = menu().entries.first { it.item == item }
    private fun PlayerViewModel.choices() = (menu().panel as QuickPanel.Choices).options

    private fun TestScope.timer() = SleepTimer(backgroundScope, onExpire = {}, elapsed = { testScheduler.currentTime })

    private fun TestScope.events(vm: PlayerViewModel): List<PlayerEvent> =
        mutableListOf<PlayerEvent>().also { list -> backgroundScope.launch { vm.events.collect { list += it } } }

    @Test fun menuKeyOpensQuickMenu() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        assertTrue("the banner shows after opening", vm.state().bannerVisible)
        vm.onLongOk()
        val menu = vm.quickMenu.value
        assertNotNull(menu)
        assertEquals(QuickItem.SEARCH, menu!!.focus)
        assertNull(menu.panel)
        assertEquals(quickItems(pipSupported = false), menu.entries.map { it.item })
        assertFalse(vm.state().overlayVisible)
        assertFalse(vm.state().bannerVisible)
        assertNull(vm.optionsFor.value)
        assertTrue(vm.dialogOpen())
        vm.dismissQuickMenu()
        assertNull(vm.quickMenu.value)
        assertFalse(vm.dialogOpen())
    }

    @Test fun upDownStillOnlyOpenOverlay() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        vm.onBack() // bare video
        assertTrue(key(vm, dpadUp))
        assertTrue(vm.state().overlayVisible)
        vm.onBack()
        assertTrue(key(vm, dpadDown))
        assertTrue(vm.state().overlayVisible)
        settle()
        assertEquals(3L, vm.channelId())
        assertEquals(listOf(3L), playback.played)
        assertNull(vm.quickMenu.value)
        vm.onBack()
        assertFalse("Left stays unbound", key(vm, KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse("Right stays unbound", key(vm, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(vm.quickMenu.value)
        assertFalse(vm.state().overlayVisible)
    }

    @Test fun tileLongPressStillOpensChannelOptions() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        key(vm, ok) // overlay up
        vm.onTileLongPress(channel(5))
        assertEquals(5L, vm.optionsFor.value?.id)
        assertNull(vm.quickMenu.value)
        assertTrue(vm.dialogOpen())
    }

    @Test fun channelOptionsItemOpensOptions() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.OPTIONS)
        assertNull("the quick menu closes", vm.quickMenu.value)
        assertEquals(3L, vm.optionsFor.value?.id)
        assertTrue(vm.dialogOpen())
    }

    @Test fun lastItemRefocusedOnReopen() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.ASPECT)
        assertEquals(QuickItem.ASPECT, (vm.menu().panel as QuickPanel.Choices).item)
        vm.closePanel()
        assertNull(vm.menu().panel)
        assertEquals(QuickItem.ASPECT, vm.menu().focus)
        vm.dismissQuickMenu()
        vm.onLongOk()
        assertEquals(QuickItem.ASPECT, vm.menu().focus)
        assertNull(vm.menu().panel)
    }

    private val sampleTracks = PlayerTracks.NONE.copy(
        audio = listOf(TrackOption(TrackId("a1", 0), "English", true), TrackOption(TrackId("a2", 0), "German", false)),
        text = listOf(TrackOption(TrackId("t1", 0), "English", false)),
        textOff = true,
    )

    @Test fun trackPickCallsPlayback() = runTest {
        val playback = FakePlayback().also { it._tracks.value = sampleTracks }
        val vm = vm(playback)
        runCurrent()
        vm.onLongOk()
        assertEquals("English", vm.entry(QuickItem.AUDIO).value)
        vm.pickQuickItem(QuickItem.AUDIO)
        assertEquals(listOf("English", "German"), vm.choices().map { it.label })
        assertEquals(listOf(true, false), vm.choices().map { it.selected })
        vm.pickChoice(QuickItem.AUDIO, vm.choices()[1].key)
        assertEquals(listOf(TrackType.AUDIO to TrackChoice.Pick(TrackId("a2", 0))), playback.selected)
        assertNull("back to the row", vm.menu().panel)
        assertEquals(QuickItem.AUDIO, vm.menu().focus)
    }

    @Test fun subtitlesOffChoice() = runTest {
        val playback = FakePlayback().also {
            it._tracks.value = sampleTracks.copy(text = listOf(TrackOption(TrackId("t1", 0), "English", true)), textOff = false)
        }
        val vm = vm(playback)
        runCurrent()
        vm.onLongOk()
        assertEquals("English", vm.entry(QuickItem.SUBTITLES).value)
        vm.pickQuickItem(QuickItem.SUBTITLES)
        assertEquals(listOf("Off", "English"), vm.choices().map { it.label })
        vm.pickChoice(QuickItem.SUBTITLES, vm.choices()[0].key)
        assertEquals(listOf(TrackType.TEXT to TrackChoice.Off), playback.selected)
    }

    @Test fun quickMenuListsFollowLateTracks() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.VIDEO)
        assertEquals(listOf("Auto"), vm.choices().map { it.label })
        playback._tracks.value = PlayerTracks.NONE.copy(
            video = listOf(TrackOption(TrackId("v", 0), "1080p", false), TrackOption(TrackId("v", 1), "720p", false)),
        )
        runCurrent()
        assertEquals(listOf("Auto", "1080p", "720p"), vm.choices().map { it.label })
        vm.pickChoice(QuickItem.VIDEO, vm.choices()[2].key)
        assertEquals(listOf(TrackType.VIDEO to TrackChoice.Pick(TrackId("v", 1))), playback.selected)
        vm.pickQuickItem(QuickItem.VIDEO)
        vm.pickChoice(QuickItem.VIDEO, vm.choices()[0].key)
        assertEquals(TrackType.VIDEO to TrackChoice.Auto, playback.selected.last())
    }

    @Test fun audioDelayStepsAndClamps() = runTest {
        val playback = FakePlayback()
        val vm = vm(playback)
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.AUDIO_DELAY)
        assertEquals(QuickPanel.Delay(0), vm.menu().panel)
        repeat(25) {
            vm.stepAudioDelay(+1)
            advanceTimeBy(100)
        }
        assertEquals(QuickPanel.Delay(1000), vm.menu().panel)
        assertEquals("applied live on every step", 25, playback.delays.size)
        assertEquals(50, playback.delays.first())
        assertEquals(1000, playback.delays.last())
        assertEquals("nothing written while stepping", 0, settingsWrites)
        advanceTimeBy(AUDIO_DELAY_WRITE_MS + 1)
        runCurrent()
        assertEquals("written once", 1, settingsWrites)
        assertEquals(1000, settings.value.audioDelayMs)
        assertEquals("+1000 ms", vm.entry(QuickItem.AUDIO_DELAY).value)
        repeat(45) { vm.stepAudioDelay(-1) }
        advanceTimeBy(AUDIO_DELAY_WRITE_MS + 1)
        runCurrent()
        assertEquals(2, settingsWrites)
        assertEquals(-1000, settings.value.audioDelayMs)
        assertEquals("−1000 ms", vm.entry(QuickItem.AUDIO_DELAY).value)
        vm.closePanel()
        assertEquals(QuickItem.AUDIO_DELAY, vm.menu().focus)
    }

    @Test fun pendingAudioDelayWrittenWhenLeaving() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.AUDIO_DELAY)
        repeat(3) { vm.stepAudioDelay(+1) }
        advanceTimeBy(100)
        vm.leave()
        clear(vm)
        runCurrent()
        assertEquals(1, settingsWrites)
        assertEquals(150, settings.value.audioDelayMs)
    }

    @Test fun pendingAudioDelayWrittenWhenClearedWithoutLeave() = runTest {
        val vm = vm(FakePlayback())
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.AUDIO_DELAY)
        vm.stepAudioDelay(-1)
        clear(vm)
        advanceTimeBy(AUDIO_DELAY_WRITE_MS + 1)
        runCurrent()
        assertEquals(1, settingsWrites)
        assertEquals(-50, settings.value.audioDelayMs)
    }

    @Test fun aspectSaveFailureRevertsToSavedAspect() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(aspect = AspectMode.STRETCH))
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        assertEquals(AspectMode.STRETCH, vm.aspect.value)
        aspectFailure = java.io.IOException("disk full")
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.ASPECT)
        vm.pickChoice(QuickItem.ASPECT, vm.choices().first { it.label == "Zoom" }.key)
        runCurrent()
        assertEquals(AspectMode.STRETCH, vm.aspect.value)
        assertEquals(ASPECT_SAVE_FAILED, vm.state().message)
    }

    @Test fun aspectSaveCancellationIsNotSwallowed() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        aspectFailure = kotlinx.coroutines.CancellationException("left")
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.ASPECT)
        vm.pickChoice(QuickItem.ASPECT, vm.choices().first { it.label == "Zoom" }.key)
        runCurrent()
        assertNull("a cancelled save shows no error", vm.state().message)
    }

    @Test fun storedAudioDelayShownInTheMenu() = runTest {
        settings.value = PlaybackSettings(audioDelayMs = -150)
        val vm = vm(FakePlayback())
        runCurrent()
        vm.onLongOk()
        assertEquals("−150 ms", vm.entry(QuickItem.AUDIO_DELAY).value)
    }

    @Test fun aspectPickSavedForChannel() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        assertEquals(AspectMode.FIT, vm.aspect.value)
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.ASPECT)
        vm.pickChoice(QuickItem.ASPECT, vm.choices().first { it.label == "Zoom" }.key)
        runCurrent()
        assertEquals(listOf(3L to AspectMode.ZOOM), aspectWrites)
        assertEquals(AspectMode.ZOOM, vm.aspect.value)
        assertEquals("Zoom", vm.entry(QuickItem.ASPECT).value)
        vm.pickQuickItem(QuickItem.ASPECT)
        vm.pickChoice(QuickItem.ASPECT, vm.choices().first { it.label == "Fit" }.key)
        runCurrent()
        assertEquals(3L to null, aspectWrites.last())
        assertEquals(AspectMode.FIT, vm.aspect.value)
    }

    @Test fun aspectFollowsChannelOnZap() = runTest {
        prefs.value = mapOf(1L to ChannelPlaybackPrefs(aspect = AspectMode.ZOOM))
        val vm = vm(FakePlayback(), start = 1)
        runCurrent()
        assertEquals(AspectMode.ZOOM, vm.aspect.value)
        assertEquals(AspectMode.ZOOM, vm.channelPlaybackPrefs.value.aspect)
        key(vm, chUp)
        settle()
        assertEquals(2L, vm.channelId())
        assertEquals(AspectMode.FIT, vm.aspect.value)
        key(vm, chDown)
        settle()
        assertEquals(AspectMode.ZOOM, vm.aspect.value)
    }

    @Test fun favouriteItemLabelToggles() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        vm.onLongOk()
        assertEquals("Add to favourites", vm.entry(QuickItem.FAVOURITE).label)
        vm.pickQuickItem(QuickItem.FAVOURITE)
        runCurrent()
        assertEquals("Remove from favourites", vm.entry(QuickItem.FAVOURITE).label)
        assertEquals("the menu stays open on the tile", QuickItem.FAVOURITE, vm.menu().focus)
        assertTrue(vm.state().favourite)
        vm.pickQuickItem(QuickItem.FAVOURITE)
        runCurrent()
        assertEquals("Add to favourites", vm.entry(QuickItem.FAVOURITE).label)
    }

    @Test fun sleepTimerChoicesStartAndCancel() = runTest {
        val timer = timer()
        val vm = vm(FakePlayback(), sleepTimer = timer)
        runCurrent()
        vm.onLongOk()
        assertEquals("Off", vm.entry(QuickItem.SLEEP).value)
        vm.pickQuickItem(QuickItem.SLEEP)
        vm.pickChoice(QuickItem.SLEEP, vm.choices().first { it.label == "30 min" }.key)
        assertEquals(SleepState.Running(30, 1_800_000), timer.state.value)
        runCurrent()
        assertEquals("30 min left", vm.entry(QuickItem.SLEEP).value)
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()
        assertEquals("the tile counts down", "25 min left", vm.entry(QuickItem.SLEEP).value)
        vm.pickQuickItem(QuickItem.SLEEP)
        assertEquals("30 min", vm.choices().single { it.selected }.label)
        vm.pickChoice(QuickItem.SLEEP, vm.choices().first { it.label == "Off" }.key)
        assertEquals(SleepState.Off, timer.state.value)
        runCurrent()
        assertEquals("Off", vm.entry(QuickItem.SLEEP).value)
    }

    @Test fun sleepWarningFlagChangesOnlyAtTheWarning() = runTest {
        val timer = timer()
        val vm = vm(FakePlayback(), sleepTimer = timer)
        val seen = mutableListOf<Boolean>()
        val job = launch { vm.sleepWarningShown.collect { seen += it } }
        runCurrent()
        timer.start(15)
        advanceTimeBy(13 * 60_000L)
        runCurrent()
        assertEquals("the countdown alone emits nothing", listOf(false), seen)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(listOf(false, true), seen)
        vm.consumeSleepWarning()
        runCurrent()
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }

    @Test fun sleepWarningAnyKeyKeepsWatching() = runTest {
        val timer = timer()
        val vm = vm(FakePlayback(), sleepTimer = timer)
        runCurrent()
        vm.onBack() // bare video
        timer.start(15)
        advanceTimeBy(14 * 60_000L + 30_000)
        runCurrent()
        assertTrue(vm.sleep.value is SleepState.Warning)
        assertTrue("the key is consumed", key(vm, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(SleepState.Running(15, 900_000), timer.state.value)
        assertFalse("nothing else happens", vm.state().overlayVisible)
        assertFalse("no warning, nothing to consume", vm.consumeSleepWarning())

        advanceTimeBy(14 * 60_000L + 30_000)
        runCurrent()
        assertTrue(vm.consumeSleepWarning())
        assertEquals(SleepState.Running(15, 900_000), timer.state.value)

        advanceTimeBy(14 * 60_000L + 30_000)
        runCurrent()
        assertTrue(timer.state.value is SleepState.Warning)
        vm.onLongOk() // using the quick menu also keeps watching
        assertTrue(timer.state.value is SleepState.Running)
    }

    @Test fun sleepExpiryHoldsPreviewAndExits() = runTest {
        val timer = timer()
        val hold = PreviewHold()
        val playback = FakePlayback()
        val vm = vm(playback, sleepTimer = timer, previewHold = hold)
        runCurrent()
        timer.start(15)
        advanceTimeBy(15 * 60_000L + 1_000)
        runCurrent()
        assertTrue(vm.exitToGuide.value)
        assertTrue("the guide keeps its preview stopped", hold.consume())
        playback.stop()
        vm.onScreenStarted()
        assertEquals("nothing resumes", listOf(1L), playback.played)
        assertFalse(key(vm, chUp))
    }

    @Test fun externalChannelHandsOffInsteadOfPlaying() = runTest {
        prefs.value = mapOf(
            3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"),
            5L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"),
            8L to ChannelPlaybackPrefs(externalPlayer = "ask"),
        )
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        val events = events(vm)
        runCurrent()
        assertEquals("opening full screen hands off", listOf(3L), playback.handOffs)
        assertEquals(emptyList<Long>(), playback.played)
        val first = events.single() as PlayerEvent.LaunchExternal
        assertEquals("org.videolan.vlc", first.choice)
        assertEquals("Ch 3", first.title)

        key(vm, chUp) // channel 4 plays here
        settle()
        assertEquals(listOf(4L), playback.played)
        key(vm, chUp) // channel 5 hands off once the zap settles
        runCurrent()
        assertEquals(listOf(3L), playback.handOffs)
        settle()
        assertEquals(listOf(3L, 5L), playback.handOffs)
        assertEquals(listOf(4L), playback.played)

        key(vm, KeyEvent.KEYCODE_8) // a number
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals(listOf(3L, 5L, 8L), playback.handOffs)
        assertEquals("ask", (events.last() as PlayerEvent.LaunchExternal).choice)

        vm.playFromRow(channel(6)) // a tile
        runCurrent()
        assertEquals(listOf(4L, 6L), playback.played)
        assertEquals(3, events.size)
    }

    /** Casting as the player sees it: starting casts to nobody yet; [onStop] runs as it stops. */
    private class FakeCast(initial: CastState = CastState.Off) : CastControls {
        val current = MutableStateFlow(initial)
        override val state: StateFlow<CastState> = current
        var starts = 0
        val stops = mutableListOf<StopReason>()
        var onStop: (StopReason) -> Unit = {}
        override fun startCasting() {
            starts++
            current.value = CastState.Casting(0)
        }
        override fun stopCasting(reason: StopReason) {
            stops += reason
            onStop(reason)
            current.value = CastState.Off
        }
    }

    @Test fun castTileStartsAndStops() = runTest {
        val cast = FakeCast()
        val vm = vm(FakePlayback(), start = 3, cast = cast)
        runCurrent()
        assertNull("no label while not casting", vm.castingLabel.value)
        vm.onLongOk()
        assertEquals("Cast to all TVs", vm.entry(QuickItem.CAST).label)
        vm.pickQuickItem(QuickItem.CAST)
        runCurrent()
        assertEquals(1, cast.starts)
        assertEquals("the menu stays open on the tile", QuickItem.CAST, vm.menu().focus)
        assertEquals("Stop casting", vm.entry(QuickItem.CAST).label)
        assertEquals("Casting", vm.castingLabel.value)
        cast.current.value = CastState.Casting(1)
        runCurrent()
        assertEquals("Casting to 1 TV", vm.castingLabel.value)
        cast.current.value = CastState.Casting(3)
        runCurrent()
        assertEquals("Casting to 3 TVs", vm.castingLabel.value)

        vm.pickQuickItem(QuickItem.CAST)
        runCurrent()
        assertEquals(listOf(StopReason.STOPPED), cast.stops)
        assertEquals(1, cast.starts)
        assertEquals("Cast to all TVs", vm.entry(QuickItem.CAST).label)
        assertNull(vm.castingLabel.value)
    }

    @Test fun castTileLabelFollowsState() = runTest {
        val cast = FakeCast()
        val vm = vm(FakePlayback(), start = 3, cast = cast)
        runCurrent()
        vm.onLongOk()
        assertEquals("Cast to all TVs", vm.entry(QuickItem.CAST).label)
        // Started or stopped elsewhere (the TVs list, the guide): the open menu follows.
        cast.current.value = CastState.Casting(2)
        runCurrent()
        assertEquals("Stop casting", vm.entry(QuickItem.CAST).label)
        cast.current.value = CastState.Off
        runCurrent()
        assertEquals("Cast to all TVs", vm.entry(QuickItem.CAST).label)
    }

    @Test fun externalChannelStopsCastBeforeHandOff() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val cast = FakeCast(CastState.Casting(2))
        var handOffsAtStop = -1
        cast.onStop = { handOffsAtStop = playback.handOffs.size }
        val vm = vm(playback, start = 3, cast = cast)
        val events = events(vm)
        runCurrent()
        assertEquals(listOf(StopReason.EXTERNAL_PLAYER), cast.stops)
        assertEquals("the cast ends before the hand-off", 0, handOffsAtStop)
        assertEquals(listOf(3L), playback.handOffs)
        assertTrue(events.single() is PlayerEvent.LaunchExternal)

        // Not casting: a hand-off stops nothing.
        val idle = FakeCast()
        vm(FakePlayback(), start = 3, cast = idle)
        runCurrent()
        assertEquals(emptyList<StopReason>(), idle.stops)
        // A channel that plays here never stops the cast.
        val casting = FakeCast(CastState.Casting(0))
        vm(FakePlayback(), start = 4, cast = casting)
        runCurrent()
        assertEquals(emptyList<StopReason>(), casting.stops)
    }

    @Test fun externalResolveFailureShowsMessage() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "ask"))
        val playback = FakePlayback().also { it.handOffResult = null }
        val vm = vm(playback, start = 3)
        val events = events(vm)
        runCurrent()
        assertEquals(listOf(3L), playback.handOffs)
        assertTrue(events.isEmpty())
        assertEquals("Couldn't open this channel in the other player.", vm.state().message)
        assertFalse(vm.exitToGuide.value)
        assertEquals(emptyList<Long>(), playback.played)
    }

    @Test fun externalResolveFailureNotPlayedHereOnResume() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "ask"))
        val playback = FakePlayback().also { it.handOffResult = null }
        val vm = vm(playback, start = 3)
        runCurrent()
        vm.onScreenStopped()
        vm.onScreenStarted()
        runCurrent()
        assertEquals(emptyList<Long>(), playback.played)
    }

    @Test fun externalLaunchFailurePlayedHereAgainOnResume() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        vm.onExternalFailed()
        playback.stop()
        vm.onScreenStopped()
        vm.onScreenStarted()
        runCurrent()
        assertEquals(listOf(3L, 3L), playback.played)
    }

    @Test fun externalLaunchedHoldsPreviewAndExits() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val hold = PreviewHold()
        val playback = FakePlayback()
        val vm = vm(playback, start = 3, previewHold = hold)
        runCurrent()
        vm.onExternalLaunched()
        assertTrue(vm.exitToGuide.value)
        assertTrue(hold.consume())
        assertEquals(emptyList<Long>(), playback.played)
    }

    @Test fun externalLaunchedExitsEvenIfResumedLater() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        vm.onExternalLaunched()
        vm.onScreenStopped()
        vm.onScreenStarted() // back from the other player, before the screen pops
        runCurrent()
        assertTrue(vm.exitToGuide.value)
        assertEquals("nothing plays here", emptyList<Long>(), playback.played)
        assertFalse(key(vm, chUp))
    }

    @Test fun externalLaunchFailurePlaysHere() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        vm.onExternalFailed()
        assertEquals("That player isn't installed any more. Playing here instead.", vm.state().message)
        assertEquals(listOf(3L), playback.played)
        assertFalse(vm.exitToGuide.value)
    }

    @Test fun decoderEditLeavesTheRestartToPlayback() = runTest {
        // Playback restarts a shown channel whose decoders change (from any screen); the screen
        // must not restart it a second time.
        val playback = FakePlayback()
        val vm = vm(playback, start = 3)
        runCurrent()
        playback.playing(3)
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE))
        runCurrent()
        assertEquals(emptyList<Long>(), playback.restarts)
        assertEquals(0, playback.stops)
        assertEquals(3L, vm.channelId())
    }

    @Test fun searchAndSettingsItemsSendEvents() = runTest {
        val vm = vm(FakePlayback())
        val events = events(vm)
        runCurrent()
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.SEARCH)
        assertNull(vm.quickMenu.value)
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.SETTINGS)
        vm.onLongOk()
        vm.pickQuickItem(QuickItem.CHANNELS)
        runCurrent()
        assertEquals(listOf(PlayerEvent.OpenSearch, PlayerEvent.OpenSettings), events)
        assertNull(vm.quickMenu.value)
        assertTrue("Channels opens the channel bar", vm.state().overlayVisible)
    }

    @Test fun pipHidesOverlayAndMenus() = runTest {
        val vm = vm(FakePlayback(), start = 3)
        runCurrent()
        key(vm, dpadUp)
        key(vm, KeyEvent.KEYCODE_1)
        assertTrue(vm.state().overlayVisible)
        assertEquals("1", vm.state().numberEntry)
        vm.onPipChanged(true)
        assertFalse(vm.state().overlayVisible)
        assertFalse(vm.state().bannerVisible)
        assertNull(vm.state().numberEntry)
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals("the dropped number never jumps", 3L, vm.channelId())
        vm.onLongOk()
        assertNull("no quick menu in picture-in-picture", vm.quickMenu.value)

        vm.onPipChanged(false)
        vm.onLongOk()
        vm.onPipChanged(true)
        assertNull(vm.quickMenu.value)
        vm.onPipChanged(false)
        openOptions(vm)
        vm.onPipChanged(true)
        assertNull(vm.optionsFor.value)
        vm.onPipChanged(false)
        openOptions(vm)
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.Rename)
        vm.onPipChanged(true)
        assertNull(vm.editing.value)
        assertFalse(vm.dialogOpen())
    }

    @Test fun pipItemOnlyWhenSupported() = runTest {
        val without = vm(FakePlayback(), pipSupported = false)
        runCurrent()
        without.onLongOk()
        assertFalse(without.menu().entries.any { it.item == QuickItem.PIP })

        val with = vm(FakePlayback(), pipSupported = true)
        val events = events(with)
        runCurrent()
        with.onLongOk()
        assertTrue(with.menu().entries.any { it.item == QuickItem.PIP })
        with.pickQuickItem(QuickItem.PIP)
        runCurrent()
        assertEquals(listOf(PlayerEvent.EnterPip), events)
        assertNull(with.quickMenu.value)
    }

    @Test fun anyExitToTheGuideClearsThePipGate() = runTest {
        fun gate() = PipGate().also { it.playerShowing = true }
        // Back from bare video
        val back = gate()
        val vm1 = vm(FakePlayback(), pipGate = back)
        runCurrent()
        vm1.onBack()
        assertFalse(vm1.onBack())
        assertFalse(back.playerShowing)
        // the sleep timer running out
        val sleep = gate()
        val timer = timer()
        vm(FakePlayback(), sleepTimer = timer, pipGate = sleep)
        runCurrent()
        timer.start(15)
        advanceTimeBy(15 * 60_000L + 1_000)
        runCurrent()
        assertFalse(sleep.playerShowing)
        // the channel handed to another player
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val external = gate()
        val vm3 = vm(FakePlayback(), start = 3, pipGate = external)
        runCurrent()
        assertTrue("still showing while the other player starts", external.playerShowing)
        vm3.onExternalLaunched()
        assertFalse(external.playerShowing)
        // the last channel of the scope hidden
        val hidden = gate()
        val vm4 = vm(FakePlayback(), listOf(channel(1)), pipGate = hidden)
        runCurrent()
        openOptions(vm4)
        vm4.pickEdit(vm4.optionsFor.value!!, ChannelEdit.Hide)
        source.edit(1) { it.copy(visible = false) }
        vm4.onEditDone()
        runCurrent()
        assertTrue(vm4.exitToGuide.value)
        assertFalse(hidden.playerShowing)
    }

    @Test fun restoredAfterHandOffExitsQuietly() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val saved = SavedStateHandle()
        val first = FakePlayback()
        vm(first, start = 3, savedState = saved)
        runCurrent()
        assertEquals(listOf(3L), first.handOffs)

        // The app was killed behind the other player; the screen comes back with its saved state.
        val hold = PreviewHold()
        val playback = FakePlayback()
        val restored = vm(playback, start = 3, savedState = saved, previewHold = hold)
        runCurrent()
        restored.onScreenStarted()
        runCurrent()
        assertTrue(restored.exitToGuide.value)
        assertTrue("the guide keeps its preview stopped", hold.consume())
        assertEquals("no second hand-off", emptyList<Long>(), playback.handOffs)
        assertEquals("nothing plays here", emptyList<Long>(), playback.played)
    }

    @Test fun launchFailureClearsTheHandOffMark() = runTest {
        prefs.value = mapOf(3L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val saved = SavedStateHandle()
        val vm = vm(FakePlayback(), start = 3, savedState = saved)
        runCurrent()
        vm.onExternalFailed()
        prefs.value = emptyMap()
        val playback = FakePlayback()
        vm(playback, start = 3, savedState = saved)
        runCurrent()
        assertEquals("a later restore plays as usual", listOf(3L), playback.played)
    }

    // ---- Catch-up ----

    /** A channel whose archive is addressed by the minute (lag 120 s), three days back. */
    private fun catchupChannel(id: Long) = channel(id).copy(
        catchupType = "default", catchupDays = 3, catchupSource = "http://example.test/a/{Y}{m}{d}{H}{M}",
    )

    private val archiveStart = Instant.parse("2026-11-08T00:00:00Z")
    private val catchupList get() = listOf(catchupChannel(1)) + (2L..5L).map { channel(it) }

    private fun TestScope.catchupVm(
        playback: FakePlayback,
        remote: RemoteSettings = RemoteSettings(),
        archiveFrom: Instant? = null,
        start: Long = 1,
        favouriteRefs: MutableStateFlow<Set<ChannelRef>> = MutableStateFlow(emptySet()),
        sleepTimer: SleepTimer? = null,
    ): PlayerViewModel = vm(
        playback, channels = catchupList, start = start, favouriteRefs = favouriteRefs, remote = remote,
        archiveFrom = archiveFrom, elapsedMs = { testScheduler.currentTime }, sleepTimer = sleepTimer,
    ).also { catchupVms += it; runCurrent() }

    private val catchupVms = mutableListOf<PlayerViewModel>()

    /** Clears every view model at the end: a visible bar keeps ticking, which the test would wait on. */
    private fun catchupTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            catchupVms.forEach(::clear)
        }
    }

    /** Replaying the programme from [archiveStart] on channel 1, with the controls hidden. */
    private fun TestScope.archiveVm(
        playback: FakePlayback = FakePlayback(),
        remote: RemoteSettings = RemoteSettings(),
        sleepTimer: SleepTimer? = null,
    ) = catchupVm(playback, remote, archiveFrom = archiveStart, sleepTimer = sleepTimer).also { it.onBack(); runCurrent() }

    private fun bound(code: Int, action: PlayerAction) = RemoteSettings(
        bindings = KeyBindings(KeyBindings.DEFAULT.player + (code to action), KeyBindings.DEFAULT.guide),
    )

    private fun TestScope.key(vm: PlayerViewModel, code: Int, repeat: Int): Boolean =
        vm.onKey(code, repeat).also { runCurrent() }

    private fun TestScope.keyUp(vm: PlayerViewModel, code: Int) { vm.onKeyUp(code); runCurrent() }

    private fun TestScope.commit() { advanceTimeBy(COMMIT_DELAY_MS + 1); runCurrent() }

    private val left = KeyEvent.KEYCODE_DPAD_LEFT
    private val ff = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    private val rw = KeyEvent.KEYCODE_MEDIA_REWIND
    private val playPause = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    @Test fun openWithArchiveFromWatchesTheProgramme() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback, archiveFrom = archiveStart)
        assertEquals(listOf(ArchiveRequest(archiveStart, Instant.parse("2026-11-08T01:00:00Z"))), playback.archives)
        assertEquals("never live first", emptyList<Long>(), playback.played)
        assertTrue("the bar shows on start", vm.barVisible.value)
        assertNotNull(vm.catchupBar.value)
    }

    @Test fun openWithArchiveFromAdoptsWithoutReplaying() = catchupTest {
        val playback = FakePlayback()
        playback._mode.value = PlayMode.Archive(1, ArchiveRequest(archiveStart, archiveStart.plusSeconds(3600)))
        playback.playing(1)
        val vm = catchupVm(playback, archiveFrom = archiveStart)
        assertEquals(emptyList<Long>(), playback.played)
        assertEquals(emptyList<ArchiveRequest>(), playback.archives)
        assertNotNull(vm.catchupBar.value)
    }

    @Test fun upDownNeverZapInArchive() = catchupTest {
        // Up/Down never change channel, whatever is showing.
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        listOf(dpadUp, dpadDown).forEach { code ->
            assertTrue(key(vm, code))
            assertTrue(vm.state().overlayVisible)
            assertFalse(key(vm, code))
            settle()
            vm.hideOverlay()
        }
        key(vm, ok)
        key(vm, dpadUp)
        key(vm, dpadDown)
        settle()
        assertEquals(1L, vm.channelId())
        assertEquals(emptyList<Long>(), playback.played)
        assertEquals(1, playback.archives.size)
    }

    @Test fun rwOnLiveRewindsWhenEnabled() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        assertTrue(key(vm, rw))
        // The live lag (120 s) behind now, on the minute.
        assertEquals(Instant.parse("2026-11-08T01:45:00Z"), playback.archives.single().from)

        val off = FakePlayback()
        val vmOff = catchupVm(off, remote = RemoteSettings(rwRewindsLive = false))
        assertFalse(key(vmOff, rw))
        assertEquals(emptyList<ArchiveRequest>(), off.archives)
    }

    @Test fun rwOnLiveWithoutCatchupShowsMessage() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback, start = 2)
        assertTrue(key(vm, rw))
        assertEquals("This channel has no catch-up.", vm.state().message)
        assertEquals(emptyList<ArchiveRequest>(), playback.archives)
    }

    @Test fun leftOnLiveDoesNothingByDefault() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        vm.onBack()
        assertFalse(key(vm, left))
        keyUp(vm, left)
        commit()
        assertEquals(emptyList<ArchiveRequest>(), playback.archives)
        assertEquals(listOf(1L), playback.played)
        assertEquals("the picture stays", 0, playback.stops)

        val on = FakePlayback()
        val vmOn = catchupVm(on, remote = RemoteSettings(leftRewindsLive = true))
        vmOn.onBack()
        assertTrue(key(vmOn, left))
        assertEquals(Instant.parse("2026-11-08T01:45:00Z"), on.archives.single().from)
    }

    @Test fun leftRightSeekOnBareVideoInArchive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        assertFalse(vm.barVisible.value)
        assertTrue(key(vm, left, 0))
        assertTrue("a seek shows the bar", vm.barVisible.value)
        assertEquals("−0:10", vm.catchupBar.value!!.pendingLabel)
        keyUp(vm, left)
        commit()
        assertEquals(2, playback.archives.size)
        assertEquals(Instant.parse("2026-11-07T23:59:00Z"), playback.archives.last().from)

        val off = FakePlayback()
        val vmOff = archiveVm(off, RemoteSettings(leftRightSeek = false))
        assertFalse(key(vmOff, left, 0))
        keyUp(vmOff, left)
        commit()
        assertEquals(1, off.archives.size)
    }

    @Test fun leftRightMoveFocusWhenBarShown() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, ok)
        assertTrue(vm.barVisible.value)
        assertFalse("the D-pad moves between the bar's buttons", key(vm, left, 0))
        assertFalse(key(vm, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        keyUp(vm, left)
        commit()
        assertNull(vm.catchupBar.value!!.pendingLabel)
        assertEquals(1, playback.archives.size)
    }

    @Test fun mediaKeysIgnoredWhenOptionOff() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback, RemoteSettings(mediaKeysSeek = false))
        assertFalse(key(vm, ff, 0))
        assertFalse(key(vm, rw, 0))
        keyUp(vm, ff)
        commit()
        assertNull(vm.catchupBar.value!!.pendingLabel)
        assertEquals(1, playback.archives.size)
    }

    @Test fun heldFfBatchesIntoOneSeek() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        repeat(16) { i ->
            assertTrue(key(vm, ff, i))
            advanceTimeBy(50)
            runCurrent()
        }
        assertEquals("nothing reopens while the key is held", 1, playback.archives.size)
        keyUp(vm, ff)
        commit()
        assertEquals(2, playback.archives.size)
        assertEquals(Instant.parse("2026-11-08T00:08:00Z"), playback.archives.last().from)
    }

    @Test fun seekKeyUpCommitsBatch() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, ff, 0)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("no commit before the key is released", 1, playback.archives.size)
        keyUp(vm, ff)
        commit()
        assertEquals(2, playback.archives.size)
    }

    @Test fun customBindingRedButtonFavourite() = catchupTest {
        val refs = MutableStateFlow<Set<ChannelRef>>(emptySet())
        val bindings = KeyBindings(
            KeyBindings.DEFAULT.player + (KeyEvent.KEYCODE_PROG_RED to PlayerAction.FAVOURITE),
            KeyBindings.DEFAULT.guide,
        )
        val vm = catchupVm(FakePlayback(), RemoteSettings(bindings = bindings), favouriteRefs = refs)
        assertTrue(key(vm, KeyEvent.KEYCODE_PROG_RED))
        runCurrent()
        assertTrue(catchupChannel(1).ref in refs.value)
    }

    @Test fun playPauseOnLiveCatchupPausesLive() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        assertTrue(key(vm, playPause))
        assertEquals("live pauses by stopping the stream", 1, playback.stops)
        assertTrue(vm.catchupBar.value!!.paused)
        assertTrue(vm.barVisible.value)

        val plain = FakePlayback()
        val vmPlain = catchupVm(plain, start = 2)
        key(vmPlain, playPause)
        assertEquals("no catch-up: live is not pausable", 0, plain.stops)
        assertNull(vmPlain.catchupBar.value)
    }

    @Test fun barHidesAfterTimeoutButNotWhenPaused() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback, archiveFrom = archiveStart)
        assertTrue(vm.barVisible.value)
        advanceTimeBy(OVERLAY_TIMEOUT_MS + 1)
        runCurrent()
        assertFalse(vm.barVisible.value)

        assertTrue(key(vm, playPause))
        assertEquals(1, playback.pauses)
        assertTrue(vm.barVisible.value)
        advanceTimeBy(OVERLAY_TIMEOUT_MS * 3)
        runCurrent()
        assertTrue("paused: the bar stays", vm.barVisible.value)
    }

    @Test fun barTickerFollowsThePositionOnlyWhileVisible() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback, archiveFrom = archiveStart)
        playback.position = 60_000
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("1:00", vm.catchupBar.value!!.elapsed)
        advanceTimeBy(OVERLAY_TIMEOUT_MS)
        runCurrent()
        assertFalse(vm.barVisible.value)
        playback.position = 120_000
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("hidden: no ticks", "1:00", vm.catchupBar.value!!.elapsed)
    }

    @Test fun okInArchiveShowsBarFocusedOnPlayPause() = catchupTest {
        val vm = archiveVm()
        assertTrue(key(vm, ok))
        assertTrue(vm.state().overlayVisible)
        assertTrue(vm.barVisible.value)
        assertTrue(vm.state().barFocused)
        vm.hideOverlay()
        assertFalse(vm.state().barFocused)
        key(vm, dpadUp)
        assertFalse("Up/Down open the overlay on the channel row", vm.state().barFocused)
    }

    @Test fun failedArchiveShowsCardWithWatchLive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        playback.fail(1, "Ch 1: Stream unavailable")
        runCurrent()
        assertTrue(vm.archiveFailed.value)
        assertTrue("OK is Try again", key(vm, ok))
        assertEquals(2, playback.archives.size)
        assertEquals("never live", emptyList<Long>(), playback.played)

        playback.fail(1, "Ch 1: Stream unavailable")
        runCurrent()
        // Right moves focus to Watch live (no seek), then OK presses it.
        assertFalse(key(vm, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        keyUp(vm, KeyEvent.KEYCODE_DPAD_RIGHT)
        commit()
        assertEquals("no seek on the error card", 2, playback.archives.size)
        vm.onRootClick(watchLiveFocused = true)
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.catchupBar.value)
        assertFalse(vm.archiveFailed.value)
    }

    @Test fun okOnTryAgainByFocusReopensTheArchive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        playback.fail(1, "Ch 1: Stream unavailable")
        runCurrent()
        vm.onRootClick(watchLiveFocused = false)
        runCurrent()
        assertEquals(2, playback.archives.size)
        assertFalse("the card goes as the reopen starts", vm.archiveFailed.value)
        assertEquals(emptyList<Long>(), playback.played)
    }

    @Test fun playPauseWorksWithSeekKeysOff() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback, RemoteSettings(mediaKeysSeek = false))
        assertTrue(key(vm, playPause))
        assertEquals(1, playback.pauses)
        assertTrue(vm.catchupBar.value!!.paused)
    }

    @Test fun pausedBarHidesInTheBackgroundAndReturns() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, playPause)
        assertTrue(vm.barVisible.value)
        vm.onScreenStopped()
        assertFalse("no ticker while unseen", vm.barVisible.value)
        playback.stop()
        runCurrent()
        vm.onScreenStarted()
        runCurrent()
        assertTrue("still paused, the bar is back", vm.barVisible.value)
        assertEquals(1, playback.archives.size)
        assertEquals(emptyList<Long>(), playback.played)
        // Play reopens the stopped archive where it was paused.
        key(vm, playPause)
        assertEquals(2, playback.archives.size)
        assertEquals(archiveStart, playback.archives.last().from)
    }

    @Test fun pipHidesTheBarAndStopsTheTicker() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback, archiveFrom = archiveStart)
        assertTrue(vm.barVisible.value)
        vm.onPipChanged(true)
        assertFalse(vm.barVisible.value)
        playback.position = 60_000
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("0:00", vm.catchupBar.value!!.elapsed)
    }

    @Test fun liveKeyWithBarFocusedHidesTheOverlay() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback, bound(KeyEvent.KEYCODE_PROG_GREEN, PlayerAction.LIVE))
        key(vm, ok)
        assertTrue(vm.state().barFocused)
        assertTrue(key(vm, KeyEvent.KEYCODE_PROG_GREEN))
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertFalse("the remote is never left without focus", vm.state().overlayVisible)
        assertFalse(vm.state().barFocused)
    }

    @Test fun pickingAChannelFromTheRowKeepsTheOverlay() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, ok)
        vm.playFromRow(channel(2))
        runCurrent()
        assertEquals(2L, vm.channelId())
        assertNull(vm.catchupBar.value)
        assertTrue(vm.state().overlayVisible)
        assertFalse(vm.state().barFocused)
    }

    @Test fun liveFromAnArchiveHonoursTheExternalPlayer() = catchupTest {
        prefs.value = mapOf(1L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        assertEquals("the recording plays here", 1, playback.archives.size)
        vm.watchLive()
        runCurrent()
        assertEquals(listOf(1L), playback.handOffs)
        assertEquals("live is not played here", emptyList<Long>(), playback.played)
        assertTrue(vm.events.first() is PlayerEvent.LaunchExternal)
    }

    @Test fun sleepWarningDuringASeekStillCommitsOnRelease() = catchupTest {
        val timer = SleepTimer(backgroundScope, onExpire = {}, elapsed = { testScheduler.currentTime })
        val playback = FakePlayback()
        val vm = archiveVm(playback, sleepTimer = timer)
        timer.start(15)
        assertTrue(key(vm, ff, 0))
        advanceTimeBy(14 * 60_000L + 30_000)
        runCurrent()
        assertTrue(vm.sleep.value is SleepState.Warning)
        assertTrue("the warning takes the repeat", key(vm, ff, 1))
        assertTrue(timer.state.value is SleepState.Running)
        keyUp(vm, ff)
        commit()
        assertEquals("the held seek commits", 2, playback.archives.size)
        assertEquals(Instant.parse("2026-11-08T00:00:00Z"), playback.archives.last().from)
    }

    @Test fun heldSeekBecomesTheResumePointWhenStopped() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        repeat(4) { key(vm, ff, it) }
        vm.onScreenStopped()
        commit()
        assertEquals("nothing commits against the stopped stream", 1, playback.archives.size)
        playback.stop()
        vm.onScreenStarted()
        runCurrent()
        assertEquals(Instant.parse("2026-11-08T00:02:00Z"), playback.archives.last().from)
    }

    @Test fun heldRewindOnLiveOpensTheArchiveOnce() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        assertTrue(key(vm, rw, 0))
        repeat(15) { i ->
            assertTrue("the held key's repeats are swallowed", key(vm, rw, i + 1))
            advanceTimeBy(50)
            runCurrent()
        }
        keyUp(vm, rw)
        commit()
        assertEquals("one open for the whole hold", 1, playback.archives.size)
        assertEquals(Instant.parse("2026-11-08T01:45:00Z"), playback.archives.single().from)

        // The next press, after the release, is an ordinary seek again.
        assertTrue(key(vm, rw, 0))
        keyUp(vm, rw)
        commit()
        assertEquals(2, playback.archives.size)
    }

    @Test fun backToTheGuideFromARecordingGoesLive() = catchupTest {
        // Set to an external player: going live on the way out still plays here, for the preview.
        prefs.value = mapOf(1L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        assertFalse(vm.onBack())
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertEquals(PlayMode.Live, playback.mode.value)
        assertEquals(emptyList<Long>(), playback.handOffs)
        assertNull(vm.catchupBar.value)
    }

    @Test fun backToTheGuideFromPausedLiveGoesLive() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        vm.onBack() // hide the entry banner
        key(vm, playPause)
        assertEquals(PlaybackState.Idle, playback.state.value)
        val before = playback.played.size
        while (vm.onBack()) runCurrent()
        runCurrent()
        assertEquals(listOf(1L), playback.played.drop(before))
        assertEquals(PlaybackState.Buffering(1), playback.state.value)
    }

    @Test fun backToTheGuideFromLiveDoesNotReconnect() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        playback.playing(1)
        while (vm.onBack()) runCurrent()
        runCurrent()
        assertEquals(listOf(1L), playback.played)
    }

    @Test fun leaveClearsTheBar() = catchupTest {
        val vm = archiveVm()
        key(vm, ok)
        vm.leave()
        runCurrent()
        assertNull(vm.catchupBar.value)
        assertFalse(vm.barVisible.value)
    }

    @Test fun numberEntryLeavesTheArchive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, KeyEvent.KEYCODE_3)
        advanceTimeBy(NUMBER_ENTRY_IDLE_MS + 1)
        runCurrent()
        assertEquals(3L, vm.channelId())
        assertNull(vm.catchupBar.value)
        assertEquals(listOf(3L), playback.played)
    }

    @Test fun boundButtonsOpenGuideMenusAndSearch() = catchupTest {
        val subtitles = catchupVm(FakePlayback())
        assertTrue(key(subtitles, KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(QuickItem.SUBTITLES, (subtitles.quickMenu.value!!.panel as QuickPanel.Choices).item)

        val audio = catchupVm(FakePlayback(), bound(KeyEvent.KEYCODE_PROG_RED, PlayerAction.AUDIO_TRACK))
        assertTrue(key(audio, KeyEvent.KEYCODE_PROG_RED))
        assertEquals(QuickItem.AUDIO, (audio.quickMenu.value!!.panel as QuickPanel.Choices).item)

        val sleep = catchupVm(FakePlayback(), bound(KeyEvent.KEYCODE_PROG_RED, PlayerAction.SLEEP_TIMER))
        assertTrue(key(sleep, KeyEvent.KEYCODE_PROG_RED))
        assertEquals(QuickItem.SLEEP, (sleep.quickMenu.value!!.panel as QuickPanel.Choices).item)

        val search = catchupVm(FakePlayback(), bound(KeyEvent.KEYCODE_PROG_BLUE, PlayerAction.SEARCH))
        assertTrue(key(search, KeyEvent.KEYCODE_PROG_BLUE))
        assertEquals(PlayerEvent.OpenSearch, search.events.first())

        val guide = catchupVm(FakePlayback())
        assertTrue(key(guide, KeyEvent.KEYCODE_GUIDE))
        assertTrue(guide.exitToGuide.value)
    }

    @Test fun resumeAfterBackgroundKeepsPosition() = catchupTest {
        // Back from the background, playback continues where it was.
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        playback.playing(1)
        playback.position = 600_000
        vm.onScreenStopped()
        playback.stop()
        vm.onScreenStarted()
        runCurrent()
        assertEquals(2, playback.archives.size)
        assertEquals(archiveStart.plusSeconds(600), playback.archives.last().from)
        assertEquals("not live", emptyList<Long>(), playback.played)
    }

    @Test fun pausedLiveStaysPausedAfterBackground() = catchupTest {
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        key(vm, playPause)
        vm.onScreenStopped()
        vm.onScreenStarted()
        runCurrent()
        assertEquals("still paused", listOf(1L), playback.played)
        assertEquals(emptyList<ArchiveRequest>(), playback.archives)
    }

    @Test fun startOverOnlyOnLiveCatchupChannels() = catchupTest {
        fun PlayerViewModel.items() = quickMenu.value!!.entries.map { it.item }
        val playback = FakePlayback()
        val vm = catchupVm(playback)
        vm.onLongOk()
        assertTrue(QuickItem.START_OVER in vm.items())
        vm.pickQuickItem(QuickItem.START_OVER)
        runCurrent()
        assertNull(vm.quickMenu.value)
        assertEquals(Instant.parse("2026-11-08T01:00:00Z"), playback.archives.single().from)
        vm.onLongOk()
        assertFalse("not while replaying", QuickItem.START_OVER in vm.items())

        val plain = catchupVm(FakePlayback(), start = 2)
        plain.onLongOk()
        assertFalse(QuickItem.START_OVER in plain.items())
    }

    @Test fun zapLeavesTheArchive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        assertTrue(key(vm, chUp))
        settle()
        assertNull(vm.catchupBar.value)
        assertEquals(2L, vm.channelId())
        assertEquals(listOf(2L), playback.played)
    }

    @Test fun barButtonsDriveTheArchive() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        key(vm, ok)
        vm.onTransport(TransportButton.PLAY_PAUSE)
        runCurrent()
        assertEquals(1, playback.pauses)
        assertTrue(vm.catchupBar.value!!.paused)
        vm.onTransport(TransportButton.FAST_FORWARD)
        commit()
        assertEquals("one press is one seek", 2, playback.archives.size)
        vm.onTransport(TransportButton.LIVE)
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.catchupBar.value)
        assertFalse("back to bare live video", vm.state().overlayVisible)
        assertFalse(vm.barVisible.value)
    }

    @Test fun watchLiveTwiceOpensLiveOnce() = catchupTest {
        prefs.value = mapOf(1L to ChannelPlaybackPrefs(externalPlayer = "org.videolan.vlc"))
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        vm.watchLive()
        vm.watchLive()
        runCurrent()
        assertEquals("one hand-off", listOf(1L), playback.handOffs)

        val here = FakePlayback()
        prefs.value = emptyMap()
        val vmHere = archiveVm(here)
        vmHere.onTransport(TransportButton.LIVE)
        vmHere.watchLive()
        runCurrent()
        assertEquals("one live open", listOf(1L), here.played)
    }

    @Test fun anArchiveStartedWhileLiveOpensWins() = catchupTest {
        val playback = FakePlayback()
        val vm = archiveVm(playback)
        prefsGate = CompletableDeferred()
        vm.watchLive()
        runCurrent()
        assertTrue(key(vm, rw)) // back into the archive while the live open reads the channel's settings
        prefsGate!!.complete(Unit)
        runCurrent()
        assertEquals("live never replaces the archive", emptyList<Long>(), playback.played)
        assertEquals(2, playback.archives.size)
        assertNotNull(vm.catchupBar.value)
    }
}
