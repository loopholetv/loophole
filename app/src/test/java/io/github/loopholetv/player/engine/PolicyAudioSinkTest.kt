package io.github.loopholetv.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.github.loopholetv.data.settings.Passthrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAudioSinkTest {

    private fun format(mime: String) = Format.Builder()
        .setSampleMimeType(mime)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .apply { if (mime == MimeTypes.AUDIO_RAW) setPcmEncoding(C.ENCODING_PCM_16BIT) }
        .build()

    private val ac3 = format(MimeTypes.AUDIO_AC3)
    private val pcm = format(MimeTypes.AUDIO_RAW)
    private val dtsFamily = listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X)

    private fun sink(policy: SinkPolicy) = PolicyAudioSink(fakeAudioSink(supportsEncoded = true), policy)

    @Test
    fun autoDelegatesEverything() {
        val sink = sink(SinkPolicy())
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
        assertTrue(sink.supportsFormat(ac3))
        dtsFamily.forEach { assertTrue(it, sink.supportsFormat(format(it))) }
    }

    @Test
    fun passthroughOffRejectsEncoded() {
        val sink = sink(SinkPolicy().apply { passthrough = Passthrough.OFF })
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ac3))
        assertFalse(sink.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
        assertTrue(sink.supportsFormat(pcm))
    }

    @Test
    fun dtsDecodedWhenAsked() {
        val policy = SinkPolicy()
        val sink = sink(policy)
        dtsFamily.forEach { assertTrue(it, sink.supportsFormat(format(it))) }

        policy.decodeDtsToPcm = true
        dtsFamily.forEach {
            assertFalse(it, sink.supportsFormat(format(it)))
            assertEquals(it, AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format(it)))
        }
        assertTrue(sink.supportsFormat(ac3))
        assertTrue(sink.supportsFormat(pcm))
    }
}
