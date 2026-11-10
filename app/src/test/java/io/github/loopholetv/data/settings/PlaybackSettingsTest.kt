package io.github.loopholetv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSettingsTest {
    @Test fun audioDelayNormalised() {
        assertEquals(50, normaliseAudioDelay(37))
        assertEquals(0, normaliseAudioDelay(24))
        assertEquals(-1000, normaliseAudioDelay(-1210))
        assertEquals(1000, normaliseAudioDelay(5000))
        assertEquals(-100, normaliseAudioDelay(-75))
        assertEquals(100, normaliseAudioDelay(75))
        assertEquals(0, normaliseAudioDelay(0))
        assertEquals(-1000, normaliseAudioDelay(Int.MIN_VALUE))
        assertEquals(1000, normaliseAudioDelay(Int.MAX_VALUE))
        assertEquals(-50, normaliseAudioDelay(-25))
    }

    @Test fun audioDelayTextFormats() {
        assertEquals("Off", audioDelayText(0))
        assertEquals("+150 ms", audioDelayText(150))
        assertEquals("−50 ms", audioDelayText(-50))
        assertEquals("+1000 ms", audioDelayText(1000))
    }

    @Test fun audioDelayRangeAndStep() {
        assertEquals(50, AUDIO_DELAY_STEP_MS)
        assertEquals(-1000..1000, AUDIO_DELAY_RANGE_MS)
    }

    @Test fun bufferPresetValues() {
        val mib = 1024 * 1024
        val n = BufferPreset.NORMAL
        assertEquals(listOf(12_000, 30_000, 1_500, 3_000, 32 * mib), listOf(n.minMs, n.maxMs, n.startMs, n.afterRebufferMs, n.targetBytes))
        val s = BufferPreset.SMALL
        assertEquals(listOf(4_000, 12_000, 800, 2_000, 16 * mib), listOf(s.minMs, s.maxMs, s.startMs, s.afterRebufferMs, s.targetBytes))
        val l = BufferPreset.LARGE
        assertEquals(listOf(30_000, 60_000, 2_500, 6_000, 64 * mib), listOf(l.minMs, l.maxMs, l.startMs, l.afterRebufferMs, l.targetBytes))
    }

    @Test fun languagesStartWithEnglishAndHaveUniqueCodes() {
        assertEquals(20, PLAYBACK_LANGUAGES.size)
        assertEquals(Language("en", "English"), PLAYBACK_LANGUAGES.first())
        assertEquals(20, PLAYBACK_LANGUAGES.map { it.code }.toSet().size)
        assertEquals(
            listOf("en", "es", "fr", "de", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "el", "tr", "ru", "ar", "hi", "zh", "ja", "ko"),
            PLAYBACK_LANGUAGES.map { it.code },
        )
        assertEquals("Korean", PLAYBACK_LANGUAGES.last().name)
    }

    @Test fun defaults() {
        val p = PlaybackSettings()
        assertEquals(BufferPreset.NORMAL, p.bufferPreset)
        assertEquals(DecoderChoice.HARDWARE, p.videoDecoder)
        assertEquals(DecoderChoice.HARDWARE, p.audioDecoder)
        assertEquals(false, p.autoFrameRate)
        assertEquals(true, p.preferSurround)
        assertEquals(Passthrough.AUTO, p.passthrough)
        assertEquals(false, p.decodeDtsToPcm)
        assertEquals(false, p.tunneling)
        assertEquals(null, p.audioLanguage)
        assertEquals(null, p.subtitleLanguage)
        assertEquals(0, p.audioDelayMs)
        assertEquals(SubtitleStyle(SubtitleSize.NORMAL, SubtitleColour.WHITE, SubtitleBackground.NONE, SubtitleEdge.OUTLINE), p.subtitleStyle)
        assertEquals(false, p.pipOnHome)
        assertEquals(PlaybackSettings(), AppSettings().playback)
    }

    @Test fun subtitleAndAspectValues() {
        assertEquals(listOf("Fit", "Stretch", "Zoom", "16:9", "4:3"), AspectMode.entries.map { it.label })
        assertEquals(listOf(0.8f, 1f, 1.25f, 1.5f), SubtitleSize.entries.map { it.scale })
        assertEquals(listOf("Small", "Normal", "Large", "Extra large"), SubtitleSize.entries.map { it.label })
        assertEquals(0xFFFFFFFF.toInt(), SubtitleColour.WHITE.argb)
        assertEquals(0xFFFFE14D.toInt(), SubtitleColour.YELLOW.argb)
        assertEquals(listOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()), SubtitleBackground.entries.map { it.argb })
        assertEquals(listOf("None", "See-through box", "Black box"), SubtitleBackground.entries.map { it.label })
        assertEquals(listOf("Outline", "Shadow", "None"), SubtitleEdge.entries.map { it.label })
    }
}
