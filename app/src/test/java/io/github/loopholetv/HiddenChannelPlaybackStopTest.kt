package io.github.loopholetv

import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiddenChannelPlaybackStopTest {
    @Test fun stopsWhenPlayingChannelTurnsHiddenButNotWhenItVanishes() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val visible = mapOf(10L to MutableStateFlow<Boolean?>(true), 20L to MutableStateFlow<Boolean?>(true))
        var stops = 0
        stopPlaybackWhenChannelHidden(
            state, { id -> visible.getValue(id) }, { stops++; state.value = PlaybackState.Idle },
            backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )
        state.value = PlaybackState.Buffering(10)
        visible.getValue(20).value = false // another channel: keep playing
        assertEquals(0, stops)
        visible.getValue(10).value = false // hidden from Settings while previewing
        assertEquals(1, stops)

        // A row that vanishes in a refresh (null) is not a hide.
        visible.getValue(20).value = true
        state.value = PlaybackState.Playing(20, StreamTags.NONE)
        visible.getValue(20).value = null
        assertEquals(1, stops)
        // Its playlist turned off: visible = 0 as well.
        visible.getValue(20).value = true
        visible.getValue(20).value = false
        assertEquals(2, stops)
    }

    @Test fun lateHiddenSignalForTheOldChannelDoesNotStopTheNextOne() = runTest(UnconfinedTestDispatcher()) {
        // Hiding the current channel in the full-screen player zaps to the next one; the watcher's
        // stop for the old id may land on main after play(next) and must not stop the new stream.
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Playing(10, StreamTags.NONE))
        val visible = mapOf(10L to MutableStateFlow<Boolean?>(true), 20L to MutableStateFlow<Boolean?>(true))
        var stops = 0
        val main = StandardTestDispatcher(testScheduler)
        stopPlaybackWhenChannelHidden(
            state, { id -> visible.getValue(id) }, { stops++; state.value = PlaybackState.Idle },
            backgroundScope, main = main, currentId = { state.value.idOrNull() },
        )
        visible.getValue(10).value = false // the stop for 10 is queued on main...
        state.value = PlaybackState.Buffering(20) // ...while the player zaps to 20
        testScheduler.runCurrent()
        assertEquals(0, stops)
        assertEquals(PlaybackState.Buffering(20), state.value)
        // The channel that is playing still stops when it turns hidden.
        visible.getValue(20).value = false
        testScheduler.runCurrent()
        assertEquals(1, stops)
    }

    @Test fun aFollowedCastIsNeverStopped() = runTest(UnconfinedTestDispatcher()) {
        // Another TV's stream has no channel row here, whatever a lookup of its id says.
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        var stops = 0
        stopPlaybackWhenChannelHidden(
            state, { flowOf(false) }, { stops++; state.value = PlaybackState.Idle },
            backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )
        state.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
        state.value = PlaybackState.Playing(CAST_CHANNEL_ID, StreamTags.NONE)
        assertEquals(0, stops)
    }

    private fun PlaybackState.idOrNull(): Long? = when (this) {
        PlaybackState.Idle -> null
        is PlaybackState.Buffering -> channelId
        is PlaybackState.Playing -> channelId
        is PlaybackState.Error -> channelId
    }

    @Test fun startingAnAlreadyHiddenChannelStopsIt() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        var stops = 0
        stopPlaybackWhenChannelHidden(
            state, { flowOf(false) }, { stops++ },
            backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )
        state.value = PlaybackState.Buffering(5)
        assertEquals(1, stops)
    }

    @Test fun lookupErrorsRestartTheWatch() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val visible = MutableStateFlow<Boolean?>(true)
        var failOnce = true
        var stops = 0
        stopPlaybackWhenChannelHidden(
            state,
            {
                flow {
                    if (failOnce) { failOnce = false; throw IllegalStateException("db gone http://example.test/u/p") }
                    emitAll(visible)
                }
            },
            { stops++ }, backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )
        state.value = PlaybackState.Playing(10, StreamTags.NONE)
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        visible.value = false
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(1, stops)
    }
}
