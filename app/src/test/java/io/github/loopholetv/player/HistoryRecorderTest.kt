package io.github.loopholetv.player

import io.github.loopholetv.data.db.ChannelEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecorderTest {
    private fun channel(id: Long) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = "Ch $id", streamUrl = "http://x/$id",
        logo = null, groupTitle = "All", groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = null,
    )

    private val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val recorded = mutableListOf<Long>()

    private fun TestScope.recorder(record: suspend (ChannelEntity) -> Unit = { recorded += it.id }): HistoryRecorder {
        val r = HistoryRecorder(state, { id -> channel(id) }, record, clock = { testScheduler.currentTime })
        assertNotNull(r.start(backgroundScope))
        runCurrent()
        return r
    }

    private fun TestScope.emit(s: PlaybackState) { state.value = s; runCurrent() }
    private fun TestScope.wait(ms: Long) { advanceTimeBy(ms); runCurrent() }

    private fun playing(id: Long, tags: StreamTags = StreamTags.NONE) = PlaybackState.Playing(id, tags)

    @Test fun recordsAfterTenSecondsPlaying() = runTest {
        recorder()
        emit(PlaybackState.Buffering(1))
        wait(5_000) // buffering doesn't count
        emit(playing(1))
        wait(HISTORY_MIN_PLAY_MS - 1)
        assertEquals(emptyList<Long>(), recorded)
        wait(1)
        assertEquals(listOf(1L), recorded)
    }

    @Test fun zapBeforeTenSecondsRecordsNothing() = runTest {
        recorder()
        emit(playing(1))
        wait(9_000)
        emit(PlaybackState.Buffering(2))
        emit(playing(2))
        wait(3_000)
        assertEquals(emptyList<Long>(), recorded)
        wait(7_000)
        assertEquals("only the channel watched for 10 s", listOf(2L), recorded)
    }

    @Test fun bufferingSameChannelDoesNotReset() = runTest {
        recorder()
        emit(playing(1))
        wait(6_000)
        emit(PlaybackState.Buffering(1))
        wait(20_000) // paused
        assertEquals(emptyList<Long>(), recorded)
        emit(PlaybackState.Error(1, "x"))
        wait(20_000)
        emit(playing(1))
        wait(3_999)
        assertEquals(emptyList<Long>(), recorded)
        wait(1)
        assertEquals(listOf(1L), recorded)
    }

    @Test fun tagUpdateDoesNotReset() = runTest {
        recorder()
        emit(playing(1))
        wait(6_000)
        emit(playing(1, StreamTags(resolution = "1080p", fps = null, audio = null)))
        wait(4_000)
        assertEquals(listOf(1L), recorded)
    }

    @Test fun idleResets() = runTest {
        recorder()
        emit(playing(1))
        wait(8_000)
        emit(PlaybackState.Idle)
        emit(playing(1))
        wait(8_000)
        assertEquals("Idle starts a new viewing", emptyList<Long>(), recorded)
        wait(2_000)
        assertEquals(listOf(1L), recorded)
    }

    @Test fun recordsOncePerViewing() = runTest {
        val r = recorder()
        emit(playing(1))
        wait(30_000)
        emit(PlaybackState.Buffering(1))
        emit(playing(1))
        wait(30_000)
        assertEquals(listOf(1L), recorded)
        emit(playing(2))
        wait(10_000)
        emit(playing(1))
        wait(10_000)
        assertEquals("switching away and back is a new viewing", listOf(1L, 2L, 1L), recorded)
        assertNull("start is idempotent", r.start(backgroundScope))
    }

    @Test fun recordFailureIsSwallowed() = runTest {
        var calls = 0
        recorder(record = { calls++; error("disk full") })
        emit(playing(1))
        wait(10_000)
        emit(playing(2))
        wait(10_000)
        assertEquals("keeps recording after a failure", 2, calls)
    }
}
