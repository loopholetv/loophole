package io.github.loopholetv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamTagsTest {

    private fun video(width: Int, height: Int, fps: Float = Format.NO_VALUE.toFloat()) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(fps)
        .build()

    private fun audio(channels: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setChannelCount(channels)
        .build()

    @Test
    fun fullHdFiftyStereo() {
        assertEquals(StreamTags("FHD", "50 FPS", "STEREO"), streamTags(video(1920, 1080, 50f), audio(2)))
    }

    @Test
    fun hdRoundsFrameRate() {
        assertEquals(StreamTags("HD", "30 FPS", null), streamTags(video(1280, 720, 29.97f), null))
    }

    @Test
    fun nullFormatsGiveNoTags() {
        assertEquals(StreamTags(null, null, null), streamTags(null, null))
    }

    @Test
    fun resolutionThresholds() {
        val cases = mapOf(2160 to "4K", 4320 to "4K", 1080 to "FHD", 1440 to "FHD", 720 to "HD", 719 to "SD", 576 to "SD")
        cases.forEach { (height, expected) ->
            assertEquals("height $height", expected, streamTags(video(height * 16 / 9, height), null).resolution)
        }
    }

    @Test
    fun unknownVideoValuesAreNull() {
        val tags = streamTags(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), null)
        assertEquals(StreamTags(null, null, null), tags)
    }

    @Test
    fun audioChannelLayouts() {
        val cases = mapOf(1 to "MONO", 2 to "STEREO", 6 to "5.1", 8 to "7.1", 3 to null, Format.NO_VALUE to null)
        cases.forEach { (channels, expected) ->
            assertEquals("channels $channels", expected, streamTags(null, audio(channels)).audio)
        }
    }
}
