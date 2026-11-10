package io.github.loopholetv.player.engine

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.settings.DecoderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CodecPolicyTest {

    private fun info(name: String, mime: String, hardware: Boolean) = MediaCodecInfo.newInstance(
        name,
        mime,
        mime,
        /* capabilities = */ null,
        /* hardwareAccelerated = */ hardware,
        /* softwareOnly = */ !hardware,
        /* vendor = */ hardware,
        /* forceDisableAdaptive = */ false,
        /* forceSecure = */ false,
    )

    private val hwAvc = info("c2.vendor.avc", MimeTypes.VIDEO_H264, hardware = true)
    private val swAvc = info("c2.android.avc", MimeTypes.VIDEO_H264, hardware = false)
    private val hwAc3 = info("OMX.dolby.ac3.decoder", MimeTypes.AUDIO_AC3, hardware = true)

    private fun base(vararg infos: MediaCodecInfo) = MediaCodecSelector { mime, _, _ ->
        infos.filter { it.mimeType == mime }
    }

    private fun names(list: List<MediaCodecInfo>) = list.map { it.name }

    @Test
    fun hardwareKeepsBaseOrder() {
        val selector = PolicyCodecSelector(CodecPolicy(), { true }, base(hwAvc, swAvc, hwAc3))
        assertEquals(listOf("c2.vendor.avc", "c2.android.avc"), names(selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)))
        assertEquals(listOf("OMX.dolby.ac3.decoder"), names(selector.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false)))
    }

    @Test
    fun softwareVideoPutsSoftwareFirst() {
        val policy = CodecPolicy().apply { video = DecoderChoice.SOFTWARE }
        val selector = PolicyCodecSelector(policy, { true }, base(hwAvc, swAvc))
        assertEquals(listOf("c2.android.avc", "c2.vendor.avc"), names(selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)))
    }

    @Test
    fun softwareVideoFallsBackWhenNone() {
        val policy = CodecPolicy().apply { video = DecoderChoice.SOFTWARE }
        val selector = PolicyCodecSelector(policy, { true }, base(hwAvc))
        assertEquals(listOf("c2.vendor.avc"), names(selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)))
    }

    @Test
    fun softwareVideoLeavesAudioAlone() {
        val policy = CodecPolicy().apply { video = DecoderChoice.SOFTWARE }
        val selector = PolicyCodecSelector(policy, { true }, base(hwAc3))
        assertEquals(listOf("OMX.dolby.ac3.decoder"), names(selector.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false)))
    }

    @Test
    fun softwareAudioHandsToFfmpeg() {
        val policy = CodecPolicy().apply { audio = DecoderChoice.SOFTWARE }
        val asked = mutableListOf<String>()
        val selector = PolicyCodecSelector(policy, { asked += it; true }, base(hwAc3, hwAvc, swAvc))
        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false).isEmpty())
        assertEquals(listOf(MimeTypes.AUDIO_AC3), asked)
        // Video is unaffected by the audio choice.
        assertEquals(listOf("c2.vendor.avc", "c2.android.avc"), names(selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)))
    }

    @Test
    fun softwareAudioFallsBackWhenFfmpegCannot() {
        val policy = CodecPolicy().apply { audio = DecoderChoice.SOFTWARE }
        val selector = PolicyCodecSelector(policy, { false }, base(hwAc3))
        assertEquals(listOf("OMX.dolby.ac3.decoder"), names(selector.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false)))
    }

    @Test
    fun policyChangesApplyToNextQuery() {
        val policy = CodecPolicy()
        val selector = PolicyCodecSelector(policy, { true }, base(hwAvc, swAvc))
        assertEquals("c2.vendor.avc", selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false).first().name)
        policy.video = DecoderChoice.SOFTWARE
        assertEquals("c2.android.avc", selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false).first().name)
    }

    @Test
    fun softwareAudioDoesNotBlockPassthrough() {
        val policy = CodecPolicy().apply { audio = DecoderChoice.SOFTWARE }
        val selector = PolicyCodecSelector(policy, { true }, base(hwAc3))
        val renderer = MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            selector,
            Handler(Looper.getMainLooper()),
            /* eventListener = */ null,
            fakeAudioSink(supportsEncoded = true),
        )
        val ac3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(renderer.supportsFormat(ac3)))

        // The same stream with a sink that cannot take it is left to the FFmpeg renderer.
        val decodeOnly = MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            selector,
            Handler(Looper.getMainLooper()),
            null,
            fakeAudioSink(supportsEncoded = false),
        )
        assertTrue(RendererCapabilities.getFormatSupport(decodeOnly.supportsFormat(ac3)) != C.FORMAT_HANDLED)
    }
}

/** An [AudioSink] that accepts PCM, and encoded audio only when [supportsEncoded]. Every other call is a no-op. */
internal fun fakeAudioSink(supportsEncoded: Boolean): AudioSink {
    fun supported(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_RAW || supportsEncoded
    return java.lang.reflect.Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, args ->
        when (method.name) {
            "supportsFormat" -> supported(args[0] as Format)
            "getFormatSupport" ->
                if (supported(args[0] as Format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
            "getFormatOffloadSupport" -> AudioOffloadSupport.DEFAULT_UNSUPPORTED
            else -> defaultFor(method.returnType)
        }
    } as AudioSink
}

private fun defaultFor(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    else -> null
}
