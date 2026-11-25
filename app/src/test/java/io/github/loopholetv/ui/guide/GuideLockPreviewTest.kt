package io.github.loopholetv.ui.guide

import androidx.media3.common.Player
import androidx.paging.testing.asPagingSourceFactory
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideLockPreviewTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePlayback : Playback {
        override val player: Player get() = error("not used")
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state
        var stops = 0
        override fun play(channel: ChannelEntity) {
            _state.value = PlaybackState.Buffering(channel.id)
        }
        override fun stop() {
            stops++
            _state.value = PlaybackState.Idle
        }
    }

    private fun channel(id: Long, group: String) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = "Ch $id", streamUrl = "http://x/$id",
        logo = null, groupTitle = group, groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = "e$id",
    )

    private val kids = channel(1, "Kids")
    private val news = channel(2, "News")
    private val playback = FakePlayback()
    private val lock = MutableStateFlow<LockState>(LockState.Unlocked(null))
    private var failLookup = false
    private var playerShowing = false

    private fun vm() = GuideViewModel(
        groupRows = flowOf(emptyList()),
        channelPages = { emptyList<ChannelEntity>().asPagingSourceFactory()() },
        programmes = { _, _, _ -> flowOf(emptyMap()) },
        latestStop = { null },
        playback = playback,
        lockState = lock,
        playerShowing = { playerShowing },
        isLocked = { id -> if (failLookup) error("read failed") else id == kids.id },
        channelById = { id -> if (id == kids.id && lock.value is LockState.Locked) null else listOf(kids, news).firstOrNull { it.id == id } },
    )

    @Test fun relockStopsLockedPreview() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        assertEquals(kids, vm.previewing.value)

        lock.value = LockState.Locked
        runCurrent()

        assertNull(vm.previewing.value)
        assertEquals(1, playback.stops)
    }

    @Test fun relockKeepsUnlockedPreview() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(news, null)
        runCurrent()

        lock.value = LockState.Locked
        runCurrent()

        assertEquals(news, vm.previewing.value)
        assertEquals(0, playback.stops)
    }

    @Test fun relockWhileFullScreenLeavesThePlayerAlone() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        // Full screen opens over the guide: the stream is the player's now.
        vm.claimPlayer(vm.onOk(kids, null))
        vm.onScreenStopped()

        lock.value = LockState.Locked
        runCurrent()

        assertEquals(0, playback.stops)
    }

    @Test fun relockWithSettingsOverTheGuideStopsTheLockedPreview() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        // Settings opens over the guide; the preview keeps playing underneath.
        vm.onScreenStopped()

        lock.value = LockState.Locked
        runCurrent()

        assertNull(vm.previewing.value)
        assertEquals(1, playback.stops)
    }

    @Test fun returningFromFullScreenAfterRelockStopsTheLockedChannel() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        vm.claimPlayer(vm.onOk(kids, null))
        vm.onScreenStopped()
        lock.value = LockState.Locked
        runCurrent()
        assertEquals(0, playback.stops)

        vm.onScreenStarted()
        runCurrent()

        assertEquals(1, playback.stops)
        assertNull(vm.previewing.value)
    }

    @Test fun aFailedLockReadCountsAsLocked() = runTest {
        failLookup = true
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(news, null)
        runCurrent()

        lock.value = LockState.Locked
        runCurrent()

        assertNull(vm.previewing.value)
        assertEquals(1, playback.stops)
    }

    @Test fun relockLeavesAnotherStreamPlayingFullScreen() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        // A film, recording or other channel opened from another screen replaced the preview's stream.
        vm.onScreenStopped()
        playback.play(news)

        lock.value = LockState.Locked
        runCurrent()

        assertEquals(0, playback.stops)
        assertNull(vm.previewing.value)
    }

    @Test fun relockLeavesTheSameChannelOpenedFromSearchPlaying() = runTest {
        val vm = vm()
        vm.onScreenStarted()
        vm.onOk(kids, null)
        runCurrent()
        vm.onScreenStopped()
        playerShowing = true // the full-screen player is on screen, opened outside the guide

        lock.value = LockState.Locked
        runCurrent()

        assertEquals(0, playback.stops)
    }
}
