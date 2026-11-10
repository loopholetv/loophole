@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import io.github.loopholetv.data.settings.BufferPreset

private const val MIB = 1024 * 1024

/** Below this per-app heap size (`ActivityManager.memoryClass`, MB) the large buffer is capped. */
private const val LOW_MEMORY_CLASS_MB = 256

private const val LOW_MEMORY_LARGE_CAP_BYTES = 48 * MIB

/** The byte cap for [preset]: [BufferPreset.LARGE] is lowered to 48 MiB on devices with a small heap. */
fun targetBytesFor(preset: BufferPreset, memoryClassMb: Int): Int =
    if (preset == BufferPreset.LARGE && memoryClassMb < LOW_MEMORY_CLASS_MB) {
        minOf(preset.targetBytes, LOW_MEMORY_LARGE_CAP_BYTES)
    } else {
        preset.targetBytes
    }

/**
 * A [LoadControl] whose buffer preset can change without rebuilding the player.
 *
 * Each preset has its own [DefaultLoadControl]; all of them share one [DefaultAllocator], so buffer memory
 * comes from a single pool whichever preset is active. [setPreset] only records the wanted preset. It takes effect at the next
 * [onPrepared], which the player calls when a new stream is prepared after `stop()`. Every other callback
 * goes to the control that received the last [onPrepared], so a stream keeps one control from prepare to
 * stop or release.
 */
class SwitchableLoadControl(initial: BufferPreset, memoryClassMb: Int) : LoadControl {

    /** The memory pool every preset shares. */
    val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val controls: Map<BufferPreset, DefaultLoadControl> = BufferPreset.entries.associateWith { preset ->
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(preset.minMs, preset.maxMs, preset.startMs, preset.afterRebufferMs)
            .setTargetBufferBytes(targetBytesFor(preset, memoryClassMb))
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .build()
    }

    @Volatile
    private var pending: BufferPreset = initial

    /** Written only on the playback thread (in [onPrepared]); read from any thread. */
    @Volatile
    private var active: BufferPreset = initial

    /** The preset of the stream being played, or of the last one. */
    val activePreset: BufferPreset get() = active

    /** The preset the next prepared stream will use. */
    val pendingPreset: BufferPreset get() = pending

    /** Uses [preset] from the next stream start. Safe to call from any thread. */
    fun setPreset(preset: BufferPreset) {
        pending = preset
    }

    private val delegate: DefaultLoadControl get() = controls.getValue(active)

    override fun onPrepared(playerId: PlayerId) {
        active = pending
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    /**
     * The active control's per-player view of the shared [allocator]. It counts this player's allocations,
     * which is what the byte cap is checked against; the memory itself always comes from [allocator].
     */
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}
