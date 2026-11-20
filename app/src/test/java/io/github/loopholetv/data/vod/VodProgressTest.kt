package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.VodProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VodProgressTest {
    private val ref = VodRef(VodKind.MOVIE, 1L, "k")
    private val now = Instant.ofEpochSecond(1_000_000)
    private val min = 60_000L

    private fun row(position: Long, watched: Boolean = false, at: Long = 10, duration: Long? = 100 * min, key: String = "k") =
        VodProgressEntity(VodKind.MOVIE, 1L, key, position, duration, watched, Instant.ofEpochSecond(at))

    @Test fun underThirtySecondsNotStarted() {
        assertNull(progressAfter(null, ref, 20_000, 100 * min, now))
    }

    @Test fun savesPosition() {
        val p = progressAfter(null, ref, 42 * min + 10_000, 100 * min, now)!!
        assertEquals(42 * min + 10_000, p.positionMs)
        assertFalse(p.watched)
        assertEquals(100 * min, p.durationMs)
        assertEquals(now, p.lastWatchedAt)
    }

    @Test fun ninetyPercentMarksWatched() {
        val p = progressAfter(null, ref, 90 * min, 100 * min, now)!!
        assertTrue(p.watched)
        assertEquals(0L, p.positionMs)
        assertEquals(now, p.lastWatchedAt)
    }

    @Test fun rewatchKeepsWatched() {
        val p = progressAfter(row(0, watched = true), ref, 10 * min, 100 * min, now)!!
        assertTrue(p.watched)
        assertEquals(10 * min, p.positionMs)
    }

    @Test fun unknownDurationNeverWatched() {
        val p = progressAfter(null, ref, 180 * min, null, now)!!
        assertFalse(p.watched)
        assertEquals(180 * min, p.positionMs)
        assertNull(p.durationMs)
    }

    @Test fun unknownDurationKeepsOldDuration() {
        val p = progressAfter(row(5 * min), ref, 6 * min, null, now)!!
        assertEquals(100 * min, p.durationMs)
    }

    @Test fun backingUpUnderThresholdResetsPositionButKeepsTimestamp() {
        val p = progressAfter(row(5 * min, at = 10), ref, 10_000, 100 * min, now)!!
        assertEquals(0L, p.positionMs)
        assertEquals(Instant.ofEpochSecond(10), p.lastWatchedAt)
    }

    @Test fun resumeFromNeedsThirtySeconds() {
        assertNull(resumeFrom(null))
        assertNull(resumeFrom(row(29_999)))
        assertEquals(30_000L, resumeFrom(row(30_000)))
    }

    private fun ep(n: Int, p: VodProgressEntity? = null) = EpisodeView("e$n", 1, n, "E$n", p)
    private fun epRow(n: Int, position: Long, watched: Boolean, at: Long) =
        VodProgressEntity(VodKind.EPISODE, 1L, "e$n", position, null, watched, Instant.ofEpochSecond(at))

    @Test fun continueTargetEmpty() {
        assertNull(continueTarget(emptyList()))
    }

    @Test fun continueTargetNoHistory() {
        val t = continueTarget(listOf(ep(1), ep(2)))!!
        assertEquals("e1", t.key)
        assertFalse(t.started)
    }

    @Test fun continueTargetMidEpisode() {
        val t = continueTarget(listOf(ep(1, epRow(1, 0, true, 5)), ep(2, epRow(2, 10 * min, false, 9)), ep(3)))!!
        assertEquals("e2", t.key)
        assertTrue(t.started)
    }

    @Test fun continueTargetFinishedMovesToNext() {
        val t = continueTarget(listOf(ep(1, epRow(1, 0, true, 5)), ep(2, epRow(2, 0, true, 9)), ep(3)))!!
        assertEquals("e3", t.key)
        assertEquals(3, t.episode)
        assertTrue(t.started)
    }

    @Test fun continueTargetFinishedLastStaysOnLast() {
        val t = continueTarget(listOf(ep(1, epRow(1, 0, true, 5)), ep(2, epRow(2, 0, true, 9))))!!
        assertEquals("e2", t.key)
    }

    @Test fun continueTargetWatchedWithPositionIsRewatch() {
        val t = continueTarget(listOf(ep(1, epRow(1, 0, true, 5)), ep(2, epRow(2, 10 * min, true, 9)), ep(3)))!!
        assertEquals("e2", t.key)
        assertTrue(t.started)
    }

    @Test fun naturalEndOfUnknownLengthResetsPosition() {
        val p = progressAfterNaturalEnd(row(40 * min, duration = null), ref, 50 * min, null, now)!!
        assertEquals(0L, p.positionMs)
        assertFalse(p.watched)
        assertNull(progressAfterNaturalEnd(null, ref, 50 * min, null, now))
        assertEquals(0L, progressAfterNaturalEnd(row(40 * min), ref, 50 * min, 0L, now)!!.positionMs)
    }

    @Test fun naturalEndOfUnknownLengthKeepsWatched() {
        assertTrue(progressAfterNaturalEnd(row(40 * min, watched = true), ref, 50 * min, null, now)!!.watched)
    }

    @Test fun naturalEndOfKnownLengthIsUnchanged() {
        val old = row(10 * min)
        assertEquals(progressAfter(old, ref, 20 * min, 100 * min, now), progressAfterNaturalEnd(old, ref, 20 * min, 100 * min, now))
        assertEquals(progressAfter(old, ref, 95 * min, 100 * min, now), progressAfterNaturalEnd(old, ref, 95 * min, 100 * min, now))
    }

    private fun sep(season: Int?, episode: Int?, p: VodProgressEntity? = null) =
        EpisodeView("s${season}e$episode", season, episode, "N", p)
    private fun sepRow(position: Long, watched: Boolean, at: Long, key: String) =
        VodProgressEntity(VodKind.EPISODE, 1L, key, position, 40 * min, watched, Instant.ofEpochSecond(at))

    @Test fun continueRowTargetNullWithoutHistory() {
        assertNull(continueRowTarget(listOf(ep(1), ep(2))))
        assertNull(continueRowTarget(emptyList()))
    }

    @Test fun continueRowTargetNullAfterFinalEpisodeWatched() {
        assertNull(continueRowTarget(listOf(ep(1, epRow(1, 0, true, 5)), ep(2, epRow(2, 0, true, 9)))))
    }

    @Test fun continueRowTargetNextAfterFinished() {
        val eps = listOf(
            sep(1, 3, sepRow(0, true, 9, "s1e3")),
            sep(1, 4),
        )
        val t = continueRowTarget(eps)!!
        assertEquals("s1e4", t.key)
        assertFalse(t.started)
    }

    @Test fun continueRowTargetInProgress() {
        val eps = listOf(
            sep(1, 9, sepRow(0, true, 5, "s1e9")),
            sep(2, 1, sepRow(5 * min, false, 9, "s2e1")),
            sep(2, 2),
        )
        val t = continueRowTarget(eps)!!
        assertEquals("s2e1", t.key)
        assertTrue(t.started)
    }

    @Test fun nextEpisodeCrossesSeason() {
        val eps = listOf(sep(1, 9), sep(1, 10), sep(2, 1), sep(2, 2))
        assertEquals("s2e1", nextEpisode(eps, "s1e10")!!.key)
        assertEquals("s1e10", nextEpisode(eps, "s1e9")!!.key)
    }

    @Test fun nextEpisodeNullForOtherAndLast() {
        val eps = listOf(sep(1, 1), sep(1, 2), sep(null, null), sep(3, null))
        assertNull(nextEpisode(eps, "s1e2"))
        assertNull(nextEpisode(eps, "snullenull"))
        assertNull(nextEpisode(eps, "s3enull"))
        assertNull(nextEpisode(eps, "missing"))
    }

    @Test fun nextWindowStartMs() {
        assertEquals(43 * min, nextWindowStartMs(45 * min))
        assertEquals(4 * min, nextWindowStartMs(5 * min))
    }
}
