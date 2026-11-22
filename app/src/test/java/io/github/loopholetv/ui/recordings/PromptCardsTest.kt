package io.github.loopholetv.ui.recordings

import io.github.loopholetv.record.RecordPrompt
import io.github.loopholetv.record.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class PromptCardsTest {
    private val warning = RecordPrompt.Warning(1, "Channel One", "News", Use.PLAYER, 30)

    @Test fun warningCopy() {
        assertEquals("Recording starts in 30 s", warningHeadline(30))
        assertEquals("Recording starts in 7 s", warningHeadline(7))
        assertEquals("Channel One · News", warningDetail(warning))
    }

    @Test fun warningSaysWhatSwitchingStops() {
        assertEquals("This will stop what you're watching.", warningStops(Use.PLAYER))
        assertEquals("This will stop casting.", warningStops(Use.CAST))
        assertNull(warningStops(Use.PREVIEW))
    }

    @Test fun holdsStreamQuestion() {
        assertEquals(
            "Recording News until 21:30. Stop the recording and watch?",
            holdsStreamText("News", Instant.parse("2026-11-22T21:30:00Z"), ZoneOffset.UTC),
        )
    }
}
