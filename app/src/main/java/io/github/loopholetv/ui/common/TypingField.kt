package io.github.loopholetv.ui.common

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.loopholetv.ui.onboarding.Field

/**
 * The TV text-field behaviour shared by every field of one screen or dialog: fields are read-only
 * (no keyboard) until OK is pressed on one; [stop] (leaving a field with the D-pad, IME Done) or the
 * keyboard being hidden by Back returns them to read-only, so the next OK opens the keyboard again.
 */
@Stable
class TypingState internal constructor() {
    var typing by mutableStateOf(false)
        internal set
    var imeVisible by mutableStateOf(false)
        internal set
    internal var keyboard: SoftwareKeyboardController? = null

    fun start() { typing = true; keyboard?.show() }
    fun stop() { typing = false; keyboard?.hide() }
}

/**
 * Only a holder: the keyboard controller and IME visibility are read by each [TypingField] in its
 * own composition, so inside a Dialog they belong to the dialog's window, not the screen's.
 */
@Composable
fun rememberTypingState(): TypingState = remember { TypingState() }

/** The onboarding [Field] ("typing" pattern) bound to [state]; IME Done stops typing, then [onDone]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypingField(
    state: TypingState,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    onDone: () -> Unit = {},
) {
    // This window's keyboard and insets (a Dialog has its own window and text input service).
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    SideEffect {
        state.keyboard = keyboard
        state.imeVisible = imeVisible
    }
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        // Back hiding the keyboard returns the field to read-only, so the next OK reopens it.
        if (imeWasVisible && !imeVisible) state.typing = false
        imeWasVisible = imeVisible
    }
    Field(
        label = label,
        value = value,
        onChange = onChange,
        modifier = modifier,
        ime = ImeAction.Done,
        keyboardType = keyboardType,
        typing = state.typing,
        imeVisible = imeVisible,
        onActivate = state::start,
        busy = false,
        masked = masked,
        onDone = { state.stop(); onDone() },
        // As the onboarding form: a D-pad move out ends typing, so the next field (or this
        // one, re-entered) stays read-only until OK instead of reopening the keyboard.
        onMove = state::stop,
    )
}
