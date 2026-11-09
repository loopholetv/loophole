package io.github.loopholetv.player

import androidx.media3.common.Format
import kotlin.math.roundToInt

/** Short badges for the stream currently playing, e.g. FHD / 50 FPS / STEREO; null when unknown. */
data class StreamTags(val resolution: String?, val fps: String?, val audio: String?) {
    companion object {
        val NONE = StreamTags(null, null, null)
    }
}

fun streamTags(video: Format?, audio: Format?): StreamTags = StreamTags(
    resolution = video?.height?.let(::resolutionTag),
    fps = video?.frameRate?.takeIf { it > 0f }?.let { "${it.roundToInt()} FPS" },
    audio = audio?.channelCount?.let(::audioTag),
)

private fun resolutionTag(height: Int): String? = when {
    height <= 0 -> null
    height >= 2160 -> "4K"
    height >= 1080 -> "FHD"
    height >= 720 -> "HD"
    else -> "SD"
}

private fun audioTag(channels: Int): String? = when (channels) {
    1 -> "MONO"
    2 -> "STEREO"
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}
