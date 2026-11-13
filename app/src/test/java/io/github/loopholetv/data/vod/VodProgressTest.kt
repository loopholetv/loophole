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
}
