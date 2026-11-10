package io.github.loopholetv.ui.guide

import androidx.media3.ui.SubtitleView
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSubtitlesTest {
    @Test
    fun previewCapsSubtitleSizeAtNormalAndKeepsTheRest() {
        val big = SubtitleStyle(size = SubtitleSize.EXTRA_LARGE, colour = SubtitleColour.YELLOW)
        assertEquals(big.copy(size = SubtitleSize.NORMAL), previewSubtitleStyle(big))
        assertEquals(SubtitleSize.NORMAL, previewSubtitleStyle(big.copy(size = SubtitleSize.LARGE)).size)
        val small = SubtitleStyle(size = SubtitleSize.SMALL)
        assertEquals(small, previewSubtitleStyle(small))
    }

    @Test
    fun previewSubtitlesSitAboveTheChannelLabel() {
        // The label takes roughly the bottom tenth of the 16:9 preview; cues start above it.
        assertTrue(PREVIEW_SUBTITLE_BOTTOM_FRACTION >= 0.15f)
        assertTrue(PREVIEW_SUBTITLE_BOTTOM_FRACTION > SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
    }
}
