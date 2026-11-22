package io.github.loopholetv.record

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.db.SeriesRuleEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordRulesTest {
    private val t0 = Instant.parse("2026-11-22T20:00:00Z")
    private fun min(n: Long) = t0.plusSeconds(n * 60)

    private fun rec(
        id: Long = 1,
        title: String = "News",
        subTitle: String? = null,
        episodeKey: String? = null,
        start: Instant = t0,
        stop: Instant = min(30),
        state: RecordingState = RecordingState.SCHEDULED,
        padBefore: Int = 1,
        padAfter: Int = 5,
        bytes: Long = 1,
    ) = RecordingEntity(
        id = id, playlistId = 1, channelKey = "k", channelName = "Ch", epgChannelId = "e", title = title,
        subTitle = subTitle, episodeKey = episodeKey, description = null, start = start, stop = stop,
        padBeforeMin = padBefore, padAfterMin = padAfter, state = state, createdAt = t0, bytes = bytes,
    )

    private fun prog(title: String = "News", subTitle: String? = null, episodeKey: String? = null, start: Instant = t0) =
        ProgrammeEntity(
            epgChannelId = "e", start = start, stop = start.plusSeconds(1800), title = title, subTitle = subTitle,
            description = null, category = null, generation = 1, episodeKey = episodeKey,
        )

    private fun rule(title: String) = SeriesRuleEntity(
        kind = SeriesKind.RECORD, playlistId = 1, channelKey = "k", channelName = "Ch", epgChannelId = "e",
        title = title, padBeforeMin = 1, padAfterMin = 5, createdAt = t0,
    )

    @Test fun windowAddsPadding() {
        val w = rec(padBefore = 1, padAfter = 5).window()
        assertEquals(t0.minusSeconds(60), w.from)
        assertEquals(min(35), w.until)
    }

    @Test fun overlapsIsHalfOpen() {
        val a = Window(min(0), min(10))
        assertTrue(overlaps(a, Window(min(9), min(20))))
        assertFalse(overlaps(a, Window(min(10), min(20))))
        assertFalse(overlaps(a, Window(min(-5), min(0))))
    }

    @Test fun seriesMatchesIgnoresCaseAndSpaces() {
        assertTrue(seriesMatches(rule("  The News "), prog("the NEWS")))
        assertFalse(seriesMatches(rule("The News"), prog("The News at Ten")))
    }

    @Test fun repeatBySubtitleWhenNoEpisodeKey() {
        val existing = listOf(rec(subTitle = "The Pilot", state = RecordingState.DONE))
        assertTrue(isRepeat(prog(subTitle = " the pilot "), existing))
        assertFalse(isRepeat(prog(subTitle = "Second"), existing))
        assertFalse(isRepeat(prog(subTitle = "The Pilot"), listOf(rec(subTitle = "The Pilot", state = RecordingState.FAILED))))
        assertTrue(isRepeat(prog(episodeKey = "S01E02"), listOf(rec(episodeKey = "S01E02", state = RecordingState.RECORDING))))
        assertTrue(isRepeat(prog(episodeKey = "S01E02"), listOf(rec(episodeKey = "S01E02", state = RecordingState.SCHEDULED))))
    }

    @Test fun noIdentityNeverRepeat() {
        val existing = listOf(rec(state = RecordingState.DONE))
        assertNull(episodeIdentity(null, "  "))
        assertFalse(isRepeat(prog(), existing))
    }

    @Test fun followMoveWithin30Min() {
        val booked = rec()
        val near = prog(start = min(20))
        val nearer = prog(start = min(-10))
        assertEquals(nearer, followMove(booked, listOf(near, nearer)))
        assertEquals(near, followMove(booked, listOf(near)))
        assertNull(followMove(booked, listOf(prog(start = min(31)))))
    }

    @Test fun followMoveIgnoresOtherGuideChannel() {
        val other = prog(start = min(5)).copy(epgChannelId = "other")
        assertNull(followMove(rec(), listOf(other)))
        assertEquals(other, followMove(rec().copy(epgChannelId = null), listOf(other)))
    }

    @Test fun followMoveIgnoresOtherTitle() {
        assertNull(followMove(rec(), listOf(prog(title = "Weather", start = min(5)))))
    }

    @Test fun startTimingOnTimeLateMissed() {
        val r = rec(padBefore = 1) // window starts at 19:59
        assertEquals(StartTiming.ON_TIME, startTiming(r, t0.minusSeconds(60)))
        assertEquals(StartTiming.ON_TIME, startTiming(r, t0.minusSeconds(1)))
        assertEquals(StartTiming.LATE, startTiming(r, t0))
        assertEquals(StartTiming.LATE, startTiming(r, min(29)))
        assertEquals(StartTiming.MISSED, startTiming(r, min(30)))
    }

    @Test fun estimatedBytesUsesRateOrDefault() {
        val r = rec(start = t0, stop = min(60), padBefore = 0, padAfter = 0)
        assertEquals(1_000L, estimatedBytes(r, 1_000L))
        assertEquals(UNKNOWN_BYTES_PER_HOUR, estimatedBytes(r, null))
        assertEquals(500L, estimatedBytes(rec(start = t0, stop = min(30), padBefore = 0, padAfter = 0), 1_000L))
    }

    @Test fun estimatedBytesClampsTheWindow() {
        val huge = rec(start = t0, stop = t0.plusSeconds(100L * 365 * 86_400), padBefore = 0, padAfter = 0)
        assertEquals(24L * 1_000L, estimatedBytes(huge, 1_000L))
        assertEquals(Long.MAX_VALUE, estimatedBytes(huge, Long.MAX_VALUE / 2))
        assertEquals(0L, estimatedBytes(rec(start = min(30), stop = t0, padBefore = 0, padAfter = 0), 1_000L))
    }

    @Test fun keepLastNeverDeletesRecordingOrScheduled() {
        val eps = listOf(
            rec(id = 1, start = min(0), state = RecordingState.DONE),
            rec(id = 2, start = min(100), state = RecordingState.STOPPED),
            rec(id = 3, start = min(200), state = RecordingState.DONE),
            rec(id = 4, start = min(300), state = RecordingState.RECORDING),
            rec(id = 5, start = min(400), state = RecordingState.SCHEDULED),
            rec(id = 6, start = min(500), state = RecordingState.FAILED),
        )
        assertEquals(listOf(1L, 2L), keepLastDeletions(eps, 1).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), keepLastDeletions(eps, 0).map { it.id })
        assertEquals(emptyList<Long>(), keepLastDeletions(eps, 3).map { it.id })
        assertEquals(emptyList<Long>(), keepLastDeletions(eps, null).map { it.id })
    }

    @Test fun keepLastIgnoresEmptyFiles() {
        val eps = listOf(
            rec(id = 1, start = min(0), state = RecordingState.DONE),
            rec(id = 2, start = min(100), state = RecordingState.STOPPED, bytes = 0),
            rec(id = 3, start = min(200), state = RecordingState.DONE),
        )
        assertEquals(listOf(1L), keepLastDeletions(eps, 1).map { it.id })
        assertEquals(emptyList<Long>(), keepLastDeletions(eps, 2).map { it.id })
    }

    private fun playlist(connectionsAllowed: Int?, account: Int?) = PlaylistEntity(
        id = 1, name = "P", type = PlaylistType.XTREAM, url = null, xtreamServer = "http://s.test", xtreamUser = "u",
        xtreamPass = "p", epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
        connectionsAllowed = connectionsAllowed, accountMaxConnections = account,
    )

    @Test fun nonPositiveConnectionsAllowedIsIgnored() {
        assertEquals(3, playlist(3, 2).effectiveConnections())
        assertEquals(2, playlist(0, 2).effectiveConnections())
        assertEquals(2, playlist(-1, 2).effectiveConnections())
        assertEquals(DEFAULT_CONNECTIONS, playlist(0, null).effectiveConnections())
        assertEquals(DEFAULT_CONNECTIONS, playlist(-1, 0).effectiveConnections())
    }
}
