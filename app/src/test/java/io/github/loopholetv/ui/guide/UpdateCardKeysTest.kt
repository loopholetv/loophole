package io.github.loopholetv.ui.guide

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCardKeysTest {
    @Test
    fun rightMovesFromUpdateToLaterAndStopsThere() {
        assertEquals(CardKey.PASS, cardKeyFor(Key.DirectionRight, buttonIndex = 0, buttonCount = 2))
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionRight, buttonIndex = 1, buttonCount = 2))
    }

    @Test
    fun leftStopsOnTheFirstButton() {
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionLeft, buttonIndex = 0, buttonCount = 2))
        assertEquals(CardKey.PASS, cardKeyFor(Key.DirectionLeft, buttonIndex = 1, buttonCount = 2))
    }

    @Test
    fun upStaysAndDownLeavesTheCard() {
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionUp, 0, 2))
        assertEquals(CardKey.LEAVE, cardKeyFor(Key.DirectionDown, 1, 2))
    }

    @Test
    fun upAndLeftFromTheFirstButtonReachTheCastBanner() {
        assertEquals(CardKey.BANNER, cardKeyFor(Key.DirectionUp, 1, 2, bannerAbove = true))
        assertEquals(CardKey.BANNER, cardKeyFor(Key.DirectionLeft, 0, 2, bannerAbove = true))
        assertEquals(CardKey.PASS, cardKeyFor(Key.DirectionLeft, 1, 2, bannerAbove = true))
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionRight, 1, 2, bannerAbove = true))
        assertEquals(CardKey.LEAVE, cardKeyFor(Key.DirectionDown, 0, 2, bannerAbove = true))
    }

    @Test
    fun aSingleButtonTrapsLeftAndRight() {
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionLeft, 0, 1))
        assertEquals(CardKey.STAY, cardKeyFor(Key.DirectionRight, 0, 1))
        assertEquals(CardKey.PASS, cardKeyFor(Key.DirectionCenter, 0, 1))
    }
}
