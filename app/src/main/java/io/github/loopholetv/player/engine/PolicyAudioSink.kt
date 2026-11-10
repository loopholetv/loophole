@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.loopholetv.data.settings.Passthrough

/**
 * Audio output choices for the next stream; read on the playback thread when renderers check formats.
 *
 * With [passthrough] AUTO and a sink that takes the encoding, compressed audio is sent to the TV or receiver
 * as is and no decoder runs, so a "software" audio decoder choice has no effect for it. OFF forces decoding.
 */
class SinkPolicy {
    @Volatile
    var passthrough: Passthrough = Passthrough.AUTO

    /** Decode DTS here instead of passing it through, for outputs that accept DTS but play it silently. */
    @Volatile
    var decodeDtsToPcm: Boolean = false
}

private val DTS_FAMILY = setOf(
    MimeTypes.AUDIO_DTS,
    MimeTypes.AUDIO_DTS_HD,
    MimeTypes.AUDIO_DTS_EXPRESS,
    MimeTypes.AUDIO_DTS_X,
)

/**
 * An [AudioSink] that reports compressed formats as unsupported when [policy] says to decode them, so the
 * renderers decode to PCM instead of passing the bitstream through. PCM and everything else is delegated.
 */
class PolicyAudioSink(sink: AudioSink, private val policy: SinkPolicy) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean = !blocked(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (blocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    private fun blocked(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        return policy.passthrough == Passthrough.OFF || (policy.decodeDtsToPcm && mime in DTS_FAMILY)
    }
}
