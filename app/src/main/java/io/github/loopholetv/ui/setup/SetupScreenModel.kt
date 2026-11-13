package io.github.loopholetv.ui.setup

import android.view.KeyEvent
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.cast.BACK_KEYS
import io.github.loopholetv.ui.cast.VOLUME_KEYS
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** How long a copy's result shows before it goes on its own. */
const val DONE_SHOWS_MS = 4_000L

/** OK on a remote, a keyboard or a game controller. */
private val OK_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
)

/** Time left on a code, as m:ss. */
internal fun remainingText(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/**
 * Between [setup] and the setup screens, which show over everything: what to show ([shown],
 * null for nothing), the code pad, and the remote while a screen shows ([onKey]).
 *
 * - A result goes after [doneShowsMs], or on OK or Back. The busy notice only hides: the copy
 *   it interrupted comes back on its own.
 * - When this TV's own setup was replaced, [onReturnToGuide] runs once.
 * - A wrong code empties the pad.
 *
 * Call [run] for as long as the screens can show.
 */
class SetupScreenModel(
    private val setup: SetupView,
    private val onReturnToGuide: () -> Unit,
    private val doneShowsMs: Long = DONE_SHOWS_MS,
) {
    val pad = CodePad(onCode = { setup.enterCode(it) }, onCancel = { setup.cancel() })

    /** A busy notice put away with OK or Back. */
    private val hidden = MutableStateFlow<SetupState?>(null)

    private val _shown = MutableStateFlow(visible(setup.state.value, null))
    val shown: StateFlow<SetupState?> = _shown.asStateFlow()

    /** The code entry the pad last belonged to. */
    private var lastAsk: SetupState.EnterCode? = null

    /** Keys pressed while a screen showed: only their release acts. */
    private val pressed = mutableSetOf<Int>()

    private fun visible(state: SetupState, hide: SetupState?): SetupState? =
        state.takeUnless { it is SetupState.Idle || it === hide }

    private fun current(): SetupState? = visible(setup.state.value, hidden.value)

    suspend fun run(): Unit = coroutineScope {
        launch { combine(setup.state, hidden, ::visible).collect { _shown.value = it } }
        setup.state.collectLatest { state ->
            arrived(state)
            if (state is SetupState.Done) {
                delay(doneShowsMs)
                dismiss(state)
            }
        }
    }

    private fun arrived(state: SetupState) {
        // A key held across a change of screen must not act on the new one.
        pressed.clear()
        when (state) {
            is SetupState.EnterCode -> {
                val last = lastAsk
                lastAsk = state
                if (last == null || last.peerName != state.peerName || last.asked != state.asked) {
                    pad.reset()
                } else if (state.error != null && state != last) {
                    pad.clear()
                }
            }
            is SetupState.Done -> {
                if (state.message != SetupController.FINISH_FIRST) lastAsk = null
                if (state.copied && state.received) onReturnToGuide()
            }
            SetupState.Idle -> lastAsk = null
            is SetupState.ShowingCode, is SetupState.Working -> Unit
        }
    }

    private fun dismiss(done: SetupState.Done) {
        if (setup.state.value !== done) return
        pressed.clear()
        if (done.message == SetupController.FINISH_FIRST) hidden.value = done else setup.cancel()
    }

    /**
     * A key while a screen shows; true when taken. Volume stays with the system. Digits and
     * D-pad moves act when pressed; OK and Back when released, and only after a press seen here,
     * so a key held from the screen underneath does nothing. [repeatCount] is the key-down's
     * auto-repeat count.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        if (keyCode in VOLUME_KEYS) return false
        val state = current()
        if (!down) {
            val seen = pressed.remove(keyCode)
            if (seen && state != null && (keyCode in OK_KEYS || keyCode in BACK_KEYS)) released(state, keyCode)
            return seen || state != null
        }
        if (state == null) return false
        if (repeatCount == 0) pressed += keyCode else if (keyCode !in pressed) return true
        if (state !is SetupState.EnterCode) return true
        val digit = digitFor(keyCode)
        when {
            digit != null -> if (repeatCount == 0) pad.type(digit)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> pad.move(0, -1)
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> pad.move(0, 1)
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> pad.move(-1, 0)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> pad.move(1, 0)
        }
        return true
    }

    private fun released(state: SetupState, keyCode: Int) {
        val back = keyCode in BACK_KEYS
        when (state) {
            is SetupState.ShowingCode -> setup.cancel()
            is SetupState.EnterCode -> if (back) pad.back() else pad.press()
            is SetupState.Working -> if (back) setup.cancel()
            is SetupState.Done -> dismiss(state)
            SetupState.Idle -> Unit
        }
    }
}
