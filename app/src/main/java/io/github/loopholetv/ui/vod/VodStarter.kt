package io.github.loopholetv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.ui.common.DialogCard
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens

/** Whether on-demand playback may start now, or must first end this TV's part in a cast. */
enum class VodStartGate { GO, ASK_STOP_CAST, ASK_LEAVE_CAST }

fun vodStartGate(state: CastState): VodStartGate = when (state) {
    is CastState.Off -> VodStartGate.GO
    is CastState.Casting -> VodStartGate.ASK_STOP_CAST
    is CastState.Following -> VodStartGate.ASK_LEAVE_CAST
}

internal const val STOP_CAST_PROMPT = "Playing this will stop casting to the other TVs. Continue?"
internal const val LEAVE_CAST_PROMPT = "Playing this will leave the cast. Continue?"

private class PendingStart(val ref: VodRef, val startMs: Long, val gate: VodStartGate)

/**
 * Starts a film or episode. On-demand streams are never cast, so while this TV casts or follows,
 * [start] asks first and only then ends the cast and calls [navigate].
 */
class VodStarter internal constructor(
    private val cast: CastView,
    private val navigate: (VodRef, Long) -> Unit,
) {
    private var pending by mutableStateOf<PendingStart?>(null)

    fun start(ref: VodRef, startMs: Long) {
        val gate = vodStartGate(cast.state.value)
        if (gate == VodStartGate.GO) navigate(ref, startMs) else pending = PendingStart(ref, startMs, gate)
    }

    /** Re-reads the cast state: it may have changed while the prompt was open. */
    private fun confirm() {
        val ask = pending ?: return
        val now = vodStartGate(cast.state.value)
        if (now != VodStartGate.GO && now != ask.gate) {
            pending = PendingStart(ask.ref, ask.startMs, now)
            return
        }
        pending = null
        when (now) {
            VodStartGate.ASK_STOP_CAST -> cast.stopCasting(StopReason.EXTERNAL_PLAYER)
            VodStartGate.ASK_LEAVE_CAST -> cast.leave()
            VodStartGate.GO -> {}
        }
        navigate(ask.ref, ask.startMs)
    }

    private fun cancel() { pending = null }

    @Composable
    internal fun Prompt() {
        val ask = pending ?: return
        val text = if (ask.gate == VodStartGate.ASK_STOP_CAST) STOP_CAST_PROMPT else LEAVE_CAST_PROMPT
        val cancelButton = remember { FocusRequester() }
        RequestFocusOnOpen(cancelButton)
        DialogCard(onDismiss = ::cancel) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.OnBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                PillButton("Continue", selected = false, containerColor = Tokens.Surface, onClick = ::confirm)
                PillButton(
                    "Cancel", selected = false, containerColor = Tokens.Surface,
                    modifier = Modifier.focusRequester(cancelButton), onClick = ::cancel,
                )
            }
        }
    }
}

/** A [VodStarter] whose cast prompt is shown by the calling composition. */
@Composable
fun rememberVodStarter(cast: CastView, navigate: (VodRef, Long) -> Unit): VodStarter {
    val latest by rememberUpdatedState(navigate)
    val starter = remember(cast) { VodStarter(cast) { ref, ms -> latest(ref, ms) } }
    starter.Prompt()
    return starter
}
