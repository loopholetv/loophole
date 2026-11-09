package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ProgrammeEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class GuideTimeMathTest {
    private val zone = ZoneId.of("Australia/Sydney")

    private fun at(h: Int, m: Int): Instant = ZonedDateTime.of(2026, 11, 8, h, m, 0, 0, zone).toInstant()

    private fun programme(start: Instant, stop: Instant) = ProgrammeEntity(
        epgChannelId = "a", start = start, stop = stop, title = "t",
        subTitle = null, description = null, category = null, generation = 1,
    )

    @Test fun initialWindowFloorsToHalfHour() {
        val w = initialWindow(at(11, 47), zone)
        assertEquals(TimeWindow(at(11, 30), at(13, 30)), w)
    }

    @Test fun initialWindowFloorsInLocalTimeForHalfHourZones() {
        val adelaide = ZoneId.of("Australia/Adelaide") // UTC+10:30 in November
        val now = ZonedDateTime.of(2026, 11, 8, 9, 10, 0, 0, adelaide).toInstant()
        val w = initialWindow(now, adelaide)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 9, 0, 0, 0, adelaide).toInstant(), w.start)
    }

    @Test fun windowShiftNeverGoesBeforeTheNowWindow() {
        val now = at(11, 47)
        val w = initialWindow(now, zone)
        // No past navigation: at "now", shifting back is refused (the window stays put).
        assertEquals(w, shiftWindow(w, -1, now, zone, latestEnd = at(20, 0)))
        assertEquals(w, shiftWindow(w, -10_000, now, zone, latestEnd = at(20, 0)))
        // From a forward-shifted window, back moves toward now and stops there.
        val ahead = shiftWindow(w, 2, now, zone, latestEnd = at(20, 0))
        assertEquals(TimeWindow(at(12, 0), at(14, 0)), shiftWindow(ahead, -1, now, zone, latestEnd = at(20, 0)))
        assertEquals(w, shiftWindow(ahead, -5, now, zone, latestEnd = at(20, 0)))
    }

    @Test fun windowShiftWithUnalignedLatestEndStaysOnHalfHours() {
        val now = at(11, 47)
        val w = initialWindow(now, zone)
        // Guide ends 20:10: ceil to 20:30, so the last window is 18:30–20:30 (marks stay on :00/:30).
        assertEquals(TimeWindow(at(18, 30), at(20, 30)), shiftWindow(w, 100, now, zone, latestEnd = at(20, 10)))
        // Shifting back from the clamped window lands exactly on the "now" window again.
        val last = shiftWindow(w, 100, now, zone, latestEnd = at(20, 10))
        assertEquals(w, shiftWindow(last, -14, now, zone, latestEnd = at(20, 10)))
    }

    @Test fun initialWindowOnSydneyDstStartDay() {
        // 2026-10-04: 02:00 AEST jumps to 03:00 AEDT.
        val now = ZonedDateTime.of(2026, 10, 4, 3, 10, 0, 0, zone).toInstant()
        val w = initialWindow(now, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 4, 3, 0, 0, 0, zone).toInstant(), w.start)
        assertEquals(Duration.ofHours(2), Duration.between(w.start, w.end))
        // A programme 01:30 AEST – 03:30 AEDT lasts one real hour: half of a window starting 01:30.
        val start = ZonedDateTime.of(2026, 10, 4, 1, 30, 0, 0, zone).toInstant()
        val p = programme(start, ZonedDateTime.of(2026, 10, 4, 3, 30, 0, 0, zone).toInstant())
        assertEquals(0.5f, blockWidthFraction(p, TimeWindow(start, start.plus(Duration.ofHours(2)))), 1e-6f)
    }

    @Test fun initialWindowOnSydneyDstEndDay() {
        // 2026-04-05: 03:00 AEDT falls back to 02:00 AEST, so 02:40 happens twice.
        val first = ZonedDateTime.ofStrict(java.time.LocalDateTime.of(2026, 4, 5, 2, 40), java.time.ZoneOffset.ofHours(11), zone)
        val second = ZonedDateTime.ofStrict(java.time.LocalDateTime.of(2026, 4, 5, 2, 40), java.time.ZoneOffset.ofHours(10), zone)
        assertEquals(first.withMinute(30).toInstant(), initialWindow(first.toInstant(), zone).start)
        assertEquals(second.withMinute(30).toInstant(), initialWindow(second.toInstant(), zone).start)
        assertEquals(Duration.ofHours(1), Duration.between(initialWindow(first.toInstant(), zone).start, initialWindow(second.toInstant(), zone).start))
        // A programme 02:30 AEDT – 02:30 AEST lasts one real hour.
        val p = programme(first.withMinute(30).toInstant(), second.withMinute(30).toInstant())
        val w = initialWindow(first.toInstant(), zone)
        assertEquals(0.5f, blockWidthFraction(p, w), 1e-6f)
    }

    @Test fun initialWindowInQuarterHourOffsetZone() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        val now = ZonedDateTime.of(2026, 11, 8, 11, 20, 0, 0, kathmandu).toInstant()
        val w = initialWindow(now, kathmandu)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 11, 0, 0, 0, kathmandu).toInstant(), w.start)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 13, 0, 0, 0, kathmandu).toInstant(), w.end)
    }

    @Test fun windowShiftMovesByHalfHourAndClampsToLatestProgrammeEnd() {
        val now = at(11, 47)
        val w = initialWindow(now, zone)
        assertEquals(TimeWindow(at(12, 0), at(14, 0)), shiftWindow(w, 1, now, zone, latestEnd = at(20, 0)))
        assertEquals(TimeWindow(at(18, 0), at(20, 0)), shiftWindow(w, 100, now, zone, latestEnd = at(20, 0)))
        // Without guide data the window cannot move past the "now" window.
        assertEquals(w, shiftWindow(w, 3, now, zone, latestEnd = null))
    }

    @Test fun blockWidthClipsToWindow() {
        val w = TimeWindow(at(11, 30), at(13, 30))
        assertEquals(0.25f, blockWidthFraction(programme(at(10, 0), at(12, 0)), w), 1e-6f)
        assertEquals(1f, blockWidthFraction(programme(at(9, 0), at(15, 0)), w), 1e-6f)
        assertEquals(0f, blockWidthFraction(programme(at(14, 0), at(15, 0)), w), 1e-6f)
    }

    @Test fun blockStartFractionClipsToWindow() {
        val w = TimeWindow(at(11, 30), at(13, 30))
        assertEquals(0f, blockStartFraction(programme(at(10, 0), at(12, 0)), w), 1e-6f)
        assertEquals(0.5f, blockStartFraction(programme(at(12, 30), at(13, 0)), w), 1e-6f)
    }
}
