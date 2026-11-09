package io.github.loopholetv.ui.guide

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.OkPressAction
import io.github.loopholetv.ui.common.OkPressTracker
import io.github.loopholetv.ui.common.isOkKey

/**
 * Move mode step for a key event: -1 (Up), +1 (Down) or 0. Held-key repeats are ignored, so a
 * held key moves one place and the row cannot run on after release on a slow device.
 */
internal fun moveStepFor(up: Boolean, down: Boolean, keyDown: Boolean, repeatCount: Int): Int = when {
    !keyDown || repeatCount > 0 -> 0
    up -> -1
    down -> 1
    else -> 0
}

/**
 * A key event in Move mode (favourites, a channel in its group, or a group): Up/Down move one place
 * ([onStep]), an OK click ([OkPressTracker]: on release) finishes ([onEnd]), Left/Right and Menu
 * are swallowed, so nothing leaves the moved row. Returns whether the event was consumed (Back is
 * left to the screen). The press that picked "Move" doesn't end it only because the options dialog
 * consumes that press's release.
 */
internal fun moveModeKey(e: KeyEvent, ok: OkPressTracker, onStep: (Int) -> Unit, onEnd: () -> Unit): Boolean {
    val phase = when (e.type) {
        KeyEventType.KeyDown -> KeyPhase.Down
        KeyEventType.KeyUp -> KeyPhase.Up
        else -> return true
    }
    val code = e.key.nativeKeyCode
    return when {
        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
            // One place per press: held-key repeats would queue moves that run on after release
            // on a slow device.
            val step = moveStepFor(
                up = e.key == Key.DirectionUp, down = e.key == Key.DirectionDown,
                keyDown = phase == KeyPhase.Down, repeatCount = e.nativeKeyEvent.repeatCount,
            )
            if (step != 0) onStep(step)
            true
        }
        e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> true
        isOkKey(code) || code == AndroidKeyEvent.KEYCODE_MENU -> {
            val action = ok.onKey(phase, isOkKey(code), code == AndroidKeyEvent.KEYCODE_MENU, e.nativeKeyEvent.repeatCount)
            if (action == OkPressAction.Click) onEnd()
            true
        }
        else -> false
    }
}

/** Where focus goes before removing a row (a favourite removed, a channel hidden) from the list. */
internal sealed interface RemovalFocus {
    /** Onto this row (the next one, or the previous one when it was the last). */
    data class Row(val channelId: Long) : RemovalFocus

    /** It was the only row: onto the selected group. */
    data object Group : RemovalFocus

    /** Not among the loaded rows: nothing to move first. */
    data object Stay : RemovalFocus
}

internal fun removalFocusFor(rowIds: List<Long?>, channelId: Long): RemovalFocus {
    val index = rowIds.indexOf(channelId)
    if (index < 0) return RemovalFocus.Stay
    val neighbour = rowIds.getOrNull(index + 1) ?: rowIds.getOrNull(index - 1)
    return if (neighbour != null) RemovalFocus.Row(neighbour) else RemovalFocus.Group
}

/**
 * The group focus goes to before [hidden] leaves the groups column: the next group, else the
 * previous one (Favourites and All channels count), else null.
 */
internal fun groupFocusAfterHiding(groups: List<GroupItem>, hidden: GroupItem): GroupItem? {
    val index = groups.indexOfFirst { it.scope == hidden.scope }
    if (index < 0) return null
    return groups.getOrNull(index + 1) ?: groups.getOrNull(index - 1)
}
