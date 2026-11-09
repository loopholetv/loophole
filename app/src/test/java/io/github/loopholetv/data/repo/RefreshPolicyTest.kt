package io.github.loopholetv.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RefreshPolicyTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private fun ago(minutes: Long): Instant = now.minus(Duration.ofMinutes(minutes))

    @Test
    fun offIsNeverDue() {
        assertFalse(isDue(null, 0, now))
        assertFalse(isDue(ago(60 * 24 * 30), 0, now))
    }

    @Test
    fun neverRefreshedIsDue() {
        assertTrue(isDue(null, 12, now))
    }

    @Test
    fun dueWithinSlack() {
        assertTrue(isDue(ago(11 * 60 + 51), 12, now))
        assertTrue(isDue(ago(11 * 60 + 50), 12, now))
        assertFalse(isDue(ago(11 * 60 + 49), 12, now))
    }

    @Test
    fun startGapSkipsQuickRestart() {
        assertFalse(startRefreshWanted(onStart = true, lastSuccess = ago(10), intervalHours = 24, now = now))
        assertTrue(startRefreshWanted(onStart = true, lastSuccess = ago(16), intervalHours = 24, now = now))
        assertTrue(startRefreshWanted(onStart = true, lastSuccess = null, intervalHours = 0, now = now))
    }

    @Test
    fun startWithoutOnStartFallsBackToDue() {
        assertFalse(startRefreshWanted(onStart = false, lastSuccess = ago(16), intervalHours = 24, now = now))
        assertTrue(startRefreshWanted(onStart = false, lastSuccess = ago(25 * 60), intervalHours = 24, now = now))
        assertFalse(startRefreshWanted(onStart = false, lastSuccess = null, intervalHours = 0, now = now))
    }
}
