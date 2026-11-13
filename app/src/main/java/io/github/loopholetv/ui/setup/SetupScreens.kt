package io.github.loopholetv.ui.setup

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.settings.focusSoon
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

internal fun showCodeText(name: String) = "Enter this code on $name to copy its setup here."
/** The setup screens' window, for tests. */
internal const val SETUP_OVERLAY_TAG = "setup-overlay"
internal const val REPLACES_WARNING = "This replaces this TV's playlists, guide, favourites and settings."
internal fun enterCodeText(name: String) = "Enter the code shown on $name"
internal fun askedCodeText(name: String) = "$name wants to copy your setup. Enter the code shown on $name."

/**
 * The setup screens over the whole app, whatever is on: the code to show, the code to type, the
 * copy under way and its result. They are a window of their own above every screen and panel,
 * with the remote to themselves while they show; nothing underneath pauses or loses its place.
 * Hidden while [hidden] (picture-in-picture). [now] is the clock of the codes' expiry times.
 */
@Composable
fun SetupOverlay(
    setup: SetupView,
    hidden: Boolean,
    onReturnToGuide: () -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime,
) {
    val toGuide by rememberUpdatedState(onReturnToGuide)
    val model = remember(setup) { SetupScreenModel(setup, onReturnToGuide = { toGuide() }) }
    LaunchedEffect(model) { model.run() }
    val shown = model.shown.collectAsState().value
    if (shown == null || hidden) return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusSoon(focus) }
        Box(
            Modifier
                .fillMaxSize()
                .testTag(SETUP_OVERLAY_TAG)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    if (!down && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    model.onKey(e.nativeKeyEvent.keyCode, down, e.nativeKeyEvent.repeatCount)
                },
        ) {
            SetupContent(shown, model.pad, now)
        }
    }
}

/** One setup screen for [state], full screen. */
@Composable
fun SetupContent(state: SetupState, pad: CodePad, now: () -> Long) {
    Box(
        // Nearly opaque: text underneath must not show through the screen's own.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.98f)).padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is SetupState.ShowingCode -> ShowCode(state, now)
            is SetupState.EnterCode -> EnterCode(state, pad)
            is SetupState.Working -> Working(state.text)
            is SetupState.Done -> Done(state.message)
            SetupState.Idle -> Unit
        }
    }
}

@Composable
private fun ShowCode(state: SetupState.ShowingCode, now: () -> Long) {
    var nowMs by remember { mutableLongStateOf(now()) }
    val totalMs = remember(state) { (state.expiresAtMs - now()).coerceAtLeast(1) }
    LaunchedEffect(state) {
        while (true) {
            nowMs = now()
            delay(TICK_MS)
        }
    }
    val leftMs = state.expiresAtMs - nowMs
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.widthIn(max = 760.dp),
    ) {
        Title(showCodeText(state.fromName))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (digit in state.code) {
                Box(
                    Modifier
                        .size(width = 96.dp, height = 120.dp)
                        .background(Tokens.Surface, RoundedCornerShape(16.dp))
                        .border(2.dp, Tokens.Accent, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(digit.toString(), style = MaterialTheme.typography.displayLarge, color = Tokens.Accent)
                }
            }
        }
        Text(
            REPLACES_WARNING,
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnBackground,
            textAlign = TextAlign.Center,
        )
        // A quiet countdown: a draining bar and the time left.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val fraction = (leftMs.toFloat() / totalMs).coerceIn(0f, 1f)
            Box(Modifier.width(160.dp).height(4.dp).background(Tokens.Outline, RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Tokens.Muted, RoundedCornerShape(50)))
            }
            Text(remainingText(leftMs), style = MaterialTheme.typography.labelLarge, color = Tokens.Muted)
        }
        PadButton(CANCEL_LABEL, highlighted = true)
    }
}

@Composable
private fun EnterCode(state: SetupState.EnterCode, pad: CodePad) {
    // The title and boxes sit level with the digit keys (Cancel hangs below them).
    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.widthIn(max = 520.dp).height(PAD_GRID_HEIGHT), verticalArrangement = Arrangement.Center) {
            Title(if (state.asked) askedCodeText(state.peerName) else enterCodeText(state.peerName), TextAlign.Start)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until CODE_LENGTH) {
                    val digit = pad.digits.getOrNull(i)
                    val next = i == pad.digits.length
                    Box(
                        Modifier
                            .size(width = 72.dp, height = 88.dp)
                            .background(Tokens.Surface, RoundedCornerShape(12.dp))
                            .border(2.dp, if (next) Tokens.Accent else Tokens.Outline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (digit != null) {
                            Text(digit.toString(), style = MaterialTheme.typography.displaySmall, color = Tokens.OnBackground)
                        }
                    }
                }
            }
            // Right under the boxes; the space is kept so nothing moves when it shows.
            Spacer(Modifier.height(12.dp))
            Text(
                state.error.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.Error,
                modifier = Modifier.heightIn(min = 28.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PAD_GAP)) {
            for (row in 0 until PAD_KEY_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
                    for (col in 0 until PAD_COLUMNS) {
                        val index = row * PAD_COLUMNS + col
                        PadButton(padLabel(index), highlighted = pad.focused == index, width = PAD_KEY_WIDTH)
                    }
                }
            }
            PadButton(CANCEL_LABEL, highlighted = pad.focused == PAD_CANCEL, width = PAD_KEY_WIDTH)
        }
    }
}

@Composable
private fun Working(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        CircularProgressIndicator(color = Tokens.Accent, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
        Title(text)
    }
}

@Composable
private fun Done(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.widthIn(max = 760.dp),
    ) {
        Title(message)
        PadButton(OK_LABEL, highlighted = true)
    }
}

@Composable
private fun Title(text: String, align: TextAlign = TextAlign.Center) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = Tokens.OnBackground,
        textAlign = align,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Drawn like a focused or unfocused pill button; the screens move the highlight themselves. */
@Composable
private fun PadButton(label: String, highlighted: Boolean, width: androidx.compose.ui.unit.Dp? = null) {
    Box(
        Modifier
            .scale(if (highlighted) 1.08f else 1f)
            .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = 120.dp))
            .height(PAD_KEY_HEIGHT)
            .background(if (highlighted) Tokens.FocusFill else Tokens.SurfaceRaised, RoundedCornerShape(50))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted) Tokens.OnFocus else Tokens.OnBackground,
            maxLines = 1,
        )
    }
}

private const val TICK_MS = 250L

/** The pad's digit keys: [PAD_KEY_ROWS] rows of fixed-size keys, so the screen can line up with them. */
private const val PAD_KEY_ROWS = 4
private val PAD_KEY_WIDTH = 104.dp
private val PAD_KEY_HEIGHT = 56.dp
private val PAD_GAP = 10.dp
private val PAD_GRID_HEIGHT = PAD_KEY_HEIGHT * PAD_KEY_ROWS + PAD_GAP * (PAD_KEY_ROWS - 1)
