package io.github.loopholetv

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPlaybackStopTest {
    private fun playlist(id: Long, enabled: Boolean = true) = PlaylistEntity(
        id = id, name = "P$id", type = PlaylistType.M3U, url = "http://example.test/$id", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
        lastRefreshedAt = null, lastError = null, enabled = enabled,
    )

    @Test fun stopsWhenPlayingChannelsPlaylistIsTurnedOffOrDeleted() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val playlists = MutableStateFlow(listOf(playlist(1), playlist(2)))
        val channelPlaylist = mutableMapOf(10L to 1L, 20L to 2L)
        var stops = 0
        stopPlaybackWhenPlaylistGoes(
            state, playlists, { channelPlaylist[it] }, { stops++; state.value = PlaybackState.Idle },
            backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )

        state.value = PlaybackState.Playing(10, StreamTags.NONE)
        playlists.value = listOf(playlist(1), playlist(2, enabled = false)) // another playlist: keep playing
        assertEquals(0, stops)
        playlists.value = listOf(playlist(1, enabled = false), playlist(2, enabled = false))
        assertEquals(1, stops)

        playlists.value = listOf(playlist(1), playlist(2))
        state.value = PlaybackState.Buffering(20)
        channelPlaylist.remove(20L) // its row goes with the playlist
        playlists.value = listOf(playlist(1))
        assertEquals(2, stops)

        // A channel that vanished in a refresh (its playlist still on) keeps playing here.
        state.value = PlaybackState.Playing(10, StreamTags.NONE)
        channelPlaylist.remove(10L)
        playlists.value = listOf(playlist(1).copy(lastError = "x"))
        assertEquals(2, stops)
    }

    @Test fun errorsMidStreamDoNotStopLaterStops() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val playlists = MutableStateFlow(listOf(playlist(1), playlist(2)))
        var failLookup = true
        var playlistsFailOnce = true
        val flaky = kotlinx.coroutines.flow.flow {
            if (playlistsFailOnce) { playlistsFailOnce = false; emit(playlists.value); throw IllegalStateException("db gone") }
            emitAll(playlists)
        }
        var stops = 0
        stopPlaybackWhenPlaylistGoes(
            state, flaky, { if (failLookup) error("lookup failed http://example.test/u/p") else it / 10 },
            { stops++; state.value = PlaybackState.Idle },
            backgroundScope, main = UnconfinedTestDispatcher(testScheduler),
        )
        state.value = PlaybackState.Playing(10, StreamTags.NONE) // the lookup throws
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent() // the playlists flow failed once too: the watcher restarts
        failLookup = false
        state.value = PlaybackState.Playing(20, StreamTags.NONE)
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        playlists.value = listOf(playlist(1), playlist(2, enabled = false))
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(1, stops)
    }
}
