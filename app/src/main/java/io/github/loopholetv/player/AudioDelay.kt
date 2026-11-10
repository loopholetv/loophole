@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package io.github.loopholetv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Lip-sync offset shared between the UI and the playback thread.
 * Positive values make the audio be heard later than the picture.
 */
class AudioDelay(initialMs: Int = 0) {
    @Volatile
    var delayMs: Int = initialMs
}

/**
 * Applies an [AudioDelay] by shifting video sample timestamps by `-delay`.
 *
 * Audio is the master clock, so audio, subtitles and the reported position stay untouched; only the
 * presentation times of video samples move. Moving video earlier makes the picture lead the sound, which is
 * the same as delaying the sound. A changed value applies to samples read after the change. Because the
 * shifted timestamps reach the codec, this works for decoded, passthrough and software-decoded audio alike.
 */
class AudioDelayMediaSource(source: MediaSource, private val delay: AudioDelay) : WrappingMediaSource(source) {

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        DelayPeriod(mediaSource.createPeriod(id, allocator, startPositionUs), delay)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as DelayPeriod).inner)
    }
}

/** Forwards every call to [inner], re-dispatching its callbacks with itself and wrapping video sample streams. */
internal class DelayPeriod(val inner: MediaPeriod, private val delay: AudioDelay) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        inner.prepare(this, positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        checkNotNull(callback).onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        checkNotNull(callback).onContinueLoadingRequested(this)
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val wrappers = Array(streams.size) { streams[it] as? DelayStream }
        val innerStreams = Array(streams.size) { wrappers[it]?.inner ?: streams[it] }
        val result = inner.selectTracks(selections, mayRetainStreamFlags, innerStreams, streamResetFlags, positionUs)
        for (i in streams.indices) {
            val stream = innerStreams[i]
            streams[i] = when {
                stream == null -> null
                wrappers[i]?.inner === stream -> wrappers[i]
                isVideo(selections[i]) -> DelayStream(stream, delay)
                else -> stream
            }
        }
        return result
    }

    override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()
    override fun getTrackGroups(): TrackGroupArray = inner.trackGroups
    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(positionUs, toKeyframe)
    override fun readDiscontinuity(): Long = inner.readDiscontinuity()
    override fun setUsesStreamPrerollFlags() = inner.setUsesStreamPrerollFlags()
    override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        inner.getAdjustedSeekPositionUs(positionUs, seekParameters)
    override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs
    override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)
    override fun isLoading(): Boolean = inner.isLoading
    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)
    override fun setEndPositionUs(endPositionUs: Long): Long = inner.setEndPositionUs(endPositionUs)

    private fun isVideo(selection: ExoTrackSelection?): Boolean =
        selection != null &&
            (MimeTypes.isVideo(selection.selectedFormat.sampleMimeType) || selection.trackGroup.type == C.TRACK_TYPE_VIDEO)
}

/**
 * Video sample stream on the shifted timeline: read timestamps are `source - delay`, so positions coming in
 * from the renderer (e.g. [skipData]) are mapped back with `+ delay`.
 */
internal class DelayStream(val inner: SampleStream, private val delay: AudioDelay) : SampleStream {

    private val delayUs: Long get() = delay.delayMs * 1000L

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = inner.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) buffer.timeUs -= delayUs
        return result
    }

    override fun skipData(positionUs: Long): Int = inner.skipData(positionUs + delayUs)
    override fun isReady(): Boolean = inner.isReady
    override fun maybeThrowError() = inner.maybeThrowError()
    override fun getFlags(): Int = inner.flags
}
