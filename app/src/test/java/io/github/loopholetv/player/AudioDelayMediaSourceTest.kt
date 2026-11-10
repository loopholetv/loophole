package io.github.loopholetv.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.test.utils.FakeMediaPeriod
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioDelayMediaSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val videoFormat = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()
    private val audioFormat = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()

    /** Three key-frame samples at 0, 40 ms and 80 ms on every track, then end of stream. */
    private val samples = FakeMediaPeriod.TrackDataFactory { _, _ ->
        listOf(
            FakeSampleStreamItem.oneByteSample(0, C.BUFFER_FLAG_KEY_FRAME),
            FakeSampleStreamItem.oneByteSample(40_000, C.BUFFER_FLAG_KEY_FRAME),
            FakeSampleStreamItem.oneByteSample(80_000, C.BUFFER_FLAG_KEY_FRAME),
            FakeSampleStreamItem.END_OF_STREAM_ITEM,
        )
    }

    private class RecordingSource(factory: FakeMediaPeriod.TrackDataFactory, vararg formats: Format) :
        FakeMediaSource(FakeTimeline(), DrmSessionManager.DRM_UNSUPPORTED, factory, *formats) {
        val released = mutableListOf<MediaPeriod>()
        override fun releaseMediaPeriod(mediaPeriod: MediaPeriod) {
            released += mediaPeriod
            super.releaseMediaPeriod(mediaPeriod)
        }
    }

    private class Harness(
        val fake: RecordingSource,
        val source: AudioDelayMediaSource,
        val period: MediaPeriod,
        val video: SampleStream,
        val audio: SampleStream,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun harness(delay: AudioDelay): Harness {
        val fake = RecordingSource(samples, videoFormat, audioFormat)
        val source = AudioDelayMediaSource(fake, delay)
        var timeline: Timeline? = null
        source.prepareSource({ _, t -> timeline = t }, PlayerId.UNSET, DefaultBandwidthMeter.getSingletonInstance(context))
        idle()
        val id = MediaSource.MediaPeriodId(checkNotNull(timeline).getUidOfPeriod(0))
        val period = source.createPeriod(id, DefaultAllocator(true, 1024), 0)
        var prepared = false
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                assertSame(period, mediaPeriod)
                prepared = true
            }
            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0)
        idle()
        assertTrue(prepared)
        val groups = period.trackGroups
        val selections = Array<ExoTrackSelection?>(groups.length) { FixedTrackSelection(groups[it], 0) }
        val streams = arrayOfNulls<SampleStream>(groups.length)
        period.selectTracks(selections, BooleanArray(groups.length), streams, BooleanArray(groups.length), 0)
        period.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
        idle()
        val videoIndex = (0 until groups.length).single { groups[it].type == C.TRACK_TYPE_VIDEO }
        val audioIndex = (0 until groups.length).single { groups[it].type == C.TRACK_TYPE_AUDIO }
        return Harness(fake, source, period, checkNotNull(streams[videoIndex]), checkNotNull(streams[audioIndex]))
    }

    private fun buffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    /** Reads up to [max] sample buffers (skipping format reads), returning their timestamps. */
    private fun readSamples(stream: SampleStream, max: Int = Int.MAX_VALUE): List<Long> {
        val out = mutableListOf<Long>()
        val holder = FormatHolder()
        val buf = buffer()
        while (out.size < max) {
            buf.clear()
            when (stream.readData(holder, buf, 0)) {
                C.RESULT_FORMAT_READ -> continue
                C.RESULT_BUFFER_READ -> if (buf.isEndOfStream) return out else out += buf.timeUs
                else -> return out
            }
        }
        return out
    }

    @Test
    fun delayShiftsVideoOnly() {
        val h = harness(AudioDelay(200))
        assertEquals(listOf(-200_000L, -160_000L, -120_000L), readSamples(h.video))
        assertEquals(listOf(0L, 40_000L, 80_000L), readSamples(h.audio))
    }

    @Test
    fun negativeDelayMovesVideoLater() {
        val h = harness(AudioDelay(-100))
        assertEquals(listOf(100_000L, 140_000L, 180_000L), readSamples(h.video))
        assertEquals(listOf(0L, 40_000L, 80_000L), readSamples(h.audio))
    }

    @Test
    fun zeroDelayIsIdentity() {
        val h = harness(AudioDelay())
        assertEquals(listOf(0L, 40_000L, 80_000L), readSamples(h.video))
        assertEquals(listOf(0L, 40_000L, 80_000L), readSamples(h.audio))
    }

    @Test
    fun changeAppliesToNextSample() {
        val delay = AudioDelay(0)
        val h = harness(delay)
        assertEquals(listOf(0L), readSamples(h.video, max = 1))
        delay.delayMs = 300
        assertEquals(listOf(40_000L - 300_000L, 80_000L - 300_000L), readSamples(h.video))
    }

    @Test
    fun formatAndEndOfStreamUntouched() {
        val shifted = harness(AudioDelay(200))
        val plain = harness(AudioDelay(0))
        val holder = FormatHolder()
        val buf = buffer()
        buf.timeUs = 123L
        assertEquals(C.RESULT_FORMAT_READ, shifted.video.readData(holder, buf, 0))
        assertEquals(MimeTypes.VIDEO_H264, holder.format?.sampleMimeType)
        assertEquals(123L, buf.timeUs)

        fun endOfStreamTime(stream: SampleStream): Long {
            readSamples(stream)
            val b = buffer()
            // readSamples consumed the end-of-stream buffer; reading again repeats it.
            assertEquals(C.RESULT_BUFFER_READ, stream.readData(FormatHolder(), b, 0))
            assertTrue(b.isEndOfStream)
            return b.timeUs
        }
        assertEquals(endOfStreamTime(plain.video), endOfStreamTime(shifted.video))
    }

    @Test
    fun skipDataMapsShiftedPositionToSourceTime() {
        val h = harness(AudioDelay(200))
        // Shifted timeline: -200 ms, -160 ms, -120 ms. Skipping to -120 ms lands on the third sample.
        assertEquals(2, h.video.skipData(-120_000))
        assertEquals(listOf(-120_000L), readSamples(h.video))
    }

    @Test
    fun releasePeriodUnwraps() {
        val h = harness(AudioDelay(200))
        val inner = h.fake.lastCreatedActiveMediaPeriod
        assertNotNull(inner)
        h.source.releasePeriod(h.period)
        assertEquals(listOf(inner), h.fake.released)
    }

    @Test
    fun wrappedSourceReachesReadyInPlayer() {
        val player = TestExoPlayerBuilder(context).build()
        try {
            val fake = FakeMediaSource(FakeTimeline(), DrmSessionManager.DRM_UNSUPPORTED, samples, videoFormat, audioFormat)
            player.setMediaSource(AudioDelayMediaSource(fake, AudioDelay(200)))
            player.prepare()
            RobolectricUtil.runMainLooperUntil { player.playbackState == Player.STATE_READY }
            assertEquals(Player.STATE_READY, player.playbackState)
            player.play()
            RobolectricUtil.runMainLooperUntil { player.playbackState == Player.STATE_ENDED }
        } finally {
            player.release()
        }
    }
}
