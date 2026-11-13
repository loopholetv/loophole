package io.github.loopholetv.player

import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale

/** Shown for a value the stream or the device does not report. */
const val INFO_UNKNOWN = "Unknown"

/** Shown for a colour field the stream leaves untagged. */
const val INFO_NOT_TAGGED = "Not tagged"

/** Shown for the audio decoder when audio goes to the TV or receiver undecoded. */
const val INFO_PASSTHROUGH = "Passthrough"

/**
 * What the player is decoding right now, read on the main thread: the decoded formats, the decoder
 * names (null when none ran), dropped video frames and the media buffered ahead (null when unknown).
 */
data class StreamSnapshot(
    val video: Format? = null,
    val audio: Format? = null,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val droppedFrames: Int? = null,
    val bufferedMs: Long? = null,
)

/** The display's current mode (physical pixels, refresh rate) and the HDR types it reports (`Display.HDR_TYPE_*`). */
data class DisplaySnapshot(val width: Int, val height: Int, val refreshHz: Float, val hdrTypes: List<Int>)

/** One labelled line of the stream info card. */
data class InfoRow(val label: String, val value: String)

data class InfoSection(val title: String, val rows: List<InfoRow>)

/** The stream info card's content, section by section. */
data class StreamInfo(val sections: List<InfoSection>) {
    fun section(title: String): InfoSection? = sections.firstOrNull { it.title == title }
}

/**
 * Builds the card from [stream] and [display] (either null when unknown). [hardware] tells whether
 * a decoder name is hardware accelerated, null when it can't tell.
 */
fun streamInfo(
    stream: StreamSnapshot?,
    display: DisplaySnapshot?,
    hardware: (String) -> Boolean? = ::hardwareByName,
): StreamInfo {
    val s = stream ?: StreamSnapshot()
    return StreamInfo(
        listOf(
            videoSection(s, hardware),
            colourSection(s.video),
            displaySection(display),
            audioSection(s, hardware),
            InfoSection(
                "Playback",
                listOf(
                    InfoRow("Dropped frames", s.droppedFrames?.takeIf { it >= 0 }?.toString() ?: INFO_UNKNOWN),
                    InfoRow("Buffered", s.bufferedMs?.takeIf { it >= 0 }?.let(::secondsText) ?: INFO_UNKNOWN),
                ),
            ),
        ),
    )
}

private fun videoSection(s: StreamSnapshot, hardware: (String) -> Boolean?): InfoSection {
    val v = s.video
    return InfoSection(
        "Video",
        listOf(
            InfoRow("Resolution", v?.let { resolutionText(it.width, it.height) } ?: INFO_UNKNOWN),
            InfoRow("Frame rate", v?.frameRate?.takeIf { it > 0f }?.let { "${decimalText(it)} fps" } ?: INFO_UNKNOWN),
            InfoRow("Codec", codecText(v)),
            InfoRow("Bitrate", v?.let(::bitrateOf)?.let(::bitrateText) ?: INFO_UNKNOWN),
            InfoRow("Decoder", s.videoDecoder?.let { decoderText(it, hardware(it)) } ?: INFO_UNKNOWN),
        ),
    )
}

private fun colourSection(video: Format?): InfoSection {
    val c = video?.colorInfo
    return InfoSection(
        "Colour",
        listOf(
            InfoRow("Colour space", colorSpaceText(c?.colorSpace ?: Format.NO_VALUE)),
            InfoRow("Range", colorRangeText(c?.colorRange ?: Format.NO_VALUE)),
            InfoRow("Transfer", colorTransferText(c?.colorTransfer ?: Format.NO_VALUE)),
            InfoRow("Bit depth", bitDepthText(c)),
            InfoRow("HDR", if (isHdrTransfer(c?.colorTransfer ?: Format.NO_VALUE)) "Yes" else "No"),
        ),
    )
}

private fun displaySection(d: DisplaySnapshot?): InfoSection = InfoSection(
    "Display",
    listOf(
        InfoRow(
            "Mode",
            d?.let {
                val size = resolutionText(it.width, it.height)
                val hz = it.refreshHz.takeIf { r -> r > 0f }?.let { r -> "${decimalText(r)} Hz" }
                listOfNotNull(size, hz).joinToString(" @ ").ifEmpty { null }
            } ?: INFO_UNKNOWN,
        ),
        InfoRow("HDR support", d?.let { hdrTypesText(it.hdrTypes) } ?: INFO_UNKNOWN),
    ),
)

private fun audioSection(s: StreamSnapshot, hardware: (String) -> Boolean?): InfoSection {
    val a = s.audio
    val decoder = when {
        s.audioDecoder != null -> decoderText(s.audioDecoder, hardware(s.audioDecoder))
        a != null -> INFO_PASSTHROUGH
        else -> INFO_UNKNOWN
    }
    return InfoSection(
        "Audio",
        listOf(
            InfoRow("Codec", codecText(a)),
            InfoRow("Channels", a?.channelCount?.let(::channelsText) ?: INFO_UNKNOWN),
            InfoRow("Sample rate", a?.sampleRate?.let(::sampleRateText) ?: INFO_UNKNOWN),
            InfoRow("Decoder", decoder),
        ),
    )
}

/** `C.COLOR_SPACE_*` in words. */
fun colorSpaceText(space: Int): String = when (space) {
    C.COLOR_SPACE_BT709 -> "BT.709"
    C.COLOR_SPACE_BT601 -> "BT.601"
    C.COLOR_SPACE_BT2020 -> "BT.2020"
    Format.NO_VALUE -> INFO_NOT_TAGGED
    else -> "Other ($space)"
}

/** `C.COLOR_RANGE_*` in words. */
fun colorRangeText(range: Int): String = when (range) {
    C.COLOR_RANGE_LIMITED -> "Limited"
    C.COLOR_RANGE_FULL -> "Full"
    Format.NO_VALUE -> INFO_NOT_TAGGED
    else -> "Other ($range)"
}

/** `C.COLOR_TRANSFER_*` in words. */
fun colorTransferText(transfer: Int): String = when (transfer) {
    C.COLOR_TRANSFER_SDR -> "SDR"
    C.COLOR_TRANSFER_SRGB -> "sRGB"
    C.COLOR_TRANSFER_ST2084 -> "PQ (HDR10)"
    C.COLOR_TRANSFER_HLG -> "HLG"
    C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma 2.2"
    C.COLOR_TRANSFER_LINEAR -> "Linear"
    Format.NO_VALUE -> INFO_NOT_TAGGED
    else -> "Other ($transfer)"
}

/** Whether [transfer] is an HDR one (PQ or HLG). */
fun isHdrTransfer(transfer: Int): Boolean = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG

/** "10-bit"; luma and chroma both when they differ. */
fun bitDepthText(c: ColorInfo?): String {
    val luma = c?.lumaBitdepth?.takeIf { it > 0 }
    val chroma = c?.chromaBitdepth?.takeIf { it > 0 }
    return when {
        luma != null && chroma != null && luma != chroma -> "$luma-bit luma, $chroma-bit chroma"
        luma != null -> "$luma-bit"
        chroma != null -> "$chroma-bit"
        else -> INFO_NOT_TAGGED
    }
}

/** `Display.HDR_TYPE_*` values in words; "None" for an empty list. */
fun hdrTypesText(types: List<Int>): String =
    types.distinct().sorted().map {
        when (it) {
            HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
            HDR_TYPE_HDR10 -> "HDR10"
            HDR_TYPE_HLG -> "HLG"
            HDR_TYPE_HDR10_PLUS -> "HDR10+"
            else -> "Type $it"
        }
    }.joinToString(", ").ifEmpty { "None" }

// android.view.Display.HdrCapabilities values, kept here so the mapping is testable off-device.
private const val HDR_TYPE_DOLBY_VISION = 1
private const val HDR_TYPE_HDR10 = 2
private const val HDR_TYPE_HLG = 3
private const val HDR_TYPE_HDR10_PLUS = 4

/** "1920×1080"; null when either side is unknown. */
fun resolutionText(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "$width×$height" else null

/** The sample MIME type and, when given, the codecs string: "video/avc · avc1.640028". */
fun codecText(format: Format?): String {
    val mime = format?.sampleMimeType
    val codecs = format?.codecs?.takeIf { it.isNotBlank() }
    return listOfNotNull(mime, codecs).joinToString(" · ").ifEmpty { INFO_UNKNOWN }
}

private fun bitrateOf(f: Format): Int? =
    listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }

/** "4.5 Mbps", "128 kbps". */
fun bitrateText(bitsPerSecond: Int): String =
    if (bitsPerSecond >= 1_000_000) "${decimalText(bitsPerSecond / 1_000_000f, 1)} Mbps"
    else "${(bitsPerSecond + 500) / 1_000} kbps"

/** "Stereo", "5.1"; the count otherwise. */
fun channelsText(channels: Int): String? = when {
    channels <= 0 -> null
    channels == 1 -> "Mono"
    channels == 2 -> "Stereo"
    channels == 6 -> "5.1"
    channels == 8 -> "7.1"
    else -> "$channels channels"
}

/** "48 kHz", "44.1 kHz". */
fun sampleRateText(hz: Int): String? = if (hz > 0) "${decimalText(hz / 1_000f, 1)} kHz" else null

/** "12.3 s". */
fun secondsText(ms: Long): String = "${decimalText(ms / 1_000f, 1)} s"

/** The decoder name with "(hardware)" or "(software)" when known. */
fun decoderText(name: String, hardware: Boolean?): String = when (hardware) {
    true -> "$name (hardware)"
    false -> "$name (software)"
    null -> name
}

/**
 * Whether a decoder is hardware accelerated, judged by its name alone: Android's and Google's
 * software codecs and the bundled extension decoders are software; null for any other name.
 */
fun hardwareByName(name: String): Boolean? {
    val n = name.lowercase(Locale.ROOT)
    return if (SOFTWARE_PREFIXES.any { n.startsWith(it) }) false else null
}

private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "ffmpeg", "lib")

/** [value] with up to [maxDecimals] decimals, trailing zeros dropped: 25, 29.97, 59.94. */
internal fun decimalText(value: Float, maxDecimals: Int = 3): String {
    val s = String.format(Locale.ROOT, "%.${maxDecimals}f", value)
    return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
}

/**
 * Remembers the names of the video and audio decoders in use (null when none runs: audio passed
 * through undecoded, or nothing playing). Add it to the player with `addAnalyticsListener`.
 */
@OptIn(UnstableApi::class)
class DecoderNames : AnalyticsListener {
    var video: String? = null
        private set
    var audio: String? = null
        private set

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        video = decoderName
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (video == decoderName) video = null
    }

    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        video = null
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audio = decoderName
    }

    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (audio == decoderName) audio = null
    }

    override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        audio = null
    }
}

/**
 * Whether a decoder is hardware accelerated: the platform's answer on Android 10+, else (or for a
 * name the platform does not list, such as an extension decoder) [hardwareByName]. Answers are
 * cached; the first lookup reads the codec list, so call it off the main thread.
 */
class DecoderKinds {
    private val cache = HashMap<String, Boolean?>()
    private val platform: Map<String, Boolean> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .filterNot { it.isEncoder }
                    .associate { it.name to it.isHardwareAccelerated }
            } catch (_: RuntimeException) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    @Synchronized
    fun isHardware(name: String): Boolean? = cache.getOrPut(name) { platform[name] ?: hardwareByName(name) }
}
