package io.github.loopholetv.ui.player

import io.github.loopholetv.player.catchup.CatchupUi
import io.github.loopholetv.player.catchup.ProgrammeSpan
import io.github.loopholetv.ui.guide.GuideFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupBarModelsTest {
    private val format = GuideFormat(is24Hour = true, zone = ZoneOffset.UTC)
    private val start = Instant.parse("2026-01-01T20:00:00Z")
    private val stop = start.plusSeconds(3600)

    private fun ui(
        title: String? = "News",
        position: Instant = start,
        now: Instant = stop.plusSeconds(600),
        pending: Instant? = null,
        live: Boolean = false,
    ) = CatchupUi(1, ProgrammeSpan(title, start, stop), position, now, pending, paused = false, live = live, failed = false)

    @Test fun durationTextFormats() {
        assertEquals("0:00", durationText(Duration.ZERO))
        assertEquals("12:34", durationText(Duration.ofSeconds(754)))
        assertEquals("1:02:03", durationText(Duration.ofSeconds(3723)))
        assertEquals("0:00", durationText(Duration.ofSeconds(-5)))
    }

    @Test fun barUiFromCatchup() {
        val bar = catchupBarUi(ui(position = start.plusSeconds(754)), format)
        assertEquals("News", bar.title)
        assertEquals("20:00–21:00", bar.times)
        assertEquals("12:34", bar.elapsed)
        assertEquals("1:00:00", bar.total)
        assertEquals(0.2094f, bar.fraction, 0.001f)
        assertTrue(bar.behindLive)
    }

    @Test fun fractionIsClamped() {
        assertEquals(0f, catchupBarUi(ui(position = start.minusSeconds(60)), format).fraction, 0f)
        assertEquals(1f, catchupBarUi(ui(position = stop.plusSeconds(60)), format).fraction, 0f)
    }

    @Test fun liveFractionOnlyWhileAiring() {
        assertNull(catchupBarUi(ui(now = stop), format).liveFraction)
        assertEquals(0.5f, catchupBarUi(ui(now = start.plusSeconds(1800)), format).liveFraction!!, 0.001f)
    }

    @Test fun pendingLabelSigned() {
        val pos = start.plusSeconds(600)
        val back = catchupBarUi(ui(position = pos, pending = pos.minusSeconds(30)), format)
        assertEquals("−0:30", back.pendingLabel)
        val fwd = catchupBarUi(ui(position = pos, pending = pos.plusSeconds(300)), format)
        assertEquals("+5:00", fwd.pendingLabel)
        assertEquals(0.25f, fwd.pendingFraction!!, 0.001f)
        assertNull(catchupBarUi(ui(position = pos), format).pendingLabel)
    }

    @Test fun noInformationTitle() {
        assertEquals("No information", catchupBarUi(ui(title = null), format).title)
    }

    @Test fun behindLiveFollowsPositionAndPausedLive() {
        val now = start.plusSeconds(1800)
        assertFalse(catchupBarUi(ui(position = now.minusSeconds(2), now = now), format).behindLive)
        assertTrue(catchupBarUi(ui(position = now.minusSeconds(60), now = now), format).behindLive)
        assertTrue(catchupBarUi(ui(position = now, now = now, live = true), format).behindLive)
    }
}
