package io.github.loopholetv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {
    private val tv = "📺"

    @Test fun shortTextIsUnchanged() {
        assertEquals("abc", "abc".takeCodePoints(3))
        assertEquals("", "".takeCodePoints(0))
        assertEquals(tv, tv.takeCodePoints(1))
    }

    @Test fun countsCodePointsNotUnits() {
        assertEquals(tv.repeat(40), tv.repeat(60).takeCodePoints(40))
        assertEquals("a".repeat(39) + tv, ("a".repeat(39) + tv + "b").takeCodePoints(40))
    }

    @Test fun neverSplitsASurrogatePair() {
        val cut = ("x".repeat(39) + tv).takeCodePoints(39)
        assertEquals("x".repeat(39), cut)
        val text = "ab$tv$tv"
        for (n in 0..4) {
            val taken = text.takeCodePoints(n)
            assertEquals(false, taken.isNotEmpty() && Character.isHighSurrogate(taken.last()))
        }
    }
}
