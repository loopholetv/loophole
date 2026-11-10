package io.github.loopholetv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private var expireCalls = 0
    private var skewMs = 0L

    private fun TestScope.timer() =
        SleepTimer(backgroundScope, { expireCalls++ }, elapsed = { testScheduler.currentTime + skewMs })

    private fun TestScope.wait(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun runningCountsDownFromElapsed() = runTest {
        val t = timer()
        t.start(15)
        assertEquals(SleepState.Running(15, 900_000), t.state.value)
        wait(60_000)
        assertEquals(SleepState.Running(15, 840_000), t.state.value)
    }

    @Test fun warningInLastMinute() = runTest {
        val t = timer()
        t.start(15)
        wait(14 * 60_000L)
        assertEquals(SleepState.Warning(15, 60), t.state.value)
        wait(30_000)
        assertEquals(SleepState.Warning(15, 30), t.state.value)
    }

    @Test fun expiryStopsAndEmits() = runTest {
        val t = timer()
        var emitted = 0
        backgroundScope.launch { t.expired.collect { emitted++ } }
        runCurrent()
        t.start(15)
        wait(15 * 60_000L)
        assertEquals(1, expireCalls)
        assertEquals(1, emitted)
        assertEquals(SleepState.Off, t.state.value)
        wait(60_000)
        assertEquals(1, expireCalls)
    }

    @Test fun keepWatchingRestartsSamePreset() = runTest {
        val t = timer()
        t.start(15)
        wait(14 * 60_000L + 20_000)
        t.keepWatching()
        assertEquals(SleepState.Running(15, 900_000), t.state.value)
        wait(15 * 60_000L - 1_000)
        assertEquals(0, expireCalls)
    }

    @Test fun keepWatchingOutsideWarningDoesNothing() = runTest {
        val t = timer()
        t.start(15)
        wait(120_000)
        t.keepWatching()
        assertEquals(SleepState.Running(15, 780_000), t.state.value)
    }

    @Test fun cancelStopsWithoutExpiry() = runTest {
        val t = timer()
        t.start(15)
        wait(60_000)
        t.cancel()
        assertEquals(SleepState.Off, t.state.value)
        wait(20 * 60_000L)
        assertEquals(0, expireCalls)
    }

    @Test fun lateTickStillExpiresOnTime() = runTest {
        val t = timer()
        t.start(15)
        skewMs = 20 * 60_000L
        wait(1_000)
        assertEquals(1, expireCalls)
        assertEquals(SleepState.Off, t.state.value)
    }

    @Test fun presetTexts() {
        assertEquals("15 min", sleepPresetText(15))
        assertEquals("1 hour", sleepPresetText(60))
        assertEquals("1 hour 30 min", sleepPresetText(90))
        assertEquals("2 hours", sleepPresetText(120))
        assertEquals("1 min left", sleepRemainingText(30_000))
        assertEquals("25 min left", sleepRemainingText(1_500_000))
        assertEquals("2 min left", sleepRemainingText(60_001))
    }

    @Test fun startRejectsUnknownPreset() = runTest {
        val t = timer()
        assertThrows(IllegalArgumentException::class.java) { t.start(20) }
    }
}
