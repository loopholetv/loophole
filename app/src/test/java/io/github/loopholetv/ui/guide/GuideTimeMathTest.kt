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

    // --- Past navigation on channels with an archive ---

    @Test fun shiftWindowClampsToRowEarliest() {
        val now = at(11, 47)
        val w = initialWindow(now, zone)
        val earliest = now.minus(Duration.ofDays(2)) // 11:47 two days ago
        val limit = floorToHalfHour(earliest, zone) // 11:30 two days ago
        // Back one step from now goes into the past.
        assertEquals(TimeWindow(at(11, 0), at(13, 0)), shiftWindow(w, -1, now, zone, at(20, 0), earliest))
        // Never further back than the half hour holding the archive limit.
        val oldest = shiftWindow(w, -10_000, now, zone, at(20, 0), earliest)
        assertEquals(TimeWindow(limit, limit + WINDOW_LENGTH), oldest)
        assertEquals(oldest, shiftWindow(oldest, -1, now, zone, at(20, 0), earliest))
        // And forward again, all the way back to the now window.
        assertEquals(w, shiftWindow(oldest, 96, now, zone, at(20, 0), earliest))
        // Without an archive: the now window is the limit (unchanged).
        assertEquals(w, shiftWindow(w, -1, now, zone, at(20, 0), earliest = null))
    }

    @Test fun shiftWindowFromThePastOnARowWithoutArchiveNeverJumps() {
        val now = at(11, 47)
        val past = TimeWindow(at(8, 0), at(10, 0))
        // Left there is refused (the strip then goes to the groups), not a jump to now.
        assertEquals(past, shiftWindow(past, -1, now, zone, at(20, 0), earliest = null))
        // Right moves forward by one step.
        assertEquals(TimeWindow(at(8, 30), at(10, 30)), shiftWindow(past, 1, now, zone, at(20, 0), earliest = null))
    }

    @Test fun rowEarliestComesFromTheChannelsArchive() {
        val now = at(11, 47)
        assertEquals(null, rowEarliest(channel(catchupType = null, days = null), now))
        assertEquals(now.minus(Duration.ofDays(3)), rowEarliest(channel(catchupType = "shift", days = 3), now))
    }

    @Test fun playableRangeMatchesTheArchive() {
        val now = at(11, 47)
        val spec = io.github.loopholetv.data.catchup.catchupSpec("shift", 1, null, "http://example.test/live/1")!!
        val range = playableRange(spec, now)!!
        val starts = listOf(
            now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)).minusSeconds(1),
            at(11, 0), now.minus(spec.liveLag), now.minus(spec.liveLag).minusSeconds(1), now,
        )
        for (s in starts) assertEquals("start $s", spec.canPlay(s, now), s in range)
        assertEquals(null, playableRange(null, now))
    }

    private fun channel(catchupType: String?, days: Int?) = io.github.loopholetv.data.db.ChannelEntity(
        id = 1, playlistId = 1, key = "k", number = 1, name = "Ch", streamUrl = "http://example.test/live/1",
        logo = null, groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = catchupType, catchupDays = days, catchupSource = null,
        epgChannelId = "e",
    )
}
