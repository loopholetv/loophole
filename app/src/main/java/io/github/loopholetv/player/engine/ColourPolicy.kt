package io.github.loopholetv.player.engine

import android.media.MediaFormat
import io.github.loopholetv.data.settings.ColourMode

/**
 * How the stream's colour tags reach the video decoder. Written by the UI thread before a stream starts and
 * read on the playback thread when the decoder is configured, which happens at every stream start.
 */
class ColourPolicy {
    @Volatile
    var mode: ColourMode = ColourMode.AUTO
}

/**
 * [ColourMode.AUTO] keeps the tags Media3 copied from the stream. [ColourMode.STANDARD] replaces them with
 * SDR, BT.709, limited range, and drops HDR metadata, so a mis-tagged stream is shown as ordinary TV.
 */
fun MediaFormat.applyColourMode(mode: ColourMode) {
    if (mode != ColourMode.STANDARD) return
    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
}
