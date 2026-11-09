package io.github.loopholetv.ui.splash

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartGateTest {
    @Test
    fun `open by default, so await returns at once`() = runTest {
        val gate = StartGate()
        assertTrue(gate.isOpen)
        var passed = false
        launch { gate.await(); passed = true }
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun `await suspends while closed and resumes on open`() = runTest {
        val gate = StartGate()
        gate.close()
        assertFalse(gate.isOpen)
        var passed = false
        launch { gate.await(); passed = true }
        runCurrent()
        assertFalse(passed)
        gate.open()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun `closing again after open holds new waiters`() = runTest {
        val gate = StartGate()
        gate.close()
        gate.open()
        gate.close()
        var passed = false
        launch { gate.await(); passed = true }
        runCurrent()
        assertFalse(passed)
        gate.open()
        runCurrent()
        assertTrue(passed)
    }
}
