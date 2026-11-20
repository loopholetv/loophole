package io.github.loopholetv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.theme.Tokens

/**
 * Whether OK on the player means "next episode": only while the Next episode button shows and the
 * episode is playing. Otherwise OK pauses and resumes as always, so the OK that resumes from pause
 * never skips ahead.
 */
internal fun okGoesToNext(ui: AutoNextUi, paused: Boolean): Boolean = ui is AutoNextUi.Pill && !paused

/** Whether [ui] is one of the cards that take focus between episodes. */
internal fun AutoNextUi.isCard(): Boolean = this is AutoNextUi.Countdown || this is AutoNextUi.StillWatching

/** Holding OK or Menu opens the options only over a picture: never on an error or a card, which keep focus. */
internal fun optionsMayOpen(hasError: Boolean, card: Boolean): Boolean = !hasError && !card

/** Home opens picture-in-picture only over a picture: a card between episodes has none, so Home leaves. */
internal fun pipMayOpen(card: Boolean): Boolean = !card

/**
 * What auto-play shows over the player, filling it: the Next episode button bottom right (OK on the
 * player presses it; it never takes focus), or a centred card between episodes whose first button
 * takes focus as it appears.
 */
@Composable
fun AutoNextOverlay(
    ui: AutoNextUi,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    onKeepWatching: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (ui) {
            AutoNextUi.Hidden -> Unit
            AutoNextUi.Pill -> NextPill(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = PILL_BOTTOM))
            is AutoNextUi.Countdown -> AutoNextCard(
                lines = listOf(ui.label, "Starting in ${ui.secondsLeft} s"),
                primary = "Play now" to onPlayNow,
                secondary = "Cancel" to onCancel,
                modifier = Modifier.align(Alignment.Center),
            )
            AutoNextUi.StillWatching -> AutoNextCard(
                lines = listOf("Still watching?"),
                primary = "Keep watching" to onKeepWatching,
                secondary = "Stop" to onStop,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Clears the seek bar's text, which sits in the bottom ~110 dp of the screen. */
private val PILL_BOTTOM = 140.dp

@Composable
private fun NextPill(modifier: Modifier) {
    Text(
        "Next episode ›",
        style = MaterialTheme.typography.titleMedium,
        color = Tokens.OnFocus,
        modifier = modifier
            .background(Tokens.Accent, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** A card in the style of the player's error card; [primary] takes focus when it appears. */
@Composable
private fun AutoNextCard(
    lines: List<String>,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    modifier: Modifier,
) {
    // Each card is composed afresh, so the question replacing the countdown focuses its own first button.
    val focus = remember { FocusRequester() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .width(520.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(28.dp),
    ) {
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.OnBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = primary.second, modifier = Modifier.focusRequester(focus)) { Text(primary.first) }
            Button(onClick = secondary.second) { Text(secondary.first) }
        }
    }
    RequestFocusOnOpen(focus)
}
