package io.github.loopholetv.ui.splash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Holds back the first playback of a screen while the start-up splash covers it, so a stream
 * opened behind the splash (last channel on start) connects only once the splash is gone or
 * skipped. Open by default; one instance per process.
 */
class StartGate {
    private val state = MutableStateFlow(true)

    val isOpen: Boolean get() = state.value

    fun close() {
        state.value = false
    }

    fun open() {
        state.value = true
    }

    /** Returns once the gate is open (at once when it already is). */
    suspend fun await() {
        state.first { it }
    }
}
