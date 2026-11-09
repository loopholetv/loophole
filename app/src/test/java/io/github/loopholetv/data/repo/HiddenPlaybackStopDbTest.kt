package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.stopPlaybackWhenChannelHidden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** An edit made in Settings (a dialog over the guide) stops the channel previewing underneath. */
@RunWith(RobolectricTestRunner::class)
class HiddenPlaybackStopDbTest {
    private lateinit var db: AppDatabase
    private lateinit var edits: ChannelEdits
    private lateinit var p: OverlayPlaylist
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stops = AtomicInteger()
    /** observeVisible results seen by the watcher (Room re-queries on every channels change). */
    private val emissions = AtomicInteger()

    @Before fun setUp() {
        db = inMemoryDb()
        edits = ChannelEdits(db, ChannelOverlay(db))
        p = OverlayPlaylist(db)
    }

    @After fun tearDown() {
        scope.cancel()
        p.close()
        db.close()
    }

    private suspend fun watch(channelId: Long) {
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Playing(channelId, StreamTags.NONE))
        stopPlaybackWhenChannelHidden(
            state, { id -> db.channelDao().observeVisible(id).onEach { emissions.incrementAndGet() } },
            { stops.incrementAndGet() }, scope, main = Dispatchers.Default,
        )
        awaitCondition { emissions.get() >= 1 } // the watcher has Room's first result
    }

    /** Waits (on real time, bounded) until [done]; no fixed sleeps. */
    private suspend fun awaitCondition(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!done()) yield() }
    }

    private suspend fun awaitStops(n: Int) = awaitCondition { stops.get() >= n }

    @Test fun hidingThePreviewedChannelStopsPlayback() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B"))
        watch(p.channel("A").id)
        val seen = emissions.get()
        edits.setHidden(p.ref("B"), true) // another channel
        awaitCondition { emissions.get() > seen } // the watcher has seen A still visible after it
        assertEquals(0, stops.get())
        edits.setHidden(p.ref("A"), true)
        awaitStops(1)
        assertEquals(1, stops.get())
    }

    @Test fun turningThePlaylistOffStopsPlayback() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B"))
        watch(p.channel("A").id)
        edits.setPlaylistEnabled(p.id, false)
        awaitStops(1)
        assertEquals(1, stops.get())
    }

    @Test fun hidingTheGroupStopsPlayback() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B"))
        watch(p.channel("A").id)
        edits.setGroupHidden(p.id, "G", true)
        awaitStops(1)
        assertEquals(1, stops.get())
    }
}
