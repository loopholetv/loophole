package io.github.loopholetv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHoldTest {
    @Test fun holdConsumedOnce() {
        val h = PreviewHold()
        assertFalse(h.consume())
        h.hold()
        assertTrue(h.consume())
        assertFalse(h.consume())
    }
}
