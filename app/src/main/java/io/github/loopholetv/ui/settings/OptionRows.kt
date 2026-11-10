package io.github.loopholetv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

@Composable
private fun OptionItem(
    label: String,
    trailing: String?,
    modifier: Modifier,
    supporting: String? = null,
    supportingIsError: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { s ->
            {
                Text(
                    s,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (supportingIsError) Tokens.Error else Color.Unspecified,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = trailing?.let { t -> { Text(t, style = MaterialTheme.typography.bodyMedium) } },
        modifier = modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(
            containerColor = Tokens.SurfaceRaised,
            contentColor = Tokens.OnBackground,
            focusedContainerColor = Tokens.FocusFill,
            focusedContentColor = Tokens.OnFocus,
        ),
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}

/** OK flips the value; the trailing text shows "On" / "Off". */
@Composable
fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) =
    OptionItem(label, onOff(value), modifier, supporting, onClick = onToggle)

/** Values longer than this go under the label, so they don't squeeze it in the narrow panel. */
private const val TRAILING_MAX_CHARS = 14

/**
 * A row whose value is picked from a list; [valueText] is the current choice. A long value goes on
 * the second line, ahead of the optional [supporting] text.
 */
@Composable
fun ChoiceRow(label: String, valueText: String, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) =
    if (valueText.length > TRAILING_MAX_CHARS) {
        OptionItem(label, null, modifier, listOfNotNull(valueText, supporting).joinToString("\n"), onClick = onClick)
    } else {
        OptionItem(label, valueText, modifier, supporting, onClick = onClick)
    }

/** A plain action row with no trailing value; [supporting] is an optional second line. */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingIsError: Boolean = false,
    trailing: String? = null,
) = OptionItem(label, trailing, modifier, supporting, supportingIsError, onClick)

/** A row showing its value as the second line (long values such as a user agent). */
@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, valueIsError: Boolean = false) =
    OptionItem(label, null, modifier, value, valueIsError, onClick)

/** Retries [requester] for up to a second (rows may not be laid out on the first frame). */
internal suspend fun focusSoon(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        delay(50)
    }
}

/** List style: OK picks (focus starts on the selected entry); Back is handled by [ChoiceHost]. */
@Composable
fun ChoiceList(title: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        val start = options.indexOfFirst { it.second }.coerceAtLeast(0)
        runCatching { requesters.getOrNull(start)?.requestFocus() }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
        Spacer(Modifier.height(8.dp))
        options.forEachIndexed { i, (label, selected) ->
            OptionItem(label, if (selected) "✓" else null, Modifier.focusRequester(requesters[i])) { onPick(i) }
        }
    }
}

class ChoiceSpec(val title: String, val options: List<Pair<String, Boolean>>, val onPick: (Int) -> Unit)

/**
 * The open choice list of a page (a sub-state of the page), plus focus return to the row that
 * opened it. Compose [Effects] after the page-level BackHandler so Back closes the list first.
 */
class ChoiceHost {
    var spec by mutableStateOf<ChoiceSpec?>(null)
        private set
    private var returnKey by mutableStateOf<String?>(null)
    private val requesters = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
    fun open(key: String, spec: ChoiceSpec) { returnKey = key; this.spec = spec }
    fun close() { spec = null }

    @Composable
    fun Effects() {
        BackHandler(enabled = spec != null) { close() }
        val open = spec != null
        LaunchedEffect(open) {
            val key = returnKey
            if (!open && key != null) focusSoon(requester(key))
        }
    }
}

@Composable
fun rememberChoiceHost(): ChoiceHost = remember { ChoiceHost() }

/**
 * A [ChoiceRow] backed by this host; picking an entry applies [onPick] and closes the list. [key]
 * identifies the row for focus return: give per-playlist rows a key with the playlist id.
 */
@Composable
fun <T> ChoiceHost.Row(
    label: String,
    values: List<T>,
    current: T,
    text: (T) -> String,
    key: String = label,
    supporting: String? = null,
    onPick: (T) -> Unit,
) {
    ChoiceRow(
        label = label,
        valueText = text(current),
        modifier = Modifier.focusRequester(requester(key)),
        supporting = supporting,
        onClick = {
            open(key, ChoiceSpec(label, values.map { text(it) to (it == current) }) { i -> onPick(values[i]); close() })
        },
    )
}
