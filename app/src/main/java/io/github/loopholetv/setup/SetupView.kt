package io.github.loopholetv.setup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the setup screens need from a copy between TVs; [SetupController] is the real one. */
interface SetupView {
    val state: StateFlow<SetupState>

    /** "Send my setup to this TV". */
    fun sendTo(peerId: String)

    /** "Copy this TV's setup", and the first-start picker. */
    fun copyFrom(peerId: String)

    /** The four digits the other TV shows, while this TV asks for them. */
    fun enterCode(code: String)

    /** Stops the copy, or clears its result. */
    fun cancel()
}

/** No copying at all: always [SetupState.Idle], nothing happens. A default for screens. */
object NoSetup : SetupView {
    override val state: StateFlow<SetupState> = MutableStateFlow<SetupState>(SetupState.Idle).asStateFlow()
    override fun sendTo(peerId: String) {}
    override fun copyFrom(peerId: String) {}
    override fun enterCode(code: String) {}
    override fun cancel() {}
}
