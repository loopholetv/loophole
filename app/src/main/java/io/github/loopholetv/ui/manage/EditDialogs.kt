package io.github.loopholetv.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.DialogCard
import io.github.loopholetv.ui.common.DialogTitle
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.common.TypingField
import io.github.loopholetv.ui.common.rememberTypingState
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens

/** Rows shown before a [ChoiceDialog] or picker list scrolls (keeps the card on a 1080p screen). */
private val LIST_MAX_HEIGHT = 420.dp

/** Longest name typed into a rename field. */
const val MAX_NAME_LENGTH = 200

/**
 * One text value in its own dialog window: the field (read-only until OK opens the keyboard), then
 * "Save" and "Cancel". IME Done also saves. Back or "Cancel" calls [onDismiss]. Typing stops at
 * [maxLength]; while [accept] rejects the text, "Save" is disabled and Done only closes the
 * keyboard. [onSave] runs once (a second OK during the write is ignored).
 */
@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    accept: (String) -> Boolean = { true },
    maxLength: Int = MAX_NAME_LENGTH,
) {
    var value by rememberSaveable { mutableStateOf(initial.take(maxLength)) }
    var saving by remember { mutableStateOf(false) }
    val typing = rememberTypingState()
    val field = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val cancelButton = remember { FocusRequester() }
    RequestFocusOnOpen(field)
    val valid = accept(value)
    val save = {
        typing.stop()
        if (valid && !saving) { saving = true; onSave(value) }
    }
    DialogCard(onDismiss = { typing.stop(); onDismiss() }, width = 560.dp) {
        DialogTitle(title)
        TypingField(
            state = typing,
            label = label,
            value = value,
            onChange = { value = it.take(maxLength) },
            // Down from the field goes to "Save" (focus search would pick the nearer "Cancel").
            modifier = Modifier
                .focusRequester(field)
                .focusProperties { down = if (valid) saveButton else cancelButton },
            keyboardType = keyboardType,
            onDone = save,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                "Save", selected = false, enabled = valid, containerColor = Tokens.Surface,
                modifier = Modifier.focusRequester(saveButton), onFocus = typing::stop, onClick = save,
            )
            PillButton(
                "Cancel", selected = false, containerColor = Tokens.Surface,
                modifier = Modifier.focusRequester(cancelButton), onFocus = typing::stop,
            ) {
                onDismiss()
            }
        }
    }
}

/** Pick one of [options] in its own dialog window; focus starts on [selected] (marked ✓). */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    RequestFocusOnOpen(focus)
    var picked by remember { mutableStateOf(false) }
    DialogCard(onDismiss = onDismiss) {
        DialogTitle(title)
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
        ) {
            itemsIndexed(options) { i, label ->
                PickRow(
                    headline = label,
                    trailing = if (i == selected) "✓" else null,
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
                    onClick = { if (!picked) { picked = true; onPick(i) } },
                )
            }
        }
    }
}

/** A focusable row of a dialog list, with an optional second line and trailing mark. */
@Composable
internal fun PickRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = supporting?.let { s ->
            { Text(s, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = trailing?.let { t -> { Text(t, style = MaterialTheme.typography.bodyMedium) } },
        modifier = modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(
            containerColor = Tokens.Surface,
            contentColor = Tokens.OnBackground,
            focusedContainerColor = Tokens.FocusFill,
            focusedContentColor = Tokens.OnFocus,
            selectedContainerColor = Tokens.Accent,
            selectedContentColor = Tokens.OnFocus,
        ),
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}
