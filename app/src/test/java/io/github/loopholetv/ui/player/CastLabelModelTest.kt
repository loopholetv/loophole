package io.github.loopholetv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastLabelModelTest {
    private var now = 1_000L

    @Test
    fun castingLabelCollapsesAfter5s() {
        val t = CastLabelTimer({ now }, 5_000)
        assertEquals(CastLabelState("Casting to 2 TVs", 2, true), t.update("Casting to 2 TVs", 2))
        now += 4_999
        assertEquals(true, t.tick()?.expanded)
        now += 1
        assertEquals(CastLabelState("Casting to 2 TVs", 2, false), t.tick())
    }

    @Test
    fun changeReexpandsFor5s() {
        val t = CastLabelTimer({ now }, 5_000)
        t.update("Casting to 1 TV", 1)
        now += 6_000
        assertEquals(false, t.tick()?.expanded)
        assertEquals(true, t.update("Casting to 2 TVs", 2)?.expanded)
        now += 4_999
        assertEquals(true, t.tick()?.expanded)
        now += 1
        assertEquals(false, t.tick()?.expanded)
        // The same values again change nothing.
        assertEquals(false, t.update("Casting to 2 TVs", 2)?.expanded)
    }

    @Test
    fun followLabelHidesAfter10s() {
        val t = CastLabelTimer({ now }, 10_000)
        assertEquals(true, t.update("From Den", null, 1)?.expanded)
        now += 10_000
        assertEquals(false, t.tick()?.expanded)
    }

    @Test
    fun followLabelReturnsOnChannelChange() {
        val t = CastLabelTimer({ now }, 10_000)
        t.update("From Den", null, 1)
        now += 12_000
        assertEquals(false, t.update("From Den", null, 1)?.expanded)
        assertEquals(true, t.update("From Den", null, 2)?.expanded)
        now += 12_000
        assertEquals(false, t.tick()?.expanded)
        assertEquals(true, t.update("From Kitchen", null, 2)?.expanded)
    }

    @Test
    fun remainingTimeCountsDownFromTheClock() {
        val t = CastLabelTimer({ now }, 5_000)
        assertEquals(0L, t.remainingMs())
        t.update("Casting", 0)
        assertEquals(5_000L, t.remainingMs())
        now += 1_200
        assertEquals(3_800L, t.remainingMs())
        now += 9_000
        assertEquals(0L, t.remainingMs())
    }

    @Test
    fun anotherCastIdReexpandsEvenWithTheSameNameAndEpoch() {
        val t = CastLabelTimer({ now }, 10_000)
        t.update("From Den", null, "a" to null)
        now += 12_000
        assertEquals(true, t.update("From Den", null, "b" to null)?.expanded)
    }

    @Test
    fun nullTextHides() {
        val t = CastLabelTimer({ now }, 5_000)
        t.update("Casting", 0)
        assertNull(t.update(null, null))
        assertNull(t.tick())
        // Showing again starts a fresh window.
        assertEquals(true, t.update("Casting", 0)?.expanded)
    }
}
