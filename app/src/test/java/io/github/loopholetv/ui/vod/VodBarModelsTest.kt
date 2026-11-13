package io.github.loopholetv.ui.vod

import io.github.loopholetv.ui.player.durationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class VodBarModelsTest {
    @Test fun knownDuration() {
        val ui = vodBarUi("Film", positionMs = 1_800_000, durationMs = 3_600_000, pendingMs = null, paused = false)
        assertEquals("Film", ui.title)
        assertEquals(0.5f, ui.fraction!!, 0.0001f)
        assertEquals(durationText(Duration.ofMillis(1_800_000)), ui.elapsed)
        assertEquals(durationText(Duration.ofMillis(3_600_000)), ui.total)
        assertNull(ui.pendingFraction)
        assertNull(ui.pendingLabel)
        assertFalse(ui.paused)
    }

    @Test fun pendingSeekShowsTargetAndSignedOffset() {
        val ui = vodBarUi("Film", positionMs = 1_800_000, durationMs = 3_600_000, pendingMs = 360_000, paused = true)
        assertEquals(0.6f, ui.pendingFraction!!, 0.0001f)
        assertEquals("+6:00", ui.pendingLabel)
        assertTrue(ui.paused)
        assertEquals("−0:30", vodBarUi("F", 100_000, 3_600_000, -30_000, false).pendingLabel)
    }

    @Test fun pendingSeekIsClampedToTheFilm() {
        val ui = vodBarUi("Film", positionMs = 3_000_000, durationMs = 3_600_000, pendingMs = 900_000, paused = false)
        assertEquals(1f, ui.pendingFraction!!, 0.0001f)
    }

    @Test fun unknownDurationHasNoTotal() {
        val ui = vodBarUi("Film", positionMs = 90_000, durationMs = null, pendingMs = 30_000, paused = false)
        assertEquals("1:30", ui.elapsed)
        assertNull(ui.total)
        assertNull(ui.fraction)
        assertNull(ui.pendingFraction)
        assertEquals("+0:30", ui.pendingLabel)
    }
}
