package io.github.loopholetv.ui.cast

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens

internal fun inviteText(name: String): String = "$name is casting"

/** What a key press does while the invitation shows. */
internal sealed interface InviteKey {
    /** Moves the highlight to the button at [index] (0 Watch, 1 Ignore). */
    data class Select(val index: Int) : InviteKey
    data object Watch : InviteKey
    data object Ignore : InviteKey

    /** Taken and ignored while the card shows. */
    data object Consume : InviteKey

    /** Left to the system (volume). */
    data object Pass : InviteKey
}

/**
 * The card's buttons are the only thing the remote reaches while it shows: Left/Right move
 * between Watch and Ignore, OK presses the highlighted one, Back ignores the invitation, and
 * every other key except volume is ignored.
 */
internal fun inviteKeyFor(keyCode: Int, selected: Int): InviteKey = when (keyCode) {
    in BACK_KEYS -> InviteKey.Ignore
    KeyEvent.KEYCODE_DPAD_LEFT -> InviteKey.Select(0)
    KeyEvent.KEYCODE_DPAD_RIGHT -> InviteKey.Select(1)
    in OK_KEYS -> if (selected == 0) InviteKey.Watch else InviteKey.Ignore
    in VOLUME_KEYS -> InviteKey.Pass
    else -> InviteKey.Consume
}

/** OK on a remote, a keyboard or a game controller. */
private val OK_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
)

/**
 * Keys while the invitation shows, taken before the screen underneath sees them, so that
 * screen keeps its own focus and gets the remote back, unchanged, once the card goes. Only a
 * press that starts while the card shows is taken, together with its repeats and its release
 * (also after the card went). A key already held when the card came stays with the screen,
 * repeats and release included.
 */
internal class InviteKeys(private val onWatch: () -> Unit, private val onIgnore: () -> Unit) {
    /** The highlighted button: 0 Watch (where every invitation starts), 1 Ignore. */
    var selected by mutableIntStateOf(0)
        private set

    private val taken = mutableSetOf<Int>()

    /** A new invitation starts on Watch. */
    fun reset() {
        selected = 0
    }

    /**
     * True when the key is taken. [repeatCount] is the key-down's auto-repeat count (0 for the
     * first press); [showing] is whether the card is on screen.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, showing: Boolean): Boolean {
        if (!down) return taken.remove(keyCode)
        // Repeats follow their first press and do nothing more on the card.
        if (repeatCount > 0) return keyCode in taken
        // A new press: whatever happened to an earlier one (a release never seen) is over.
        taken.remove(keyCode)
        if (!showing) return false
        val action = inviteKeyFor(keyCode, selected)
        if (action == InviteKey.Pass) return false
        taken += keyCode
        when (action) {
            is InviteKey.Select -> selected = action.index
            InviteKey.Watch -> onWatch()
            InviteKey.Ignore -> onIgnore()
            InviteKey.Consume, InviteKey.Pass -> Unit
        }
        return true
    }
}

/**
 * `<name> is casting` with Watch and Ignore; [selected] is the highlighted button. The card
 * never takes focus itself: [InviteKeys] gives it the remote while it shows.
 */
@Composable
fun CastInviteCard(name: String, selected: Int, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(max = 360.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            inviteText(name),
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InviteButton("Watch", highlighted = selected == 0)
            InviteButton("Ignore", highlighted = selected == 1)
        }
    }
}

/** Drawn like a focused or unfocused pill button. */
@Composable
private fun InviteButton(label: String, highlighted: Boolean) {
    Box(
        Modifier
            .scale(if (highlighted) 1.1f else 1f)
            .background(if (highlighted) Tokens.FocusFill else Tokens.Surface, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted) Tokens.OnFocus else Tokens.OnBackground,
        )
    }
}
