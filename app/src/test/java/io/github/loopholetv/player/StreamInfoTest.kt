package io.github.loopholetv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInfoTest {
    private fun StreamInfo.value(section: String, label: String) =
        section(section)!!.rows.first { it.label == label }.value

    private fun video(color: ColorInfo? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs("avc1.640028")
        .setWidth(1920)
        .setHeight(1080)
        .setFrameRate(25f)
        .setAverageBitrate(4_500_000)
        .setColorInfo(color)
        .build()

    private val audio = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setCodecs("mp4a.40.2")
        .setChannelCount(2)
        .setSampleRate(48_000)
        .build()

    @Test fun colourConstantsInWords() {
        assertEquals("BT.709", colorSpaceText(C.COLOR_SPACE_BT709))
        assertEquals("BT.601", colorSpaceText(C.COLOR_SPACE_BT601))
        assertEquals("BT.2020", colorSpaceText(C.COLOR_SPACE_BT2020))
        assertEquals(INFO_NOT_TAGGED, colorSpaceText(Format.NO_VALUE))
        assertEquals("Limited", colorRangeText(C.COLOR_RANGE_LIMITED))
        assertEquals("Full", colorRangeText(C.COLOR_RANGE_FULL))
        assertEquals(INFO_NOT_TAGGED, colorRangeText(Format.NO_VALUE))
        assertEquals("SDR", colorTransferText(C.COLOR_TRANSFER_SDR))
        assertEquals("sRGB", colorTransferText(C.COLOR_TRANSFER_SRGB))
        assertEquals("PQ (HDR10)", colorTransferText(C.COLOR_TRANSFER_ST2084))
        assertEquals("HLG", colorTransferText(C.COLOR_TRANSFER_HLG))
        assertEquals("Gamma 2.2", colorTransferText(C.COLOR_TRANSFER_GAMMA_2_2))
        assertEquals(INFO_NOT_TAGGED, colorTransferText(Format.NO_VALUE))
        assertEquals("Other (99)", colorTransferText(99))
        assertTrue(isHdrTransfer(C.COLOR_TRANSFER_ST2084))
        assertTrue(isHdrTransfer(C.COLOR_TRANSFER_HLG))
        assertFalse(isHdrTransfer(C.COLOR_TRANSFER_SDR))
        assertFalse(isHdrTransfer(Format.NO_VALUE))
    }

    @Test fun bitDepth() {
        assertEquals(INFO_NOT_TAGGED, bitDepthText(null))
        assertEquals("10-bit", bitDepthText(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build()))
        assertEquals("8-bit", bitDepthText(ColorInfo.Builder().setLumaBitdepth(8).build()))
        assertEquals(
            "8-bit luma, 10-bit chroma",
            bitDepthText(ColorInfo.Builder().setLumaBitdepth(8).setChromaBitdepth(10).build()),
        )
    }

    @Test fun fullRangeHdrStream() {
        val color = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setLumaBitdepth(10)
            .setChromaBitdepth(10)
            .build()
        val info = streamInfo(StreamSnapshot(video = video(color)), null)
        assertEquals("BT.2020", info.value("Colour", "Colour space"))
        assertEquals("Full", info.value("Colour", "Range"))
        assertEquals("PQ (HDR10)", info.value("Colour", "Transfer"))
        assertEquals("10-bit", info.value("Colour", "Bit depth"))
        assertEquals("Yes", info.value("Colour", "HDR"))
    }

    @Test fun untaggedColour() {
        val info = streamInfo(StreamSnapshot(video = video(null)), null)
        listOf("Colour space", "Range", "Transfer", "Bit depth").forEach {
            assertEquals(it, INFO_NOT_TAGGED, info.value("Colour", it))
        }
        assertEquals("No", info.value("Colour", "HDR"))
        // A partly tagged stream: only the missing fields read Not tagged.
        val partial = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).build()
        val p = streamInfo(StreamSnapshot(video = video(partial)), null)
        assertEquals("BT.709", p.value("Colour", "Colour space"))
        assertEquals(INFO_NOT_TAGGED, p.value("Colour", "Range"))
    }

    @Test fun videoAudioAndPlayback() {
        val snapshot = StreamSnapshot(
            video = video(),
            audio = audio,
            videoDecoder = "c2.vendor.avc.decoder",
            audioDecoder = "c2.android.aac.decoder",
            droppedFrames = 7,
            bufferedMs = 12_340,
        )
        val info = streamInfo(snapshot, null) { if (it.startsWith("c2.vendor")) true else hardwareByName(it) }
        assertEquals(listOf("Video", "Colour", "Display", "Audio", "Playback"), info.sections.map { it.title })
        assertEquals("1920×1080", info.value("Video", "Resolution"))
        assertEquals("25 fps", info.value("Video", "Frame rate"))
        assertEquals("video/avc · avc1.640028", info.value("Video", "Codec"))
        assertEquals("4.5 Mbps", info.value("Video", "Bitrate"))
        assertEquals("c2.vendor.avc.decoder (hardware)", info.value("Video", "Decoder"))
        assertEquals("audio/mp4a-latm · mp4a.40.2", info.value("Audio", "Codec"))
        assertEquals("Stereo", info.value("Audio", "Channels"))
        assertEquals("48 kHz", info.value("Audio", "Sample rate"))
        assertEquals("c2.android.aac.decoder (software)", info.value("Audio", "Decoder"))
        assertEquals("7", info.value("Playback", "Dropped frames"))
        assertEquals("12.3 s", info.value("Playback", "Buffered"))
    }

    @Test fun audioWithoutADecoderIsPassthrough() {
        val info = streamInfo(StreamSnapshot(audio = audio.buildUpon().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()), null)
        assertEquals(INFO_PASSTHROUGH, info.value("Audio", "Decoder"))
    }

    @Test fun nothingKnownReadsUnknown() {
        val info = streamInfo(null, null)
        listOf("Video", "Audio", "Display", "Playback").forEach { s ->
            info.section(s)!!.rows.forEach { assertEquals("$s ${it.label}", INFO_UNKNOWN, it.value) }
        }
        val noFields = streamInfo(StreamSnapshot(video = Format.Builder().build(), audio = Format.Builder().build()), null)
        assertEquals(INFO_UNKNOWN, noFields.value("Video", "Resolution"))
        assertEquals(INFO_UNKNOWN, noFields.value("Video", "Frame rate"))
        assertEquals(INFO_UNKNOWN, noFields.value("Video", "Codec"))
        assertEquals(INFO_UNKNOWN, noFields.value("Video", "Bitrate"))
        assertEquals(INFO_UNKNOWN, noFields.value("Audio", "Channels"))
        assertEquals(INFO_UNKNOWN, noFields.value("Audio", "Sample rate"))
    }

    @Test fun display() {
        val info = streamInfo(null, DisplaySnapshot(3840, 2160, 59.94f, listOf(2, 3, 1)))
        assertEquals("3840×2160 @ 59.94 Hz", info.value("Display", "Mode"))
        assertEquals("Dolby Vision, HDR10, HLG", info.value("Display", "HDR support"))
        assertEquals("None", hdrTypesText(emptyList()))
        assertEquals("HDR10+, Type 9", hdrTypesText(listOf(9, 4)))
        assertEquals("1920×1080 @ 50 Hz", streamInfo(null, DisplaySnapshot(1920, 1080, 50f, emptyList())).value("Display", "Mode"))
    }

    @Test fun numberFormats() {
        assertEquals("29.97", decimalText(29.97f))
        assertEquals("50", decimalText(50f))
        assertEquals("128 kbps", bitrateText(128_000))
        assertEquals("12 Mbps", bitrateText(12_000_000))
        assertEquals("44.1 kHz", sampleRateText(44_100))
        assertEquals("5.1", channelsText(6))
        assertEquals("3 channels", channelsText(3))
        assertEquals("0 s", secondsText(0))
    }

    @Test fun decoderKindByName() {
        assertEquals(false, hardwareByName("OMX.google.h264.decoder"))
        assertEquals(false, hardwareByName("c2.android.avc.decoder"))
        assertEquals(false, hardwareByName("ffmpegLib"))
        assertNull(hardwareByName("OMX.amlogic.avc.decoder.awesome"))
        assertEquals("x", decoderText("x", null))
    }

    @Test fun decoderNamesFollowTheRenderers() {
        val names = DecoderNames()
        val t = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        names.onVideoDecoderInitialized(t, "v1", 0, 0)
        names.onAudioDecoderInitialized(t, "a1", 0, 0)
        assertEquals("v1", names.video)
        assertEquals("a1", names.audio)
        // A stale release of an earlier decoder keeps the current name.
        names.onVideoDecoderReleased(t, "v0")
        assertEquals("v1", names.video)
        names.onVideoDecoderReleased(t, "v1")
        assertNull(names.video)
        names.onAudioDisabled(t, DecoderCounters())
        assertNull(names.audio)
        names.onVideoDecoderInitialized(t, "v2", 0, 0)
        names.onVideoDisabled(t, DecoderCounters())
        assertNull(names.video)
    }
}
