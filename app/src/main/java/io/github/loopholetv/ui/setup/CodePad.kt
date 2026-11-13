package io.github.loopholetv.ui.setup

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How many digits a code has. */
internal const val CODE_LENGTH = 4

/** The on-screen pad: 1–9 in rows of three, then Delete, 0, OK, and Cancel under them. */
internal const val PAD_COLUMNS = 3
internal const val PAD_DELETE = 9
internal const val PAD_ZERO = 10
internal const val PAD_OK = 11
internal const val PAD_CANCEL = 12

/** The digit rows end at [PAD_OK]; [PAD_CANCEL] is a row of its own. */
private const val PAD_ROWS = 4

internal const val DELETE_LABEL = "Delete"
internal const val OK_LABEL = "OK"
internal const val CANCEL_LABEL = "Cancel"

internal fun padLabel(index: Int): String = when (index) {
    in 0..8 -> "${index + 1}"
    PAD_DELETE -> DELETE_LABEL
    PAD_ZERO -> "0"
    PAD_OK -> OK_LABEL
    PAD_CANCEL -> CANCEL_LABEL
    else -> ""
}

/** The digit a remote key types (top-row and numpad digits), or null. */
internal fun digitFor(keyCode: Int): Char? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}

/**
 * Typing the other TV's code: [digits] so far and the [focused] pad button. The fourth digit
 * calls [onCode] at once; OK calls it again with four digits. Back deletes a digit, or calls
 * [onCancel] when there are none. The digits are shown on screen only, never logged.
 */
class CodePad(private val onCode: (String) -> Unit, private val onCancel: () -> Unit) {
    var digits by mutableStateOf("")
        private set

    /** The highlighted pad button (see [padLabel]); every entry starts on 1. */
    var focused by mutableIntStateOf(0)
        private set

    fun type(digit: Char) {
        if (digit !in '0'..'9' || digits.length >= CODE_LENGTH) return
        digits += digit
        if (digits.length == CODE_LENGTH) onCode(digits)
    }

    /** OK on the highlighted button. */
    fun press() {
        when (val f = focused) {
            in 0..8 -> type('1' + f)
            PAD_ZERO -> type('0')
            PAD_DELETE -> delete()
            PAD_OK -> if (digits.length == CODE_LENGTH) onCode(digits)
            PAD_CANCEL -> onCancel()
        }
    }

    fun back() {
        if (digits.isEmpty()) onCancel() else delete()
    }

    /** Moves the highlight by one button ([dx] across, [dy] down); the edges stop it. */
    fun move(dx: Int, dy: Int) {
        val f = focused
        focused = if (f == PAD_CANCEL) {
            if (dy < 0) PAD_ZERO else f
        } else {
            val row = f / PAD_COLUMNS
            val col = f % PAD_COLUMNS
            when {
                dy > 0 && row == PAD_ROWS - 1 -> PAD_CANCEL
                dy != 0 -> (row + dy).coerceIn(0, PAD_ROWS - 1) * PAD_COLUMNS + col
                else -> row * PAD_COLUMNS + (col + dx).coerceIn(0, PAD_COLUMNS - 1)
            }
        }
    }

    /** A wrong code: the digits go, the highlight stays. */
    fun clear() {
        digits = ""
    }

    /** A new entry. */
    fun reset() {
        digits = ""
        focused = 0
    }

    private fun delete() {
        digits = digits.dropLast(1)
    }
}
