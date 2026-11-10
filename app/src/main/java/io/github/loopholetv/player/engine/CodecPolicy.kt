@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.loopholetv.data.settings.DecoderChoice

/**
 * Decoder choices for the next stream. Written by the UI thread before a stream starts and read on the
 * playback thread whenever a codec is initialised, which happens at every stream start.
 */
class CodecPolicy {
    @Volatile
    var video: DecoderChoice = DecoderChoice.HARDWARE

    @Volatile
    var audio: DecoderChoice = DecoderChoice.HARDWARE
}

/**
 * A [MediaCodecSelector] that follows [policy] at every query.
 *
 * - Hardware: the [base] list, unchanged.
 * - Software video: the [base] list with software decoders first, so decoder fallback still reaches a
 *   hardware decoder if the software one fails. With no software decoder for the codec (often HEVC) this is
 *   the [base] list.
 * - Software audio: no MediaCodec decoder when FFmpeg can decode the format ([ffmpegSupports]), so the
 *   FFmpeg renderer that follows takes the track; otherwise the [base] list.
 *
 * Software audio only changes who decodes. With passthrough on and a sink that accepts the encoding, the
 * stream is sent to the TV or receiver as is and no decoder runs, so the choice has no effect there.
 * Turning passthrough off forces decoding.
 */
class PolicyCodecSelector(
    private val policy: CodecPolicy,
    private val ffmpegSupports: (mime: String) -> Boolean,
    private val base: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return when {
            MimeTypes.isVideo(mimeType) && policy.video == DecoderChoice.SOFTWARE -> softwareFirst(infos)
            MimeTypes.isAudio(mimeType) && policy.audio == DecoderChoice.SOFTWARE ->
                if (ffmpegSupports(mimeType)) emptyList() else infos
            else -> infos
        }
    }

    private fun softwareFirst(infos: List<MediaCodecInfo>): List<MediaCodecInfo> {
        val (software, rest) = infos.partition { it.softwareOnly || !it.hardwareAccelerated }
        return software + rest
    }
}
