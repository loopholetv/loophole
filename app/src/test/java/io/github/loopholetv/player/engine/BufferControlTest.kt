package io.github.loopholetv.player.engine

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.settings.BufferPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for the Android types inside the fake timeline.
@RunWith(RobolectricTestRunner::class)
class BufferControlTest {

    private val mib = 1024 * 1024
    private val playerId = PlayerId("test")

    @Test
    fun largePresetCappedOnLowMemoryClass() {
        assertEquals(48 * mib, targetBytesFor(BufferPreset.LARGE, 192))
        assertEquals(64 * mib, targetBytesFor(BufferPreset.LARGE, 256))
        assertEquals(32 * mib, targetBytesFor(BufferPreset.NORMAL, 128))
        assertEquals(16 * mib, targetBytesFor(BufferPreset.SMALL, 128))
    }

    @Test
    fun presetAppliesAtNextPrepare() {
        val control = SwitchableLoadControl(BufferPreset.NORMAL, 256)
        control.onPrepared(playerId)
        control.setPreset(BufferPreset.SMALL)
        assertEquals(BufferPreset.NORMAL, control.activePreset)
        assertEquals(BufferPreset.SMALL, control.pendingPreset)

        control.onStopped(playerId)
        assertEquals(BufferPreset.NORMAL, control.activePreset)

        control.onPrepared(playerId)
        assertEquals(BufferPreset.SMALL, control.activePreset)
    }

    @Test
    fun setPresetBetweenStopAndPrepareTakesEffect() {
        val control = SwitchableLoadControl(BufferPreset.NORMAL, 256)
        control.onPrepared(playerId)
        control.onStopped(playerId)
        control.setPreset(BufferPreset.LARGE)
        assertEquals(BufferPreset.NORMAL, control.activePreset)

        control.onPrepared(playerId)
        assertEquals(BufferPreset.LARGE, control.activePreset)
    }

    @Test
    fun allocatorSharedAcrossPresets() {
        val control = SwitchableLoadControl(BufferPreset.NORMAL, 256)
        val pool = control.allocator
        control.onPrepared(playerId)
        val before = control.getAllocator(playerId)
        val first = before.allocate()
        assertEquals(pool.individualAllocationLength, pool.totalBytesAllocated)

        control.setPreset(BufferPreset.SMALL)
        control.onStopped(playerId)
        control.onPrepared(playerId)
        assertEquals(BufferPreset.SMALL, control.activePreset)
        val after = control.getAllocator(playerId)
        val second = after.allocate()
        assertEquals(2 * pool.individualAllocationLength, pool.totalBytesAllocated)

        before.release(first)
        after.release(second)
        assertEquals(0, pool.totalBytesAllocated)
        control.onReleased(playerId)
    }

    @Test
    fun shouldContinueLoadingHonoursByteCap() {
        val control = SwitchableLoadControl(BufferPreset.SMALL, 256)
        control.onPrepared(playerId)
        assertTrue(control.shouldContinueLoading(params()))

        val allocator = control.getAllocator(playerId)
        val held = mutableListOf<Allocation>()
        while (allocator.totalBytesAllocated < BufferPreset.SMALL.targetBytes) held += allocator.allocate()
        assertFalse(control.shouldContinueLoading(params()))

        held.forEach { allocator.release(it) }
        assertTrue(control.shouldContinueLoading(params()))
        control.onReleased(playerId)
    }

    @Test
    fun lowMemoryLargePresetUsesCappedBytes() {
        val control = SwitchableLoadControl(BufferPreset.LARGE, 192)
        control.onPrepared(playerId)
        val allocator = control.getAllocator(playerId)
        val held = mutableListOf<Allocation>()
        while (allocator.totalBytesAllocated < 48 * mib) held += allocator.allocate()
        assertFalse(control.shouldContinueLoading(params()))
        held.forEach { allocator.release(it) }
        control.onReleased(playerId)
    }

    @Test
    fun playerSwapsPresetAcrossStopAndPrepare() {
        val control = SwitchableLoadControl(BufferPreset.NORMAL, 256)
        val player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).setLoadControl(control).build()
        try {
            player.setMediaSource(FakeMediaSource())
            player.prepare()
            RobolectricUtil.runMainLooperUntil { player.playbackState == Player.STATE_READY }
            control.setPreset(BufferPreset.SMALL)
            assertEquals(BufferPreset.NORMAL, control.activePreset)

            player.stop()
            player.setMediaSource(FakeMediaSource())
            player.prepare()
            RobolectricUtil.runMainLooperUntil { player.playbackState == Player.STATE_READY }
            assertEquals(BufferPreset.SMALL, control.activePreset)
            player.play()
            RobolectricUtil.runMainLooperUntil { player.playbackState == Player.STATE_ENDED }
        } finally {
            player.release()
        }
        assertEquals(0, control.allocator.totalBytesAllocated)
    }

    private val timeline = FakeTimeline()
    private val periodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    private fun params() = LoadControl.Parameters(
        playerId,
        timeline,
        periodId,
        /* playbackPositionUs = */ 0,
        /* bufferedDurationUs = */ 0,
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ true,
        /* rebuffering = */ false,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
    )
}
