package io.github.loopholetv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.github.loopholetv.data.vod.NEXT_PILL_HIDE_MS
import io.github.loopholetv.data.vod.nextWindowStartMs
import org.junit.Test

class AutoNextTest {
    private val duration = 3_000_000L
    private val inWindow = duration - 60_000L

    private fun pill(
        position: Long = inWindow,
        durationMs: Long? = duration,
        hasNext: Boolean = true,
        paused: Boolean = false,
        inPip: Boolean = false,
        shownFor: Long = 0L,
        bar: Boolean = false,
    ) = pillVisible(position, durationMs, hasNext, paused, inPip, shownFor, bar)

    @Test fun pillHiddenBeforeWindow() = assertFalse(pill(position = duration - 300_000L))

    @Test fun pillShownInWindow() = assertTrue(pill())

    @Test fun pillHiddenAfterSeekingBackOutOfWindow() {
        assertTrue(pill(position = inWindow))
        assertFalse(pill(position = duration / 2))
    }

    @Test fun pillHiddenWhilePaused() = assertFalse(pill(paused = true))

    @Test fun pillHiddenInPip() = assertFalse(pill(inPip = true))

    @Test fun pillHiddenWithoutNextEpisode() = assertFalse(pill(hasNext = false))

    @Test fun pillHiddenWithUnknownDuration() {
        assertFalse(pill(durationMs = null))
        assertFalse(pill(durationMs = 0L, position = 0L))
    }

    @Test fun pillReturnsWithBar() {
        assertFalse(pill(shownFor = 10_000L))
        assertTrue(pill(shownFor = 10_000L, bar = true))
    }

    @Test fun counterAsksOnThirdAutoStart() {
        val c = StillWatchingCounter()
        assertFalse(c.consumeAutoStart())
        assertFalse(c.consumeAutoStart())
        assertTrue(c.consumeAutoStart())
    }

    @Test fun counterResetsOnKeyAndManualNext() {
        val c = StillWatchingCounter()
        c.consumeAutoStart()
        c.consumeAutoStart()
        c.onUserKey()
        assertFalse(c.consumeAutoStart())
        assertFalse(c.consumeAutoStart())
        c.onManualNext()
        assertFalse(c.consumeAutoStart())
        assertFalse(c.consumeAutoStart())
        c.reset()
        assertFalse(c.consumeAutoStart())
    }

    @Test fun countdownLabelFormats() {
        assertEquals("Next: S2 E5 · Pilot", countdownLabel(2, 5, "Pilot"))
        assertEquals("Next: Pilot", countdownLabel(null, 5, "Pilot"))
        assertEquals("Next: Pilot", countdownLabel(2, null, "Pilot"))
    }

    @Test fun pillVisibleExactlyAtWindowStart() =
        assertTrue(pill(position = nextWindowStartMs(duration)))

    @Test fun pillHiddenWhenShownForFullHideTime() =
        assertFalse(pill(shownFor = NEXT_PILL_HIDE_MS))

    @Test fun counterAllowsTwoMoreAfterResetFollowingAsk() {
        val c = StillWatchingCounter()
        assertFalse(c.consumeAutoStart())
        assertFalse(c.consumeAutoStart())
        assertTrue(c.consumeAutoStart())
        c.reset()
        assertFalse(c.consumeAutoStart())
        assertFalse(c.consumeAutoStart())
        assertTrue(c.consumeAutoStart())
    }

    @Test fun counterKeepsAskingUntilReset() {
        val c = StillWatchingCounter()
        repeat(2) { c.consumeAutoStart() }
        assertTrue(c.consumeAutoStart())
        assertTrue(c.consumeAutoStart())
    }
}
