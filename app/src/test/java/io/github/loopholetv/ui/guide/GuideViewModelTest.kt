package io.github.loopholetv.ui.guide

import androidx.media3.common.Player
import androidx.paging.testing.asPagingSourceFactory
import androidx.paging.testing.asSnapshot
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.GroupRow
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.GuideKey
import io.github.loopholetv.data.repo.guideKey
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.ui.manage.EditActions
import io.github.loopholetv.ui.manage.FakeEditActions
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePlayback : Playback {
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state
        val played = mutableListOf<Long>()
        override fun play(channel: ChannelEntity) {
            played += channel.id
            _state.value = PlaybackState.Buffering(channel.id)
        }
        override fun stop() { _state.value = PlaybackState.Idle }
        fun fail(id: Long) { _state.value = PlaybackState.Error(id, "can't play") }
        fun playingElsewhere(id: Long) { _state.value = PlaybackState.Playing(id, io.github.loopholetv.player.StreamTags(null, null, null)) }
    }

    private fun channel(id: Long, group: String) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = "Ch $id", streamUrl = "http://x/$id",
        logo = null, groupTitle = group, groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = "e$id",
    )

    private val all = listOf(channel(1, "News"), channel(2, "Sport"), channel(3, "News"))
    private var now = Instant.parse("2026-11-08T01:47:00Z")
    private val playback = FakePlayback()
    private val programmeQueries = mutableListOf<List<GuideKey>>()
    private var nowLookup: suspend (ChannelEntity) -> ProgrammeEntity? = { null }
    private var programmeRows = emptyList<ProgrammeEntity>()
    private var latestStopValue: Instant? = null
    private var latestStopCalls = 0
    private var channelsGone = false
    private var favourites = listOf(all[2], all[1])
    private val favouriteRefs = MutableStateFlow(setOf(all[2].ref, all[1].ref))
    private val moves = mutableListOf<Pair<Long, Int>>()
    private var moveGate: suspend () -> Unit = {}
    private var remembered: suspend () -> ChannelScope = { ChannelScope.All }
    private val saved = mutableListOf<ChannelScope>()

    private val groupRows = MutableStateFlow(
        listOf(
            GroupRow(1, "News", "Headlines", 0, 2, "P", GroupSort.NAME),
            GroupRow(1, "Sport", "Sport", 1, 1, "P", GroupSort.PLAYLIST),
        ),
    )
    private val edits = FakeEditActions()

    /** [edits], plus what the database would do: a hidden group leaves the groups list. */
    private val dbEdits = object : EditActions by edits {
        override suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean) {
            edits.setGroupHidden(playlistId, title, hidden)
            if (hidden) groupRows.value = groupRows.value.filterNot { it.playlistId == playlistId && it.title == title }
        }
    }
    private val playlistsOn = MutableStateFlow(1)
    private var lookups: List<ChannelEntity> = all

    /** Like ShiftedProgrammes.inWindow: every key in the map, its rows shifted (shown = stored + shift). */
    private fun shiftedRows(keys: Collection<GuideKey>): Map<GuideKey, List<ProgrammeEntity>> =
        keys.associateWith { k -> programmeRows.filter { it.epgChannelId == k.epgId }.map { it.shiftedBy(k.shiftMinutes) } }

    private fun vm() = GuideViewModel(
        groupRows = groupRows,
        channelPages = { scope ->
            when (scope) {
                ChannelScope.All -> all
                ChannelScope.Favourites -> favourites
                is ChannelScope.Group -> all.filter { it.playlistId == scope.playlistId && it.groupTitle == scope.title }
            }.asPagingSourceFactory()()
        },
        favouriteCount = flowOf(favourites.size),
        programmes = { keys, _, _ -> programmeQueries += keys.toList(); flowOf(shiftedRows(keys)) },
        latestStop = { latestStopValue.also { latestStopCalls++ } },
        playback = playback,
        nowFor = { nowLookup(it) },
        channelById = { id -> if (channelsGone) null else lookups.firstOrNull { it.id == id } },
        clock = { now },
        zone = ZoneOffset.UTC,
        favouriteRefs = favouriteRefs,
        toggleFavourite = { c ->
            val added = c.ref !in favouriteRefs.value
            favouriteRefs.value = if (added) favouriteRefs.value + c.ref else favouriteRefs.value - c.ref
            added
        },
        moveFavourite = { c, direction -> moves += c.id to direction; moveGate(); true },
        initialScope = { remembered() },
        saveScope = { saved += it },
        edits = dbEdits,
        playlistsOn = playlistsOn,
    )

    @Test fun groupsListStartsWithFavouritesThenAllChannels() = runTest {
        val vm = vm()
        runCurrent()
        val groups = vm.groups.value
        assertEquals(GroupItem(scope = ChannelScope.Favourites, label = "Favourites", count = 2), groups[0])
        assertEquals(GroupItem(scope = ChannelScope.All, label = "All channels", count = 3), groups[1])
        assertEquals(
            listOf(ChannelScope.Group(1, "News"), ChannelScope.Group(1, "Sport")),
            groups.drop(2).map { it.scope },
        )
        assertEquals(listOf("Headlines", "Sport"), groups.drop(2).map { it.label })
        assertEquals(listOf("P", "P"), groups.drop(2).map { it.playlistName })
        assertEquals(listOf(GroupSort.NAME, GroupSort.PLAYLIST), groups.drop(2).map { it.sortMode })
    }

    @Test fun favouritesScopeListsFavouritesInOrder() = runTest {
        val vm = vm()
        vm.selectGroup(ChannelScope.Favourites)
        assertEquals(ChannelScope.Favourites, vm.selectedScope.value)
        assertEquals(listOf(3L, 2L), vm.pages.value!!.flow.asSnapshot().map { it.id })
    }

    @Test fun eachScopeSwitchGetsItsOwnPages() = runTest {
        val vm = vm()
        runCurrent()
        val all = vm.pages.value!!
        assertEquals(ChannelScope.All, all.scope)
        vm.selectGroup(ChannelScope.Favourites)
        val favourites = vm.pages.value!!
        // A fresh flow per scope: the screen's list never presents the previous scope's rows.
        assertEquals(ChannelScope.Favourites, favourites.scope)
        assertTrue(favourites.flow !== all.flow)
        assertEquals(listOf(3L, 2L), favourites.flow.asSnapshot().map { it.id })
        vm.selectGroup(ChannelScope.Favourites) // same scope: same pages
        assertSame(favourites, vm.pages.value)
        vm.onGroupFocused(ChannelScope.Group(1, "News")) // pending: nothing changes yet
        assertSame(favourites, vm.pages.value)
        vm.commitPendingGroup()
        assertEquals(ChannelScope.Group(1, "News"), vm.pages.value!!.scope)
        assertEquals(vm.selectedScope.value, vm.pages.value!!.scope)
        advanceTimeBy(GROUP_FOCUS_DEBOUNCE_MS + 1)
        assertEquals(listOf(1L, 3L), vm.pages.value!!.flow.asSnapshot().map { it.id })
    }

    @Test fun selectingGroupFiltersChannels() = runTest {
        val vm = vm()
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.pages.value!!.flow.asSnapshot().map { it.id })
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        vm.selectGroup(ChannelScope.Group(1, "News"))
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
        assertEquals(listOf(1L, 3L), vm.pages.value!!.flow.asSnapshot().map { it.id })
    }

    private fun programme(epg: String, fromNowSec: Long, toNowSec: Long, title: String) = ProgrammeEntity(
        epgChannelId = epg, start = now.plusSeconds(fromNowSec), stop = now.plusSeconds(toNowSec), title = title,
        subTitle = null, description = null, category = null, generation = 1,
    )

    @Test fun focusChangesNeverPlay() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(all[0], null)
        vm.onFocus(all[1], programme("e2", -600, 1200, "Now"))
        vm.onFocus(all[2], programme("e3", 3600, 5400, "Later"))
        advanceUntilIdle()
        assertTrue(playback.played.isEmpty())
        // The info area still follows focus.
        assertEquals("Later", vm.focused.value?.programme?.title)
    }

    @Test fun firstOkOnChannelPreviewsWithoutNavigating() = runTest {
        val vm = vm()
        assertNull(vm.previewing.value)
        assertEquals(OkAction.Previewed, vm.onOk(all[0], null))
        assertEquals(listOf(1L), playback.played)
        assertEquals(all[0], vm.previewing.value)
        // OK on any block of another row previews that row's channel, even a future programme.
        assertEquals(OkAction.Previewed, vm.onOk(all[1], programme("e2", 3600, 5400, "Later")))
        assertEquals(listOf(1L, 2L), playback.played)
    }

    @Test fun secondOkOnPreviewingChannelOpensPlayer() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        assertEquals(OkAction.OpenPlayer(1L), vm.onOk(all[0], null))
        assertEquals(OkAction.OpenPlayer(1L), vm.onOk(all[0], programme("e1", -600, 1200, "Now")))
        // No reconnect: play() ran once.
        assertEquals(listOf(1L), playback.played)
    }

    @Test fun okOnPastOrFutureProgrammeOfPreviewingChannelOpensDetails() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        val later = programme("e1", 3600, 5400, "Later")
        val earlier = programme("e1", -7200, -5400, "Earlier")
        assertEquals(OkAction.ShowDetails(later), vm.onOk(all[0], later))
        assertEquals(OkAction.ShowDetails(earlier), vm.onOk(all[0], earlier))
        assertEquals(listOf(1L), playback.played)
    }

    @Test fun resumeAfterBackgroundReplaysPreviewingChannel() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        vm.onFocus(all[2], null)
        playback.stop() // MainActivity.onStop
        vm.onScreenStarted()
        runCurrent()
        assertEquals(listOf(1L, 1L), playback.played)
        // Started again while still previewing: no reconnect.
        vm.onScreenStarted()
        assertEquals(listOf(1L, 1L), playback.played)
    }

    @Test fun screenStartWithoutPreviewPlaysNothing() = runTest {
        val vm = vm()
        vm.onFocus(all[0], null)
        vm.onScreenStarted()
        advanceUntilIdle()
        assertTrue(playback.played.isEmpty())
    }

    @Test fun focusingGroupSwitchesItAfterDebounce() = runTest {
        val vm = vm()
        vm.onGroupFocused(ChannelScope.Group(1, "News"))
        advanceTimeBy(100)
        vm.onGroupFocused(ChannelScope.Group(1, "Sport"))
        advanceTimeBy(100)
        vm.onGroupFocused(ChannelScope.Group(1, "News"))
        advanceTimeBy(GROUP_FOCUS_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        advanceUntilIdle()
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
        assertEquals(listOf(1L, 3L), vm.pages.value!!.flow.asSnapshot().map { it.id })
    }

    @Test fun focusedStopsSharingWithoutSubscribers() = runTest {
        val vm = vm()
        vm.onFocus(all[0], null)
        runCurrent()
        // Nobody collects: nothing upstream (and so no Room query) is kept running.
        assertNull(vm.focused.value)
        val job = backgroundScope.launch { vm.focused.collect {} }
        runCurrent()
        assertEquals(all[0], vm.focused.value?.channel)
        job.cancel()
    }

    @Test fun programmesAreQueriedOnlyForVisibleIds() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.slice.collect {} }
        vm.setVisibleKeys(listOf(GuideKey("e1", 0), GuideKey("e2", 0), GuideKey("e1", 0)))
        runCurrent()
        assertEquals(listOf(GuideKey("e1", 0), GuideKey("e2", 0)), programmeQueries.last())
        assertEquals(setOf(GuideKey("e1", 0), GuideKey("e2", 0)), vm.slice.value.keys)
    }

    @Test fun focusedChannelShowsProgrammeAiringNowOnceLoaded() = runTest {
        programmeRows = listOf(
            ProgrammeEntity(
                epgChannelId = "e1", start = now.minusSeconds(600), stop = now.plusSeconds(1200), title = "News at two",
                subTitle = null, description = null, category = null, generation = 1,
            ),
        )
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(all[0], null)
        runCurrent()
        assertNull(vm.focused.value?.programme)
        vm.setVisibleKeys(listOf(GuideKey("e1", 0)))
        runCurrent()
        assertEquals("News at two", vm.focused.value?.programme?.title)
    }

    @Test fun leavingGroupsCommitsPendingGroupAtOnce() = runTest {
        val vm = vm()
        vm.onGroupFocused(ChannelScope.Group(1, "Sport"))
        advanceTimeBy(100)
        vm.onFocus(all[0], null) // focus moved to the grid within the debounce
        assertEquals(ChannelScope.Group(1, "Sport"), vm.selectedScope.value)
        vm.onGroupFocused(ChannelScope.Group(1, "News")) // focus back in groups...
        vm.commitPendingGroup() // ...and out to the rail
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
        advanceUntilIdle()
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value) // no late switch
    }

    @Test fun focusedChannelFallsBackToNowAndNextWhenNotLoaded() = runTest {
        nowLookup = { c -> if (c.guideEpgId == "e1") programme("e1", -600, 1200, "On now") else null }
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(all[0], null) // no visible ids: nothing in the slice (window in the past)
        runCurrent()
        assertEquals("On now", vm.focused.value?.programme?.title)
    }

    @Test fun secondOkRetriesWhenPreviewFailed() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        playback.fail(1)
        assertEquals(OkAction.Previewed, vm.onOk(all[0], null))
        runCurrent() // the retry re-checks the channel first
        assertEquals(listOf(1L, 1L), playback.played)
        // Now buffering again: the next OK opens full screen.
        assertEquals(OkAction.OpenPlayer(1L), vm.onOk(all[0], null))
    }

    @Test fun okOnAPreviewHiddenMeanwhileDoesNotReplayIt() = runTest {
        // Hidden from Settings (a dialog over the guide); the app watcher stopped the preview.
        val vm = vm()
        vm.onOk(all[0], null)
        playback.stop()
        lookups = all.filterNot { it.id == 1L }
        assertEquals(OkAction.Previewed, vm.onOk(all[0], null))
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.previewing.value)
        assertNull(vm.onPreviewOk())
    }

    @Test fun previewOkAfterAFailedHiddenPreviewDoesNotReplayIt() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        playback.fail(1)
        lookups = all.filterNot { it.id == 1L }
        assertEquals(OkAction.Previewed, vm.onPreviewOk())
        runCurrent()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.previewing.value)
    }

    @Test fun screenStartSyncsPreviewWithPlayerChannel() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onOk(all[0], null)
        playback.playingElsewhere(3) // e.g. channel up/down in the full-screen player
        assertEquals(3L, vm.returnChannelId())
        vm.onScreenStarted()
        runCurrent()
        assertEquals(all[2], vm.previewing.value)
        assertEquals(listOf(1L), playback.played)
        // Focus returns to the channel the player ended on, and the info area describes it.
        assertEquals(3L, vm.refocus.value)
        assertEquals(all[2], vm.focused.value?.channel)
        vm.consumeRefocus()
        assertNull(vm.refocus.value)
    }

    @Test fun returningFromPlayerWithoutPreviewAdoptsPlayingChannel() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        // Opened full screen without a preview (Search, or the player at start): the guide comes
        // back with that channel playing.
        playback.playingElsewhere(2)
        assertNull(vm.returnChannelId())
        vm.onScreenStarted()
        runCurrent()
        assertEquals(all[1], vm.previewing.value)
        assertEquals(2L, vm.refocus.value)
        assertEquals(all[1], vm.focused.value?.channel)
        assertTrue("no reconnect", playback.played.isEmpty())
    }

    @Test fun shiftBackAtNowIsRefused() = runTest {
        latestStopValue = now.plusSeconds(6 * 3600)
        val vm = vm()
        runCurrent()
        val start = vm.window.value
        // No past navigation: Left at "now" (strip edge) is refused, so focus can go to the groups.
        assertTrue(!vm.shiftWindow(-1))
        assertEquals(start, vm.window.value)
        assertTrue(!vm.windowShifted.value)
    }

    @Test fun forwardShiftThenBackAndResetSnapsToNow() = runTest {
        latestStopValue = now.plusSeconds(6 * 3600)
        val vm = vm()
        runCurrent()
        val start = vm.window.value
        assertTrue(vm.shiftWindow(2))
        assertEquals(start.start.plusSeconds(3600), vm.window.value.start)
        assertTrue(vm.windowShifted.value)
        // Left at the edge of a forward-shifted window moves back toward now, never past it.
        assertTrue(vm.shiftWindow(-1))
        assertEquals(start.start.plusSeconds(1800), vm.window.value.start)
        assertTrue(vm.shiftWindow(-1))
        assertEquals(start, vm.window.value)
        assertTrue(!vm.windowShifted.value)
        assertTrue(!vm.shiftWindow(-1))
        // Back while shifted into the future snaps to now.
        assertTrue(vm.shiftWindow(3))
        assertTrue(vm.resetWindow())
        assertEquals(start, vm.window.value)
        assertTrue(!vm.windowShifted.value)
        assertTrue(!vm.resetWindow()) // already at now: Back is not consumed
    }

    @Test fun shiftedWindowOvertakenByTheClockFollowsNowAgain() = runTest {
        latestStopValue = now.plusSeconds(6 * 3600)
        val vm = vm()
        runCurrent()
        assertTrue(vm.shiftWindow(1)) // 02:00Z
        now = now.plusSeconds(3600) // 02:47Z: the now window starts 02:30Z, after the shifted one
        vm.onTick()
        assertEquals(initialWindow(now, ZoneOffset.UTC), vm.window.value)
        assertTrue(!vm.windowShifted.value)
    }

    @Test fun okOnPreviewOpensThePreviewingChannel() = runTest {
        val vm = vm()
        vm.onOk(all[1], null)
        assertEquals(OkAction.OpenPlayer(2L), vm.onPreviewOk())
        // Same player, no reconnect.
        assertEquals(listOf(2L), playback.played)
    }

    @Test fun okOnPreviewWithNothingPreviewingDoesNothing() = runTest {
        val vm = vm()
        assertNull(vm.onPreviewOk())
        assertTrue(playback.played.isEmpty())
        assertNull(vm.previewing.value)
    }

    @Test fun resumeAfterBackgroundDoesNotReplayAChannelThatNoLongerExists() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        playback.stop()
        channelsGone = true
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.previewing.value)
    }

    @Test fun forwardShiftIsClampedToLatestProgrammeStop() = runTest {
        latestStopValue = now.plusSeconds(7200) // 03:47Z -> latest start 02:00Z, nowStart 01:30Z
        val vm = vm()
        runCurrent()
        val start = vm.window.value.start
        assertTrue(vm.shiftWindow(1))
        assertEquals(start.plusSeconds(1800), vm.window.value.start)
        assertTrue(!vm.shiftWindow(1))
    }

    @Test fun latestStopIsOneShotNotCollectedPerChange() = runTest {
        val vm = vm()
        runCurrent()
        vm.shiftWindow(-1)
        vm.shiftWindow(-1)
        runCurrent()
        assertTrue("calls=$latestStopCalls", latestStopCalls <= 1)
    }

    @Test fun blockedForwardShiftRequeriesLimitOnceAndAppliesShift() = runTest {
        val limits = ArrayDeque(listOf<Instant?>(now.plusSeconds(7200), now.plusSeconds(10800)))
        var calls = 0
        val vm = GuideViewModel(
            groupRows = flowOf(emptyList()),
            channelPages = { all.asPagingSourceFactory()() },
            programmes = { _, _, _ -> flowOf(emptyMap()) },
            latestStop = { calls++; delay(100); limits.removeFirstOrNull() ?: now.plusSeconds(10800) },
            playback = playback,
            nowFor = { null },
            channelById = { null },
            clock = { now },
            zone = ZoneOffset.UTC,
        )
        advanceTimeBy(101); runCurrent()
        assertEquals(1, calls)
        val start = vm.window.value.start
        assertTrue(vm.shiftWindow(1)) // within the first limit
        assertTrue(!vm.shiftWindow(1)) // blocked: triggers one re-query
        assertTrue(!vm.shiftWindow(1)) // in flight: no second query
        assertTrue(!vm.shiftWindow(1))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(start.plusSeconds(1800), vm.window.value.start)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.window.value.start > start.plusSeconds(1800))
    }

    @Test fun toggleFavouriteUpdatesRefsAndNotice() = runTest {
        val vm = vm()
        runCurrent()
        assertEquals(setOf(all[2].ref, all[1].ref), vm.favourites.value)
        vm.toggleFavourite(all[0])
        runCurrent()
        assertTrue(all[0].ref in vm.favourites.value)
        assertEquals("Added to favourites", vm.notice.value)
        vm.consumeNotice()
        assertNull(vm.notice.value)
        vm.toggleFavourite(all[0])
        runCurrent()
        assertTrue(all[0].ref !in vm.favourites.value)
        assertEquals("Removed from favourites", vm.notice.value)
    }

    @Test fun moveOnlyInFavouritesScope() = runTest {
        val vm = vm()
        runCurrent()
        vm.startMove(all[2]) // a favourite, but the scope is All channels
        assertNull(vm.moving.value)
        vm.selectGroup(ChannelScope.Favourites)
        vm.startMove(all[0]) // not a favourite
        assertNull(vm.moving.value)
        vm.startMove(all[2])
        assertEquals(all[2], vm.moving.value)
        // Leaving the scope ends the move.
        vm.selectGroup(ChannelScope.All)
        assertNull(vm.moving.value)
    }

    @Test fun moveByCallsRepositoryAndEndMoveClears() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Favourites)
        vm.startMove(all[1])
        vm.moveBy(-1)
        runCurrent()
        vm.moveBy(+1)
        runCurrent()
        assertEquals(listOf(2L to -1, 2L to 1), moves)
        vm.endMove()
        assertNull(vm.moving.value)
        vm.moveBy(-1) // not moving: nothing
        runCurrent()
        assertEquals(2, moves.size)
    }

    @Test fun moveRequestsWhileOneIsRunningAreDropped() = runTest {
        // A slow device: the reorder hasn't finished when the next press arrives; queueing it
        // would make the row overshoot after the key is released.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        moveGate = { gate.await() }
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Favourites)
        vm.startMove(all[1])
        vm.moveBy(-1)
        runCurrent()
        vm.moveBy(-1)
        vm.moveBy(-1)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(2L to -1), moves)
        vm.moveBy(+1) // the first one has finished: accepted again
        runCurrent()
        assertEquals(listOf(2L to -1, 2L to 1), moves)
    }

    @Test fun removingTheMovingChannelEndsTheMove() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Favourites)
        vm.startMove(all[1])
        vm.toggleFavourite(all[1])
        runCurrent()
        assertNull(vm.moving.value)
    }

    @Test fun startsOnRememberedGroup() = runTest {
        remembered = { ChannelScope.Group(1, "News") }
        val vm = vm()
        runCurrent()
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
        assertEquals(ChannelScope.Group(1, "News"), vm.pages.value!!.scope)
        assertEquals(listOf(1L, 3L), vm.pages.value!!.flow.asSnapshot().map { it.id })
        // Starting on it is not a new choice.
        assertTrue(saved.isEmpty())
    }

    @Test fun noChannelListUntilTheRememberedGroupIsKnown() = runTest {
        remembered = { delay(100); ChannelScope.Group(1, "Sport") }
        val vm = vm()
        runCurrent()
        // No "All channels" list loaded first and then replaced.
        assertNull(vm.pages.value)
        advanceTimeBy(101); runCurrent()
        assertEquals(ChannelScope.Group(1, "Sport"), vm.pages.value!!.scope)
    }

    @Test fun rememberedGroupGoneFallsBackToAll() = runTest {
        remembered = { ChannelScope.Group(1, "Movies") }
        val vm = vm()
        runCurrent()
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        assertEquals(ChannelScope.All, vm.pages.value!!.scope)
    }

    @Test fun rememberedEmptyFavouritesFallsBackToAll() = runTest {
        favourites = emptyList()
        remembered = { ChannelScope.Favourites }
        val vm = vm()
        runCurrent()
        assertEquals(ChannelScope.All, vm.selectedScope.value)
    }

    @Test fun rememberedFavouritesWithChannelsIsKept() = runTest {
        remembered = { ChannelScope.Favourites }
        val vm = vm()
        runCurrent()
        assertEquals(ChannelScope.Favourites, vm.selectedScope.value)
    }

    @Test fun selectingGroupSavesScope() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "Sport"))
        runCurrent()
        assertEquals(listOf<ChannelScope>(ChannelScope.Group(1, "Sport")), saved)
        // A focus switch saves once the debounce commits it.
        vm.onGroupFocused(ChannelScope.Favourites)
        runCurrent()
        assertEquals(1, saved.size)
        advanceTimeBy(GROUP_FOCUS_DEBOUNCE_MS + 1); runCurrent()
        assertEquals(listOf(ChannelScope.Group(1, "Sport"), ChannelScope.Favourites), saved)
        vm.selectGroup(ChannelScope.Favourites) // same scope: not saved again
        runCurrent()
        assertEquals(2, saved.size)
    }

    @Test fun choosingBeforeTheRememberedGroupIsReadWins() = runTest {
        remembered = { delay(100); ChannelScope.Group(1, "News") }
        val vm = vm()
        vm.selectGroup(ChannelScope.Group(1, "Sport"))
        advanceTimeBy(101); runCurrent()
        assertEquals(ChannelScope.Group(1, "Sport"), vm.selectedScope.value)
        assertEquals(listOf<ChannelScope>(ChannelScope.Group(1, "Sport")), saved)
    }

    // --- Group and channel management, headings, display values, time shift ---------------

    private fun GuideViewModel.group(title: String) =
        groups.value.first { (it.scope as? ChannelScope.Group)?.title == title }

    private fun List<GroupEntry>.labels() = map {
        when (it) {
            is GroupEntry.Heading -> "# ${it.name}"
            is GroupEntry.Item -> it.group.label
        }
    }

    @Test fun headingsOnlyWithTwoPlaylists() = runTest {
        val vm = vm()
        runCurrent()
        // One playlist: no headings, the groups as before.
        assertTrue(vm.groupEntries.value.none { it is GroupEntry.Heading })
        assertEquals(vm.groups.value, vm.groupEntries.value.map { (it as GroupEntry.Item).group })
        groupRows.value = groupRows.value + GroupRow(2, "News", "News", 0, 4, "Second", GroupSort.PLAYLIST)
        runCurrent()
        assertEquals(
            listOf("Favourites", "All channels", "# P", "Headlines", "Sport", "# Second", "News"),
            vm.groupEntries.value.labels(),
        )
        // Headings follow the list order, never GroupRow.order.
        assertEquals(
            listOf("# A", "a2", "a1", "# B", "b1"),
            withHeadings(
                listOf(
                    GroupItem(ChannelScope.Group(1, "a2"), "a2", 1, "A"),
                    GroupItem(ChannelScope.Group(1, "a1"), "a1", 1, "A"),
                    GroupItem(ChannelScope.Group(2, "b1"), "b1", 1, "B"),
                ),
            ).labels(),
        )
        // Back to one playlist: the headings go.
        groupRows.value = groupRows.value.filter { it.playlistId == 2L }
        runCurrent()
        assertEquals(listOf("Favourites", "All channels", "News"), vm.groupEntries.value.labels())
    }

    @Test fun groupOptionsHideCallsEditsAndGroupDisappears() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "Sport"))
        vm.hideGroup(vm.group("Sport"))
        runCurrent()
        assertEquals(listOf("setGroupHidden(1, Sport, true)"), edits.calls)
        assertTrue(vm.groups.value.none { it.scope == ChannelScope.Group(1, "Sport") })
        // The list it showed is gone with it: All channels instead.
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        // Favourites and All channels have no group options.
        assertFalse(hasGroupOptions(vm.groups.value[0]))
        assertFalse(hasGroupOptions(vm.groups.value[1]))
        assertTrue(hasGroupOptions(vm.group("News")))
    }

    @Test fun hidingAGroupStopsAPreviewFromIt() = runTest {
        val vm = vm()
        runCurrent()
        vm.onOk(all[1], null) // Sport
        vm.hideGroup(vm.group("Sport"))
        runCurrent()
        assertNull(vm.previewing.value)
        assertEquals(PlaybackState.Idle, playback.state.value)
    }

    @Test fun groupMoveModeUpDown() = runTest {
        val vm = vm()
        runCurrent()
        vm.startGroupMove(vm.groups.value[1]) // All channels: not movable
        assertNull(vm.moveKind.value)
        val news = vm.group("News")
        vm.startGroupMove(news)
        assertEquals(MoveKind.GROUP, vm.moveKind.value)
        assertEquals(news, vm.movingGroup.value)
        assertNull(vm.moving.value)
        vm.moveBy(+1)
        runCurrent()
        vm.moveBy(-1)
        runCurrent()
        assertEquals(listOf("moveGroup(1, News, 1)", "moveGroup(1, News, -1)"), edits.calls)
        // Focus following the moved group doesn't switch the channel list.
        vm.onGroupFocused(ChannelScope.Group(1, "News"))
        advanceTimeBy(GROUP_FOCUS_DEBOUNCE_MS + 1); runCurrent()
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        vm.endMove()
        assertNull(vm.moveKind.value)
        assertNull(vm.movingGroup.value)
        vm.moveBy(+1)
        runCurrent()
        assertEquals(2, edits.calls.size)
    }

    @Test fun channelMoveInGroupScopeCallsEdits() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "News"))
        vm.startMove(all[1]) // Sport's channel: not in this group
        assertNull(vm.moving.value)
        vm.startMove(all[0])
        assertEquals(MoveKind.GROUP_CHANNEL, vm.moveKind.value)
        assertEquals(all[0], vm.moving.value)
        vm.moveBy(+1)
        runCurrent()
        assertEquals(listOf("move(k1, 1)"), edits.calls)
        assertTrue("favourites untouched", moves.isEmpty())
        vm.endMove()
        assertNull(vm.moving.value)
        assertNull(vm.moveKind.value)
        // Favourites keep their own move.
        vm.selectGroup(ChannelScope.Favourites)
        vm.startMove(all[2])
        assertEquals(MoveKind.FAVOURITE, vm.moveKind.value)
        vm.moveBy(-1)
        runCurrent()
        assertEquals(listOf(3L to -1), moves)
        assertEquals(1, edits.calls.size)
    }

    @Test fun moveNotOfferedInAllChannels() = runTest {
        val vm = vm()
        runCurrent()
        assertFalse(vm.canMoveInGroup(all[0]))
        vm.startMove(all[0])
        assertNull(vm.moving.value)
        vm.selectGroup(ChannelScope.Favourites)
        assertFalse(vm.canMoveInGroup(all[2]))
        vm.selectGroup(ChannelScope.Group(1, "News"))
        assertTrue(vm.canMoveInGroup(all[0]))
        assertFalse(vm.canMoveInGroup(all[1]))
    }

    @Test fun hidingPreviewingChannelStopsPreview() = runTest {
        val vm = vm()
        runCurrent()
        vm.onOk(all[0], null)
        vm.hideChannel(all[1]) // not the previewing one: the preview carries on
        runCurrent()
        assertEquals(all[0], vm.previewing.value)
        assertEquals(PlaybackState.Buffering(1), playback.state.value)
        vm.hideChannel(all[0])
        runCurrent()
        assertNull(vm.previewing.value)
        assertEquals(PlaybackState.Idle, playback.state.value)
        assertEquals(listOf("setHidden(1:k2, true)", "setHidden(1:k1, true)"), edits.calls)
        // Back from the background: nothing to resume.
        vm.onScreenStarted()
        runCurrent()
        assertEquals(listOf(1L), playback.played)
    }

    @Test fun hidingTheMovingChannelEndsTheMove() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "News"))
        vm.startMove(all[0])
        vm.hideChannel(all[0])
        runCurrent()
        assertNull(vm.moving.value)
        assertNull(vm.moveKind.value)
    }

    @Test fun resumeSkipsAChannelHiddenMeanwhile() = runTest {
        val vm = vm()
        vm.onOk(all[0], null)
        playback.stop()
        lookups = all.drop(1) // the visible-only lookup no longer finds it
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(listOf(1L), playback.played)
        assertNull(vm.previewing.value)
    }

    @Test fun shiftedProgrammesShownAtShiftedTimes() = runTest {
        val shifted = all[0].copy(epgShiftMinutes = 60)
        // Stored 00:37–01:17Z, before now (01:47Z); shown an hour later: 01:37–02:17Z, airing.
        programmeRows = listOf(programme("e1", -4200, -1800, "Shifted show"))
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.setVisibleKeys(listOfNotNull(shifted.guideKey))
        vm.onFocus(shifted, null)
        runCurrent()
        assertEquals(listOf(GuideKey("e1", 60)), programmeQueries.last())
        val row = vm.slice.value.byKey[GuideKey("e1", 60)].orEmpty()
        assertEquals(now.minusSeconds(600), row.single().start)
        assertEquals("Shifted show", vm.focused.value?.programme?.title)
        // The same guide channel unshifted is another key, its programme at the stored time.
        vm.setVisibleKeys(listOf(GuideKey("e1", 0)))
        runCurrent()
        assertEquals(now.minusSeconds(4200), vm.slice.value.byKey[GuideKey("e1", 0)]!!.single().start)
    }

    @Test fun focusedFallsBackToTheChannelsShiftedNowLookup() = runTest {
        val shifted = all[0].copy(epgShiftMinutes = -30)
        var asked: ChannelEntity? = null
        nowLookup = { c -> asked = c; programme("e1", -60, 60, "Shifted now") }
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(shifted, null)
        runCurrent()
        assertEquals(shifted, asked)
        assertEquals("Shifted now", vm.focused.value?.programme?.title)
    }

    @Test fun displayNameUsedInFocusedProgramme() = runTest {
        val renamed = all[0].copy(customName = "My News", guideNumber = 101, customLogo = "http://example.test/l.png")
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(renamed, null)
        runCurrent()
        assertEquals("101  My News", channelLabel(vm.focused.value!!.channel))
        assertEquals("1  Ch 1", channelLabel(all[0]))
        vm.onOk(renamed, null)
        assertEquals("101  My News", channelLabel(vm.previewing.value!!))
    }

    @Test fun allPlaylistsOffShowsMessage() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.allPlaylistsOff.collect {} }
        runCurrent()
        assertFalse(vm.allPlaylistsOff.value)
        assertNull(emptyHintFor(allPlaylistsOff = false, scope = ChannelScope.All, listEmpty = true))
        playlistsOn.value = 0
        runCurrent()
        assertTrue(vm.allPlaylistsOff.value)
        assertEquals(
            "All playlists are turned off. Turn one on in Settings → Playlists.",
            emptyHintFor(allPlaylistsOff = true, scope = ChannelScope.All, listEmpty = true),
        )
        assertEquals(ALL_PLAYLISTS_OFF, emptyHintFor(allPlaylistsOff = true, scope = ChannelScope.Favourites, listEmpty = true))
        assertEquals(FAVOURITES_EMPTY_HINT, emptyHintFor(allPlaylistsOff = false, scope = ChannelScope.Favourites, listEmpty = true))
        assertNull(emptyHintFor(allPlaylistsOff = false, scope = ChannelScope.Favourites, listEmpty = false))
    }

    @Test fun noPlaylistsAtAllIsNotAllOff() = runTest {
        val vm = GuideViewModel(
            groupRows = flowOf(emptyList()),
            channelPages = { emptyList<ChannelEntity>().asPagingSourceFactory()() },
            programmes = { _, _, _ -> flowOf(emptyMap()) },
            latestStop = { null },
            playback = playback,
            clock = { now },
            zone = ZoneOffset.UTC,
            playlistsOn = flowOf(0),
            playlistsExist = flowOf(false),
        )
        backgroundScope.launch { vm.allPlaylistsOff.collect {} }
        runCurrent()
        assertFalse(vm.allPlaylistsOff.value)
    }

    @Test fun rapidDoubleOkOpensPlayerOnce() = runTest {
        val vm = vm()
        runCurrent()
        vm.onOk(all[0], null)
        assertEquals(1L, vm.claimPlayer(vm.onOk(all[0], null)))
        // The guide hasn't left yet: more OKs (row or preview) don't navigate again.
        assertNull(vm.claimPlayer(vm.onOk(all[0], null)))
        assertNull(vm.claimPlayer(vm.onPreviewOk()))
        assertNull(vm.claimPlayer(OkAction.Previewed))
        // Back on screen: the next one does.
        vm.onScreenStarted()
        assertEquals(1L, vm.claimPlayer(vm.onPreviewOk()))
    }

    @Test fun editedChannelRefreshesInfoAndPreviewWithoutReplaying() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onOk(all[0], null)
        vm.onFocus(all[0], null)
        runCurrent()
        val renamed = all[0].copy(customName = "Renamed")
        lookups = listOf(renamed) + all.drop(1)
        vm.onChannelEdited(all[0].id)
        runCurrent()
        assertEquals("1  Renamed", channelLabel(vm.focused.value!!.channel))
        assertEquals(renamed, vm.previewing.value)
        assertEquals(listOf(1L), playback.played)
        // Another channel's edit leaves them alone.
        vm.onChannelEdited(all[1].id)
        runCurrent()
        assertEquals(renamed, vm.focused.value!!.channel)
        // A focus that lands before the re-read finishes wins (it carries the fresh row and block).
        val block = programme("e1", 1800, 3600, "Later")
        vm.onChannelEdited(all[0].id)
        vm.onFocus(renamed, block)
        runCurrent()
        assertEquals("Later", vm.focused.value!!.programme?.title)
    }

    @Test fun hidingTheLastChannelOfAGroupLeavesTheGroup() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "Sport")) // one channel
        vm.hideChannel(all[1])
        runCurrent()
        // The group leaves the list with its last channel: the grid shows All channels again.
        assertEquals(ChannelScope.All, vm.selectedScope.value)
        assertEquals(ChannelScope.All, vm.pages.value!!.scope)
        // A group with more channels stays.
        vm.selectGroup(ChannelScope.Group(1, "News"))
        vm.hideChannel(all[0])
        runCurrent()
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
    }

    @Test fun hidingTheLastChannelWhileAnotherGroupIsPendingKeepsThatGroup() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "Sport"))
        // The screen moved focus to the neighbouring group first.
        vm.onGroupFocused(ChannelScope.Group(1, "News"))
        vm.hideChannel(all[1])
        advanceTimeBy(GROUP_FOCUS_DEBOUNCE_MS + 1); runCurrent()
        assertEquals(ChannelScope.Group(1, "News"), vm.selectedScope.value)
    }

    @Test fun lastChannelOfGroupIsKnown() = runTest {
        val vm = vm()
        runCurrent()
        vm.selectGroup(ChannelScope.Group(1, "Sport"))
        assertTrue(vm.isLastOfShownGroup(all[1]))
        vm.selectGroup(ChannelScope.Group(1, "News"))
        assertFalse(vm.isLastOfShownGroup(all[0]))
        vm.selectGroup(ChannelScope.All)
        assertFalse(vm.isLastOfShownGroup(all[1]))
    }

    @Test fun failedHideShowsANotice() = runTest {
        val failing = object : EditActions by edits {
            override suspend fun setHidden(ref: io.github.loopholetv.data.db.ChannelRef, hidden: Boolean) = error("db")
        }
        val vm = GuideViewModel(
            groupRows = groupRows,
            channelPages = { all.asPagingSourceFactory()() },
            programmes = { _, _, _ -> flowOf(emptyMap()) },
            latestStop = { null },
            playback = playback,
            clock = { now },
            zone = ZoneOffset.UTC,
            edits = failing,
        )
        runCurrent()
        vm.hideChannel(all[0])
        runCurrent()
        assertEquals(NOTICE_EDIT_FAILED, vm.notice.value)
    }

    @Test fun headingsAppearOncePerPlaylistEvenWhenInterleaved() {
        val entries = withHeadings(
            listOf(
                GroupItem(ChannelScope.Group(1, "a"), "a", 1, "A"),
                GroupItem(ChannelScope.Group(2, "b"), "b", 1, "B"),
                GroupItem(ChannelScope.Group(1, "c"), "c", 1, "A"),
            ),
        )
        assertEquals(listOf("# A", "a", "# B", "b", "c"), entries.labels())
    }

    @Test fun sameGuideChannelAtTwoShiftsInOneSlice() = runTest {
        programmeRows = listOf(programme("e1", -600, 1200, "Show"))
        val vm = vm()
        backgroundScope.launch { vm.slice.collect {} }
        vm.setVisibleKeys(listOf(GuideKey("e1", 0), GuideKey("e1", 60)))
        runCurrent()
        val slice = vm.slice.value
        assertEquals(setOf(GuideKey("e1", 0), GuideKey("e1", 60)), slice.keys)
        assertEquals(now.minusSeconds(600), slice.byKey[GuideKey("e1", 0)]!!.single().start)
        assertEquals(now.plusSeconds(3000), slice.byKey[GuideKey("e1", 60)]!!.single().start)
    }

    @Test fun visibleOnlyLookupDropsHiddenRows() {
        assertEquals(all[0], visibleOnly(all[0]))
        assertNull(visibleOnly(all[0].copy(visible = false)))
        assertNull(visibleOnly(null))
    }

    @Test fun hidingTheFocusedChannelOrItsGroupClearsTheInfoArea() = runTest {
        val vm = vm()
        backgroundScope.launch { vm.focused.collect {} }
        vm.onFocus(all[0], null)
        runCurrent()
        vm.hideChannel(all[0])
        runCurrent()
        assertNull(vm.focused.value)
        vm.onFocus(all[1], null) // Sport
        runCurrent()
        vm.hideGroup(vm.group("Sport"))
        runCurrent()
        assertNull(vm.focused.value)
    }
}
