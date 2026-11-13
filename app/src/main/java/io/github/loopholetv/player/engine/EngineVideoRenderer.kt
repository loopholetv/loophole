@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player.engine

import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * The MediaCodec video renderer the app plays with.
 *
 * - The decoder's colour tags follow [colourPolicy] (see [applyColourMode]).
 * - A new output surface gets a new decoder. A decoder switched over with `setOutputSurface` keeps the
 *   buffers it was set up with; when those were made for the guide preview's TextureView, some TVs then
 *   draw the full-screen SurfaceView with the GPU instead of the video plane, losing the TV's own
 *   picture processing (a soft, pale picture). The new decoder waits for the next key frame, so the
 *   picture can pause briefly at the switch.
 */
class EngineVideoRenderer(builder: Builder, private val colourPolicy: ColourPolicy) : MediaCodecVideoRenderer(builder) {

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat = super.getMediaFormat(
        format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround,
        tunnelingAudioSessionId,
    ).apply { applyColourMode(colourPolicy.mode) }

    /** True for every decoder: Media3 then releases it and starts a new one when the surface changes. */
    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = true

    @VisibleForTesting
    internal fun newDecoderOnSurfaceChange(name: String): Boolean = codecNeedsSetOutputSurfaceWorkaround(name)
}
