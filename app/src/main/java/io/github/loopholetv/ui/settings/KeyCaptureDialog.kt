package io.github.loopholetv.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.theme.Tokens

internal const val CAPTURE_TITLE = "Press the button you want to set up"
internal const val CAPTURE_HINT = "Back cancels"
internal const val CAPTURE_REJECTED = "That button can't be changed."

sealed interface CaptureResult {
    data object Cancel : CaptureResult
    data class Key(val code: Int) : CaptureResult
}

/**
 * Decides what a key event means while a button is being captured. The dialog consumes every
 * event; this says whether it also does something. A button is taken on its first KeyDown. Back
 * cancels on KeyUp, so its KeyUp never reaches the panel's Back handling after the dialog is gone.
 */
class CaptureKeyRouter {
    fun onKey(phase: KeyPhase, code: Int, repeatCount: Int): CaptureResult? = when {
        code == AndroidKeyEvent.KEYCODE_BACK -> if (phase == KeyPhase.Up) CaptureResult.Cancel else null
        phase == KeyPhase.Down && repeatCount == 0 -> CaptureResult.Key(code)
        else -> null
    }
}

/**
 * Waits for one remote button. Every key press is consumed here, so arrows and OK cannot move focus
 * away; Back calls [onCancel], any other button calls [onKey] with its key code. [message] is an
 * optional line under the hint (used to say a button can't be changed).
 */
@Composable
fun KeyCaptureDialog(onKey: (Int) -> Unit, onCancel: () -> Unit, message: String? = null) {
    val focus = remember { FocusRequester() }
    val router = remember { CaptureKeyRouter() }
    LaunchedEffect(Unit) { focusSoon(focus) }
    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val phase = when (event.type) {
                    KeyEventType.KeyDown -> KeyPhase.Down
                    KeyEventType.KeyUp -> KeyPhase.Up
                    else -> return@onPreviewKeyEvent true
                }
                when (val r = router.onKey(phase, event.nativeKeyEvent.keyCode, event.nativeKeyEvent.repeatCount)) {
                    CaptureResult.Cancel -> onCancel()
                    is CaptureResult.Key -> onKey(r.code)
                    null -> Unit
                }
                true
            }
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(CAPTURE_TITLE, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
        Text(CAPTURE_HINT, style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = Tokens.Error)
    }
}
