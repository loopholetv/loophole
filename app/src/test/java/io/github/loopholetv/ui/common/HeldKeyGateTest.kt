package io.github.loopholetv.ui.common

import io.github.loopholetv.ui.common.KeyPhase.Down
import io.github.loopholetv.ui.common.KeyPhase.Up
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldKeyGateTest {
    private val gate = HeldKeyGate()
    private fun ok(phase: KeyPhase, repeat: Int = 0) = gate.allows(phase, isOk = true, repeatCount = repeat)

    @Test fun okStillHeldFromTheOpeningPressIsSwallowed() {
        // The dialog opened on the first repeat of a held OK: the rest of that press never reaches it.
        assertFalse(ok(Down, 2))
        assertFalse(ok(Down, 3))
        assertFalse(ok(Up))
    }

    @Test fun freshPressInsideTheDialogPasses() {
        assertFalse(ok(Down, 5))
        assertFalse(ok(Up))
        assertTrue(ok(Down))
        assertTrue(ok(Up))
        // From then on every OK event passes, repeats included.
        assertTrue(ok(Down))
        assertTrue(ok(Down, 1))
        assertTrue(ok(Up))
    }

    @Test fun otherKeysAlwaysPass() {
        assertTrue(gate.allows(Down, isOk = false, repeatCount = 0))
        assertTrue(gate.allows(Down, isOk = false, repeatCount = 4))
        assertTrue(gate.allows(Up, isOk = false, repeatCount = 0))
        assertFalse("OK is still gated", ok(Up))
    }
}
