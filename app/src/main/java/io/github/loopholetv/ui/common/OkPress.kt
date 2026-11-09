package io.github.loopholetv.ui.common

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

enum class KeyPhase { Down, Up }

enum class OkPressAction { Click, LongClick }

/**
 * Tells a short OK press from a hold. A press clicks on KeyUp; holding OK (the first key repeat)
 * long-clicks once, and that press then does not click. The Menu key long-clicks at once.
 */
class OkPressTracker {
    private var longFired = false

    fun onKey(type: KeyPhase, isOk: Boolean, isMenu: Boolean, repeatCount: Int): OkPressAction? = when {
        // Once per press: holding ≡ must not re-fire on every key repeat.
        isMenu -> if (type == KeyPhase.Down && repeatCount == 0) OkPressAction.LongClick else null
        !isOk -> null
        type == KeyPhase.Down -> when {
            repeatCount == 0 -> { longFired = false; null }
            !longFired -> { longFired = true; OkPressAction.LongClick }
            else -> null
        }
        else -> if (longFired) { longFired = false; null } else OkPressAction.Click
    }
}

/**
 * For a window opened by a held OK (a long click fires on the first key repeat): the rest of that
 * press (more repeats, the KeyUp) must not reach it, or it would click whatever has focus there.
 * OK events are let through only once a fresh press (KeyDown, repeat 0) started inside; other
 * keys always pass. One instance per opened window.
 */
class HeldKeyGate {
    private var armed = false

    fun allows(type: KeyPhase, isOk: Boolean, repeatCount: Int): Boolean {
        if (!isOk || armed) return true
        if (type == KeyPhase.Down && repeatCount == 0) armed = true
        return armed
    }
}

internal fun isOkKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * OK clicks on release and long-clicks when held (or on Menu). Consumes every OK event, and Menu
 * events when [onLongClick] is set; other keys pass through.
 */
@Composable
fun Modifier.okPress(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val tracker = remember { OkPressTracker() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return onKeyEvent { e ->
        val code = e.key.nativeKeyCode
        val isOk = isOkKey(code)
        val isMenu = code == KeyEvent.KEYCODE_MENU && longClick != null
        if (!isOk && !isMenu) return@onKeyEvent false
        val phase = when (e.type) {
            KeyEventType.KeyDown -> KeyPhase.Down
            KeyEventType.KeyUp -> KeyPhase.Up
            else -> return@onKeyEvent true
        }
        when (tracker.onKey(phase, isOk, isMenu, e.nativeKeyEvent.repeatCount)) {
            OkPressAction.Click -> click()
            OkPressAction.LongClick -> longClick?.invoke() ?: Unit
            null -> Unit
        }
        true
    }
}
