package io.github.loopholetv.ui.player

import android.view.KeyEvent
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.isOkKey

/** What the player root does with an OK or Menu event. */
enum class RootOkAction {
    /** Not the root's: the usual key path (tiles with the overlay up, other keys). */
    PassThrough,

    /** Swallowed with no action. */
    Consume,

    /** A short press that began at the root: OK as before (overlay / Try again / commit). */
    Click,

    /** Held OK or Menu on bare video or the banner: the current channel's options. */
    LongClick,

    /** OK while typing a number: commit at once (key-down, no hold detection). */
    Commit,
}

/** The OK press the root is following. */
enum class RootPress {
    None,

    /** Began at the root (key-down, repeat 0): it clicks on release or long-clicks when held. */
    Armed,

    /** Already acted on (long click, number commit): the rest of it is swallowed. */
    Swallow,
}

data class RootKeyState(val overlayVisible: Boolean, val numberEntry: Boolean, val optionsOpen: Boolean)

data class RootOkResult(val action: RootOkAction, val press: RootPress)

/**
 * Routes OK/Menu at the player root. Only a press that BEGINS at the root (key-down, repeat 0,
 * without the overlay) can click or long-click: the tail of a press that began on a footer tile
 * (whose long click hid the overlay before the options dialog took the keys) is swallowed. Menu
 * acts on its first key-down only, never while typing a number. With the overlay up the tiles
 * handle OK and Menu, except OK while typing a number, which commits.
 */
fun rootOkRoute(state: RootKeyState, keyCode: Int, phase: KeyPhase, repeat: Int, press: RootPress): RootOkResult {
    val isOk = isOkKey(keyCode)
    if (!isOk && keyCode != KeyEvent.KEYCODE_MENU) return RootOkResult(RootOkAction.PassThrough, press)
    if (state.optionsOpen) return RootOkResult(RootOkAction.Consume, RootPress.None)
    if (!isOk) {
        return when {
            state.overlayVisible -> RootOkResult(RootOkAction.PassThrough, press)
            phase == KeyPhase.Down && repeat == 0 && !state.numberEntry -> RootOkResult(RootOkAction.LongClick, press)
            else -> RootOkResult(RootOkAction.Consume, press)
        }
    }
    return when (phase) {
        KeyPhase.Down -> when {
            repeat == 0 -> when {
                state.numberEntry -> RootOkResult(RootOkAction.Commit, RootPress.Swallow)
                state.overlayVisible -> RootOkResult(RootOkAction.PassThrough, RootPress.None)
                else -> RootOkResult(RootOkAction.Consume, RootPress.Armed)
            }
            press == RootPress.Armed -> RootOkResult(RootOkAction.LongClick, RootPress.Swallow)
            press == RootPress.Swallow -> RootOkResult(RootOkAction.Consume, press)
            state.overlayVisible -> RootOkResult(RootOkAction.PassThrough, press)
            // Repeats of a press that began elsewhere (a tile): never ours.
            else -> RootOkResult(RootOkAction.Consume, press)
        }
        KeyPhase.Up -> when {
            press == RootPress.Armed -> RootOkResult(RootOkAction.Click, RootPress.None)
            press == RootPress.Swallow -> RootOkResult(RootOkAction.Consume, RootPress.None)
            state.overlayVisible -> RootOkResult(RootOkAction.PassThrough, RootPress.None)
            else -> RootOkResult(RootOkAction.Consume, RootPress.None)
        }
    }
}
