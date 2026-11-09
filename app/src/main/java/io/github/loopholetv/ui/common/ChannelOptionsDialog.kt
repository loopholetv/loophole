package io.github.loopholetv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

data class ChannelOption(val label: String, val onClick: () -> Unit)

const val OPTION_ADD_FAVOURITE = "Add to favourites"
const val OPTION_REMOVE_FAVOURITE = "Remove from favourites"
const val OPTION_MOVE = "Move"

/** The options panel's title for [channel]: the number and name the guide shows. */
fun channelOptionsTitle(channel: ChannelEntity): String = "${channel.guideNumber}  ${channel.displayName}"

/**
 * Centred options card for a channel (guide and player). It is its own window, so Back dismisses
 * it ([onDismiss]) and never reaches the screen underneath. The first option takes focus.
 */
@Composable
fun ChannelOptionsDialog(title: String, options: List<ChannelOption>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    RequestFocusOnOpen(first)
    // Scrolls: a long options list must not run off a 1080p screen (the focused row scrolls in).
    DialogCard(onDismiss = onDismiss, scrollable = true) {
        DialogTitle(title)
        options.forEachIndexed { index, option ->
            PillButton(
                label = option.label,
                selected = false,
                containerColor = Tokens.Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                onClick = option.onClick,
            )
        }
    }
}

/**
 * The window and card of every Loophole dialog: its own [Dialog] (Back calls [onDismiss]), and
 * opened on a held OK, the rest of that press is swallowed so it can't click what has focus.
 */
@Composable
fun DialogCard(
    onDismiss: () -> Unit,
    width: Dp = 420.dp,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gate = remember { HeldKeyGate() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .onPreviewKeyEvent { e ->
                    val phase = when (e.type) {
                        KeyEventType.KeyDown -> KeyPhase.Down
                        KeyEventType.KeyUp -> KeyPhase.Up
                        else -> return@onPreviewKeyEvent false
                    }
                    !gate.allows(phase, isOkKey(e.key.nativeKeyCode), e.nativeKeyEvent.repeatCount)
                }
                .width(width)
                .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

@Composable
fun DialogTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Tokens.OnBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Focuses [requester] once the dialog window has attached its content (a frame or two later). */
@Composable
fun RequestFocusOnOpen(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(20) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
}
