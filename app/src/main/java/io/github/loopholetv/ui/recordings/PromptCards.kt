package io.github.loopholetv.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.record.RecordPrompt
import io.github.loopholetv.record.ReminderPrompt
import io.github.loopholetv.record.Use
import io.github.loopholetv.record.reminderText
import io.github.loopholetv.ui.common.DialogCard
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.theme.Tokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CARD_WIDTH = 520.dp

internal fun warningHeadline(secondsLeft: Int): String = "Recording starts in $secondsLeft s"

internal fun warningDetail(w: RecordPrompt.Warning): String = "${w.channel} · ${w.title}"

/** What Switch now would stop; null for a use a warning is never shown for. */
internal fun warningStops(stops: Use): String? = when (stops) {
    Use.PLAYER -> "This will stop what you're watching."
    Use.CAST -> "This will stop casting."
    else -> null
}

internal fun holdsStreamText(title: String, until: Instant, zone: ZoneId): String =
    "Recording $title until ${DateTimeFormatter.ofPattern("HH:mm").format(until.atZone(zone))}. Stop the recording and watch?"

/** A reminder over whatever is on screen; Back (and Dismiss) dismisses. The first button takes focus. */
@Composable
fun ReminderCard(p: ReminderPrompt, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val (headline, detail) = remember(p) { reminderText(p, ZoneId.systemDefault()) }
    PromptCard(listOf(headline, detail), "Watch" to onWatch, "Dismiss" to onDismiss, onBack = onDismiss)
}

/** The 30 s warning before a recording takes the stream; Back keeps watching. */
@Composable
fun RecordWarningCard(w: RecordPrompt.Warning, onKeep: () -> Unit, onSwitch: () -> Unit) {
    val lines = listOfNotNull(warningHeadline(w.secondsLeft), warningDetail(w), warningStops(w.stops))
    PromptCard(lines, "Keep watching" to onKeep, "Switch now" to onSwitch, onBack = onKeep)
}

/** A recording holds the stream the viewer asked for; Back cancels the play. */
@Composable
fun HoldsStreamDialog(p: RecordPrompt.HoldsStream, onStop: () -> Unit, onCancel: () -> Unit) {
    val text = remember(p) { holdsStreamText(p.title, p.until, ZoneId.systemDefault()) }
    PromptCard(listOf(text), "Stop recording" to onStop, "Cancel" to onCancel, onBack = onCancel, focusSecond = true)
}

/** Its own window, so the screen below keeps its focus and gets it back, and keys can't reach it meanwhile. */
@Composable
private fun PromptCard(
    lines: List<String>,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    onBack: () -> Unit,
    /** Focus starts on the secondary button, when the primary is the destructive one. */
    focusSecond: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    DialogCard(onDismiss = onBack, width = CARD_WIDTH) {
        lines.forEachIndexed { i, line ->
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (i == 0) Tokens.OnBackground else Tokens.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = primary.second, modifier = if (focusSecond) Modifier else Modifier.focusRequester(focus)) { Text(primary.first) }
            Button(onClick = secondary.second, modifier = if (focusSecond) Modifier.focusRequester(focus) else Modifier) { Text(secondary.first) }
        }
    }
    RequestFocusOnOpen(focus)
}
