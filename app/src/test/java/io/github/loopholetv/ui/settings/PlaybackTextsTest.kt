package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.ColourMode
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.PLAYBACK_LANGUAGES
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.data.settings.audioDelayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTextsTest {
    private val withoutPip = listOf(
        "Buffer size",
        "Video decoder",
        "Picture colour",
        "Audio decoder",
        "Auto frame rate",
        "Surround sound by default",
        "Audio passthrough",
        "Decode DTS to PCM",
        "Audio delay",
        "Audio language",
        "Subtitle language",
        "Subtitle size",
        "Subtitle colour",
        "Subtitle background",
        "Subtitle edge",
    )

    @Test fun playbackRowLabelsInOrder() {
        assertEquals(withoutPip + "Tunneled playback (advanced)", playbackRowLabels(pipSupported = false))
        assertEquals(
            withoutPip + "Home button opens picture-in-picture" + "Tunneled playback (advanced)",
            playbackRowLabels(pipSupported = true),
        )
    }

    @Test fun audioDelayChoicesSpanRange() {
        val texts = audioDelayChoices().map(::audioDelayText)
        assertEquals(41, texts.size)
        assertEquals("−1000 ms", texts.first())
        assertEquals("+1000 ms", texts.last())
        assertTrue("Off" in texts)
        assertEquals("−50 ms", texts[19])
        assertEquals("Off", texts[20])
        assertEquals("+50 ms", texts[21])
    }

    @Test fun languageChoicesStartWithDefault() {
        val audio = audioLanguageChoices()
        assertEquals(null to "Stream default", audio.first())
        assertEquals(PLAYBACK_LANGUAGES.map { it.code to it.name }, audio.drop(1))
        val subs = subtitleLanguageChoices()
        assertEquals(null to "Off", subs.first())
        assertEquals(PLAYBACK_LANGUAGES.map { it.code to it.name }, subs.drop(1))
    }

    @Test fun engineChoiceTexts() {
        assertEquals(
            listOf("Small (fastest channel change)", "Normal", "Large (for unsteady Wi-Fi)"),
            BufferPreset.entries.map(::bufferPresetText),
        )
        assertEquals(listOf("Hardware", "Software"), DecoderChoice.entries.map(::decoderText))
        assertEquals(listOf("Auto", "Standard (ignore stream colour tags)"), ColourMode.entries.map(::colourModeText))
        assertEquals(listOf("Auto", "Off"), Passthrough.entries.map(::passthroughText))
    }

    @Test fun languageTextFallsBackToTheCode() {
        assertEquals("German", languageText(subtitleLanguageChoices(), "de"))
        assertEquals("Off", languageText(subtitleLanguageChoices(), null))
        assertEquals("Stream default", languageText(audioLanguageChoices(), null))
        assertEquals("xx", languageText(audioLanguageChoices(), "xx"))
    }
}
