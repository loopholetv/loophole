package io.github.loopholetv.ui.guide

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class CastBannerKeysTest {
    @Test fun upAndRightStayDownGoesOnLeftToThePreview() {
        assertEquals(CastBannerKey.STAY, castBannerKeyFor(Key.DirectionUp))
        assertEquals(CastBannerKey.DOWN, castBannerKeyFor(Key.DirectionDown))
        assertEquals(CastBannerKey.LEFT, castBannerKeyFor(Key.DirectionLeft))
        assertEquals(CastBannerKey.STAY, castBannerKeyFor(Key.DirectionRight))
        assertEquals("OK presses the pill", CastBannerKey.PASS, castBannerKeyFor(Key.DirectionCenter))
    }
}
