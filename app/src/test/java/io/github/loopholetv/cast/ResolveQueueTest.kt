package io.github.loopholetv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveQueueTest {
    private val queue = ResolveQueue<String>(max = 3)

    @Test
    fun runsOneAtATimeInOrder() {
        queue.offer("a", "A")
        queue.offer("b", "B")
        val first = queue.start()!!
        assertEquals("a", first.key)
        assertNull(queue.start())
        assertTrue(queue.finish(first.token))
        assertEquals("b", queue.start()!!.key)
    }

    @Test
    fun keyIsQueuedAtMostOnce() {
        assertTrue(queue.offer("a", "A"))
        assertFalse(queue.offer("a", "A"))
        queue.start()
        assertFalse(queue.offer("a", "A")) // running
        assertTrue(queue.offer("b", "B"))
    }

    @Test
    fun waitingItemsAreBounded() {
        assertTrue(queue.offer("a", "A"))
        assertTrue(queue.offer("b", "B"))
        assertTrue(queue.offer("c", "C"))
        assertFalse(queue.offer("d", "D"))
        queue.start()
        assertTrue(queue.offer("d", "D"))
    }

    @Test
    fun retryPutsTheItemBackInFront() {
        queue.offer("a", "A")
        queue.offer("b", "B")
        val first = queue.start()!!
        assertTrue(queue.retry(first.token))
        val again = queue.start()!!
        assertEquals("a", again.key)
        assertEquals("A", again.item)
        queue.finish(again.token)
        assertEquals("b", queue.start()!!.key)
    }

    @Test
    fun lateCallbacksOfAFinishedItemDoNothing() {
        queue.offer("a", "A")
        val first = queue.start()!!
        assertTrue(queue.finish(first.token)) // for example a timeout
        queue.offer("b", "B")
        val second = queue.start()!!
        assertFalse(queue.finish(first.token))
        assertFalse(queue.retry(first.token))
        assertEquals("b", second.key)
        assertNull(queue.start()) // b is still running
    }

    @Test
    fun clearDropsEverythingAndInvalidatesTheRunningItem() {
        queue.offer("a", "A")
        queue.offer("b", "B")
        val first = queue.start()!!
        queue.clear()
        assertFalse(queue.finish(first.token))
        assertNull(queue.start())
        queue.offer("c", "C")
        assertEquals("c", queue.start()!!.key)
    }
}
