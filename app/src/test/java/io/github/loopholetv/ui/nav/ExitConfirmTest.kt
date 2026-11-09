package io.github.loopholetv.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitConfirmTest {
    private var now = 10_000L
    private val exit = ExitConfirm(clock = { now })

    @Test
    fun firstBackShowsHint() {
        assertEquals(ExitDecision.SHOW_HINT, exit.onBack())
    }

    @Test
    fun secondWithinWindowExits() {
        exit.onBack()
        now += 1_999
        assertEquals(ExitDecision.EXIT, exit.onBack())
    }

    @Test
    fun secondAfterWindowShowsHintAgain() {
        exit.onBack()
        now += 2_001
        assertEquals(ExitDecision.SHOW_HINT, exit.onBack())
        now += 500
        assertEquals(ExitDecision.EXIT, exit.onBack())
    }
}
