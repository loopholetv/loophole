package io.github.loopholetv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.toArg
import io.github.loopholetv.ui.common.OkPressTracker
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.theme.Tokens

private val HEADING_HEIGHT = 30.dp
private val GroupShape = RoundedCornerShape(ROW_HEIGHT / 2)

/**
 * Groups list. Focusing a group switches the channel list (debounced in the view model); OK switches
 * at once; holding OK (or Menu) on a playlist's group opens its options ([onLongPress]). The focused
 * item is a light pill; the selected group stays in the accent when focus is elsewhere. Entering the
 * column lands on the selected group ([selectedFocus]). Right enters the grid ([onRight]: the row
 * focused last, at the anchor time); Up from the first group goes to [upFromTop] (the preview). The
 * first row is Favourites, then All channels; with two or more playlists a muted heading (not a
 * focus stop) precedes each playlist's groups.
 *
 * Group Move mode ([movingScope] set): that group is outlined, Up/Down move it ([onMoveStep]), OK
 * finishes ([onMoveEnd]; Back is the screen's), and nothing else leaves it. [focusTarget] carries
 * [targetFocus] (scrolled into view), for the screen to put focus on a given group.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GroupsColumn(
    entries: List<GroupEntry>,
    selected: ChannelScope,
    onSelect: (ChannelScope) -> Unit,
    onFocusGroup: (ChannelScope) -> Unit,
    selectedFocus: FocusRequester,
    upFromTop: FocusRequester,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (GroupItem) -> Unit = {},
    movingScope: ChannelScope? = null,
    onMoveStep: (Int) -> Unit = {},
    onMoveEnd: () -> Unit = {},
    focusTarget: ChannelScope? = null,
    targetFocus: FocusRequester = remember { FocusRequester() },
) {
    val selectedRequester = selectedFocus
    val listState = rememberLazyListState()
    val moving by rememberUpdatedState(movingScope)
    val moveOk = remember { OkPressTracker() }

    // The target group (a moved one as the list reorders, or where focus goes before a group is
    // hidden) is kept in view, so its requester is attached.
    val target = focusTarget ?: movingScope
    val targetIndex = if (target == null) -1 else entries.indexOfFirst { it is GroupEntry.Item && it.group.scope == target }
    LaunchedEffect(target, targetIndex) {
        if (targetIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val fully = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        when {
            fully.isEmpty() -> Unit
            targetIndex < fully.first().index -> listState.scrollToItem(targetIndex)
            targetIndex > fully.last().index -> listState.scrollToItem((targetIndex - fully.size + 1).coerceAtLeast(0))
        }
        // The moved group keeps focus as the list reorders under it.
        if (movingScope != null) targetFocus.tryFocus()
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .onPreviewKeyEvent { e ->
                if (moving != null) return@onPreviewKeyEvent moveModeKey(e, moveOk, onMoveStep, onMoveEnd)
                // Not focus search: the grid's nearest row is rarely the one focused last.
                if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) onRight()
                true
            }
            .focusRestorer(selectedRequester),
    ) {
        itemsIndexed(entries, key = { _, e -> e.key() }, contentType = { _, e -> e::class }) { index, entry ->
            when (entry) {
                is GroupEntry.Heading -> Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(HEADING_HEIGHT).padding(start = 14.dp, top = 12.dp),
                )
                is GroupEntry.Item -> {
                    val g = entry.group
                    GroupRowItem(
                        group = g,
                        selected = g.scope == selected,
                        moving = g.scope == movingScope,
                        onClick = { onSelect(g.scope) },
                        // No options on Favourites / All channels: a held OK selects, as a press does.
                        onLongPress = if (hasGroupOptions(g)) ({ onLongPress(g) }) else ({ onSelect(g.scope) }),
                        onFocused = { onFocusGroup(g.scope) },
                        modifier = Modifier
                            .then(if (g.scope == selected) Modifier.focusRequester(selectedRequester) else Modifier)
                            .then(if (g.scope == target) Modifier.focusRequester(targetFocus) else Modifier)
                            .then(if (index == 0) Modifier.focusProperties { up = upFromTop } else Modifier),
                    )
                }
            }
        }
    }
}

private fun GroupEntry.key(): String = when (this) {
    is GroupEntry.Heading -> "h:$playlistId"
    is GroupEntry.Item -> group.scope.toArg()
}

@Composable
private fun GroupRowItem(
    group: GroupItem,
    selected: Boolean,
    moving: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onFocused: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text = when {
        focused -> Tokens.OnFocus
        selected -> Tokens.Accent
        else -> Tokens.OnBackground
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .onFocusChanged { if (it.isFocused) onFocused() }
            // OK clicks on release; held OK (or Menu) opens the group's options.
            .okPress(onClick = onClick, onLongClick = onLongPress)
            .focusable(interactionSource = interaction)
            .background(if (focused) Tokens.FocusFill else Color.Transparent, GroupShape)
            .then(if (moving) Modifier.border(2.dp, Tokens.Accent, GroupShape) else Modifier)
            .padding(horizontal = 14.dp),
    ) {
        Text(
            text = group.label,
            style = MaterialTheme.typography.bodyMedium,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = group.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Tokens.OnFocus else Tokens.Muted,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
