package io.github.loopholetv.player.catchup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekBatcherTest {
    private var clock = 0L
    private val batcher = SeekBatcher { clock }

    @Test fun tapsAccumulate() {
        repeat(3) { batcher.press(-1, 0) }
        assertEquals(-30_000L, batcher.pending)
    }

    @Test fun heldKeyAccelerates() {
        assertEquals(30_000L, stepMs(1, 0))
        assertEquals(-10_000L, stepMs(-1, 5))
        assertEquals(-30_000L, stepMs(-1, 6))
        assertEquals(60_000L, stepMs(1, 16))
        assertEquals(300_000L, stepMs(1, 31))
        var total = 0L
        for (n in 0..40) total = batcher.press(1, n)
        assertEquals((0..40).sumOf { stepMs(1, it) }, total)
    }

    @Test fun commitsOnceAfterRelease() {
        batcher.press(-1, 0)
        batcher.release()
        clock = 699
        assertNull(batcher.due())
        clock = 700
        assertEquals(-10_000L, batcher.due())
        assertNull(batcher.due())
        assertNull(batcher.pending)
    }

    @Test fun pressAfterReleaseExtendsBatch() {
        batcher.press(-1, 0)
        batcher.release()
        clock = 500
        batcher.press(-1, 0)
        clock = 700
        assertNull(batcher.due())
        batcher.release()
        clock = 1399
        assertNull(batcher.due())
        clock = 1400
        assertEquals(-20_000L, batcher.due())
    }

    @Test fun mixedDirectionsNet() {
        batcher.press(-1, 0)
        batcher.press(-1, 0)
        batcher.press(1, 0)
        assertEquals(10_000L, batcher.pending)
    }

    @Test fun heldKeyIsNotDueUntilReleased() {
        batcher.press(1, 0)
        clock = 5_000
        assertNull(batcher.due())
        batcher.cancel()
        assertNull(batcher.pending)
    }
}
