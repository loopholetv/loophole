@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.player.AudioDelay

/**
 * The switchable parts of the player engine. Changing a policy or the buffer preset applies at the next
 * stream start; [audioDelay] applies to samples read after the change.
 */
class EngineParts(
    val codecPolicy: CodecPolicy = CodecPolicy(),
    val colourPolicy: ColourPolicy = ColourPolicy(),
    val sinkPolicy: SinkPolicy = SinkPolicy(),
    val loadControl: SwitchableLoadControl = SwitchableLoadControl(BufferPreset.NORMAL, 256),
    val audioDelay: AudioDelay = AudioDelay(),
)

/** Whether the FFmpeg native library loaded. False, never an exception, when it is missing or broken. */
fun ffmpegAvailable(): Boolean = try {
    FfmpegLibrary.isAvailable()
} catch (t: Throwable) {
    false
}

/** Whether FFmpeg is loaded and can decode audio of [mime]. False, never an exception, on any failure. */
fun ffmpegSupports(mime: String): Boolean = try {
    FfmpegLibrary.supportsFormat(mime)
} catch (t: Throwable) {
    false
}

/**
 * Builds the app's player around [parts].
 *
 * The FFmpeg audio renderer is added after the MediaCodec one (extension mode ON), so it is used only when
 * MediaCodec cannot handle a track and passthrough stays possible. If its native library is missing the
 * renderer reports every format unsupported and MediaCodec is used as before.
 */
fun buildEnginePlayer(context: Context, parts: EngineParts): ExoPlayer {
    val renderers = EngineRenderersFactory(context.applicationContext, parts.sinkPolicy, parts.colourPolicy)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(PolicyCodecSelector(parts.codecPolicy, ::ffmpegSupports))
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context.applicationContext, renderers)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(audio, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(parts.loadControl)
        .build()
}

/**
 * Media3's limit on dropped frames between reports, as its own factory sets it. The builder in
 * [EngineRenderersFactory.buildVideoRenderers] copies Media3 1.11.1's private `createMediaCodecVideoRenderer`;
 * re-check it against that method whenever Media3 is upgraded.
 */
private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50

/**
 * Wraps the default audio sink in a [PolicyAudioSink]; MediaCodec and FFmpeg renderers share it. The
 * MediaCodec video renderer is replaced by an [EngineVideoRenderer] built the same way.
 */
private class EngineRenderersFactory(
    context: Context,
    private val sinkPolicy: SinkPolicy,
    private val colourPolicy: ColourPolicy,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out,
        )
        val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
        if (index < 0) return
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_FRAMES_TO_NOTIFY)
        out[index] = EngineVideoRenderer(builder, colourPolicy)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        ?.let { PolicyAudioSink(it, sinkPolicy) }
}
