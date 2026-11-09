package io.github.loopholetv.ui.player

import android.view.KeyEvent
import androidx.media3.common.Player
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.manage.ChannelEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
            val active = (s is PlaybackState.Buffering && s.channelId == channel.id) ||
                (s is PlaybackState.Playing && s.channelId == channel.id)
            if (active) return
            loads += channel.id
            _state.value = PlaybackState.Buffering(channel.id)
        }
        override fun stop() { stops++; _state.value = PlaybackState.Idle }
        fun fail(id: Long, message: String) { _state.value = PlaybackState.Error(id, message) }
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
        )
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

    @Test fun longOkOpensOptionsForCurrentChannel() = runTest {
        val vm = vm(FakePlayback(), start = 4)
        runCurrent()
        vm.onBack() // bare video
        assertNull(vm.optionsFor.value)
        vm.onLongOk()
        assertEquals(4L, vm.optionsFor.value?.id)
        assertFalse("long OK doesn't open the overlay", vm.state().overlayVisible)
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
        vm.onTileLongPress(channel(7))
        assertEquals("the tile's channel stays", 6L, vm.optionsFor.value?.id)
        vm.dismissOptions()
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
        vm.pickEdit(vm.optionsFor.value!!, ChannelEdit.TimeShift)
        vm.onLongOk() // a held OK leaking past the dialog
        assertNull("no second options panel while a dialog is open", vm.optionsFor.value)
        vm.onEditDone()
        runCurrent()
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
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
        vm.onLongOk()
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
}
