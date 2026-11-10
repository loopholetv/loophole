package io.github.loopholetv.player.catchup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ArchiveTimelineTest {
    private val from = Instant.parse("2026-01-10T18:00:00Z")
    private val now = Instant.parse("2026-01-10T21:00:00Z")
    private val earliest = Instant.parse("2026-01-08T00:00:00Z")
    private val lag = Duration.ofSeconds(120)

    private fun loaded(seekable: Boolean = true, durationMs: Long? = 3_600_000, hls: Boolean = true) = PlayMode.Archive(
        channelId = 1, request = ArchiveRequest(from, from.plusSeconds(3600)),
        seekable = seekable, durationMs = durationMs, hls = hls,
    )

    private fun plan(
        target: Instant,
        current: Instant = from.plusSeconds(100),
        mode: PlayMode.Archive = loaded(),
        minute: Boolean = false,
    ) = planSeek(current, target, mode, now, earliest, lag, minute)

    @Test fun planSeekCases() {
        assertEquals(SeekPlan.Within(600_000), plan(from.plusSeconds(600)))
        assertEquals(SeekPlan.Reopen(earliest), plan(earliest.minusSeconds(9_999)))
        assertEquals(SeekPlan.GoLive, plan(now.minusSeconds(30)))
        assertEquals(SeekPlan.Ignore, plan(from.plusSeconds(103)))
        assertEquals(SeekPlan.Reopen(from.plusSeconds(600)), plan(from.plusSeconds(600), mode = loaded(seekable = false)))
        assertEquals(SeekPlan.Reopen(from.plusSeconds(600)), plan(from.plusSeconds(605), mode = loaded(seekable = false), minute = true))
        assertEquals(SeekPlan.Reopen(from.plusSeconds(1200)), plan(from.plusSeconds(1200), mode = loaded(hls = false)))
        assertEquals(SeekPlan.Within(400_000), plan(from.plusSeconds(400), mode = loaded(hls = false)))
        assertEquals(SeekPlan.Reopen(from.minusSeconds(60)), plan(from.minusSeconds(60)))
        assertEquals(SeekPlan.Reopen(from.plusSeconds(3600)), plan(from.plusSeconds(3600)))
    }

    @Test fun minuteGranularClampsUpToWholeMinute() {
        val odd = Instant.parse("2026-01-08T00:00:20Z")
        val c = PlayMode.Archive(1, ArchiveRequest(from, from.plusSeconds(3600)), seekable = false)
        val p = planSeek(from.plusSeconds(100), odd.minusSeconds(500), c, now, odd, lag, true)
        assertEquals(SeekPlan.Reopen(Instant.parse("2026-01-08T00:01:00Z")), p)
        val whole = planSeek(from.plusSeconds(100), earliest.minusSeconds(500), c, now, earliest, lag, true)
        assertEquals(SeekPlan.Reopen(earliest), whole)
    }

    @Test fun nearLiveBoundary() {
        assertTrue(isNearLive(now.minus(lag), now, lag))
        assertTrue(isNearLive(now, now, lag))
        assertFalse(isNearLive(now.minus(lag).minusMillis(1), now, lag))
    }

    @Test fun rewindFromLiveGoesBackAtLeastLag() {
        assertEquals(now.minusSeconds(120), rewindFromLive(now, Duration.ofSeconds(10), lag, earliest, false))
        assertEquals(now.minusSeconds(300), rewindFromLive(now, Duration.ofSeconds(300), lag, earliest, false))
        val odd = Instant.parse("2026-01-10T21:00:45Z")
        assertEquals(Instant.parse("2026-01-10T20:58:00Z"), rewindFromLive(odd, Duration.ofSeconds(10), lag, earliest, true))
        assertEquals(earliest, rewindFromLive(now, Duration.ofDays(30), lag, earliest, false))
    }

    @Test fun positionOfAddsOffset() {
        assertEquals(from.plusMillis(1500), positionOf(loaded(), 1500))
    }

    @Test fun fallbackSpanIsClockHour() {
        val zone = ZoneId.of("Europe/Berlin")
        val t = ZonedDateTime.of(2026, 1, 10, 20, 37, 0, 0, zone).toInstant()
        val span = fallbackSpan(t, zone)
        assertEquals(null, span.title)
        assertEquals(ZonedDateTime.of(2026, 1, 10, 20, 0, 0, 0, zone).toInstant(), span.start)
        assertEquals(ZonedDateTime.of(2026, 1, 10, 21, 0, 0, 0, zone).toInstant(), span.stop)
    }
}
