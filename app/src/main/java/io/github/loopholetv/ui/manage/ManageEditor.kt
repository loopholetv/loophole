package io.github.loopholetv.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.EditorChannelRow
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.HeldKeyGate
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.channelOptionsTitle
import io.github.loopholetv.ui.common.isOkKey
import io.github.loopholetv.ui.guide.moveStepFor
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

const val EDITOR_TITLE = "Groups and channels"
const val EDITOR_EMPTY = "No channels yet."

/**
 * Settings → Playlists → (playlist) → "Groups and channels", sized for the 35 % panel.
 * Page 1 lists every group (hidden ones too); OK opens a group page: its options, then all its
 * channels; OK on a channel opens its edit options. "Move group" / "Move" enter a move mode:
 * Up/Down move one place, OK or Back finish. Back: group page → page 1 → [onBack].
 */
@Composable
fun GroupsAndChannelsEditor(
    playlistId: Long,
    onBack: () -> Unit,
    vm: ManageViewModel = viewModel(key = "manage:$playlistId", factory = ManageViewModel.factory(playlistId)),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val open by vm.openGroup.collectAsStateWithLifecycle()
    // The group to focus on page 1 (the one just left, or the one being moved).
    var returnTo by rememberSaveable { mutableStateOf<String?>(null) }
    var movingGroup by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = movingGroup == null) {
        val title = open
        if (title != null) { returnTo = title; vm.close() } else onBack()
    }
    BackHandler(enabled = movingGroup != null) { movingGroup = null }

    val title = open
    if (title == null) {
        GroupsPage(
            groups = groups,
            focusTitle = movingGroup ?: returnTo,
            movingGroup = movingGroup,
            onOpen = { vm.open(it) },
            onMove = { t, step -> vm.moveGroup(t, step) },
            onEndMove = { returnTo = movingGroup; movingGroup = null },
        )
    } else {
        val group = groups.firstOrNull { it.title == title }
        GroupPage(
            vm = vm,
            title = title,
            group = group,
            onMoveGroup = { returnTo = title; movingGroup = title; vm.close() },
        )
    }
}

@Composable
private fun GroupsPage(
    groups: List<EditorGroupItem>,
    focusTitle: String?,
    movingGroup: String?,
    onOpen: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onEndMove: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val target = remember { FocusRequester() }
    val gate = remember(movingGroup) { HeldKeyGate() }
    val list = rememberLazyListState()
    val currentGroups by rememberUpdatedState(groups)
    KeepMovingRowInView(list, movingGroup, target) { currentGroups.indexOfFirst { it.title == movingGroup } }
    // Focus lands on the remembered group once the list has it, else on the first row.
    val hasTarget = focusTitle != null && groups.any { it.title == focusTitle }
    LaunchedEffect(groups.isNotEmpty(), hasTarget) { if (groups.isNotEmpty()) focusSoon(if (hasTarget) target else first) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(EDITOR_TITLE, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
        if (groups.isEmpty()) Text(EDITOR_EMPTY, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().moveKeys(movingGroup != null, gate, onEndMove) { step ->
                movingGroup?.let { onMove(it, step) }
            },
        ) {
            itemsIndexed(groups, key = { _, g -> g.title }) { index, g ->
                val isTarget = g.title == focusTitle
                EditorRow(
                    headline = g.label,
                    trailing = g.tagText,
                    moving = g.title == movingGroup,
                    modifier = when {
                        isTarget -> Modifier.focusRequester(target)
                        index == 0 -> Modifier.focusRequester(first)
                        else -> Modifier
                    },
                    onClick = { if (movingGroup == null) onOpen(g.title) },
                )
            }
        }
    }
}

@Composable
private fun GroupPage(vm: ManageViewModel, title: String, group: EditorGroupItem?, onMoveGroup: () -> Unit) {
    val channels = vm.groupChannels.collectAsLazyPagingItems()
    val first = remember { FocusRequester() }
    val target = remember { FocusRequester() }
    var groupEdit by remember { mutableStateOf<GroupEdit?>(null) }
    var options by remember { mutableStateOf<EditorChannelRow?>(null) }
    var channelEdit by remember { mutableStateOf<Pair<ChannelEdit, ChannelEntity>?>(null) }
    var moving by remember { mutableStateOf<ChannelEntity?>(null) }
    // The channel row to return focus to after its dialog (or move) closes.
    var focusId by remember { mutableStateOf<Long?>(null) }
    val gate = remember(moving) { HeldKeyGate() }
    val list = rememberLazyListState()

    BackHandler(enabled = moving != null) { moving = null }
    LaunchedEffect(Unit) { focusSoon(first) }

    val sort = group?.sort ?: GroupSort.PLAYLIST
    val groupOptions = groupEditOptions(hidden = group?.hidden == true, sort = sort) { edit ->
        if (edit == GroupEdit.Move) onMoveGroup() else groupEdit = edit
    }
    val optionCount = groupOptions.size
    val movingId = moving?.id
    KeepMovingRowInView(list, movingId, target) {
        // The channel rows come after the group's option rows.
        channels.itemSnapshotList.indexOfFirst { it?.channel?.id == movingId }.let { if (it < 0) it else it + optionCount }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            group?.label ?: title,
            style = MaterialTheme.typography.titleLarge,
            color = Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        group?.tagText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted) }
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().moveKeys(moving != null, gate, onEnd = { moving = null }) { step ->
                moving?.let { vm.moveChannel(it, step) }
            },
        ) {
            items(groupOptions.size, key = { "option:$it" }) { i ->
                val option = groupOptions[i]
                EditorRow(
                    headline = option.label,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { if (moving == null) option.onClick() },
                )
            }
            items(count = channels.itemCount, key = channels.itemKey { it.channel.id }) { index ->
                val row = channels[index]?.takeIf { it.channel.groupTitle == title } ?: return@items
                val c = row.channel
                val isMoving = c.id == moving?.id
                if (moving == null && c.id == focusId) {
                    LaunchedEffect(index) {
                        focusSoon(target)
                        focusId = null
                    }
                }
                EditorRow(
                    headline = "${c.guideNumber}  ${c.displayName}",
                    trailing = row.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    moving = isMoving,
                    modifier = if (isMoving || c.id == focusId) Modifier.focusRequester(target) else Modifier,
                    onClick = { if (moving == null) options = row },
                )
            }
        }
    }

    options?.let { row ->
        val c = row.channel
        ChannelOptionsDialog(
            title = channelOptionsTitle(c),
            options = channelEditOptions(c, hidden = row.hidden, canMove = true) { edit ->
                options = null
                if (edit == ChannelEdit.Move) moving = c else channelEdit = edit to c
            },
            onDismiss = { options = null; focusId = c.id },
        )
    }
    channelEdit?.let { (edit, c) ->
        ChannelEditHost(edit, c, vm.actions, onDone = { channelEdit = null; focusId = c.id })
    }
    groupEdit?.let { edit ->
        GroupEditHost(
            edit = edit,
            playlistId = vm.playlistId,
            title = title,
            label = group?.label ?: title,
            sort = sort,
            actions = vm.actions,
            onDone = { groupEdit = null },
        )
    }
}

/** Move mode keys on a list: Up/Down step (no key repeats), OK on a fresh press ends; nothing leaves. */
private fun Modifier.moveKeys(active: Boolean, gate: HeldKeyGate, onEnd: () -> Unit, onStep: (Int) -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        if (!active) return@onPreviewKeyEvent false
        val phase = when (e.type) {
            KeyEventType.KeyDown -> KeyPhase.Down
            KeyEventType.KeyUp -> KeyPhase.Up
            else -> return@onPreviewKeyEvent true
        }
        val code = e.key.nativeKeyCode
        when {
            e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                val step = moveStepFor(
                    up = e.key == Key.DirectionUp, down = e.key == Key.DirectionDown,
                    keyDown = phase == KeyPhase.Down, repeatCount = e.nativeKeyEvent.repeatCount,
                )
                if (step != 0) onStep(step)
                true
            }
            e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> true
            isOkKey(code) -> {
                // Only a press that started in move mode ends it (not the rest of the one that chose "Move").
                if (gate.allows(phase, true, e.nativeKeyEvent.repeatCount) && phase == KeyPhase.Up) onEnd()
                true
            }
            else -> false
        }
    }

/**
 * Move mode: whenever the moved row's index ([indexOf], -1 while not loaded) changes, scroll just
 * enough to keep it fully visible (as the guide does), then refocus it through [target].
 */
@Composable
private fun KeepMovingRowInView(list: LazyListState, movingKey: Any?, target: FocusRequester, indexOf: () -> Int) {
    val index by rememberUpdatedState(indexOf)
    LaunchedEffect(movingKey) {
        if (movingKey == null) return@LaunchedEffect
        snapshotFlow { index() }
            .filter { it >= 0 }
            .distinctUntilChanged()
            .collect { i ->
                val info = list.layoutInfo
                val fully = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
                keepInViewScrollTarget(i, fully.firstOrNull()?.index, fully.lastOrNull()?.index)?.let { list.scrollToItem(it) }
                focusSoon(target)
            }
    }
}

/** Requests focus, retrying for a few frames while the row is being laid out. */
private suspend fun focusSoon(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        delay(50)
    }
}

@Composable
private fun EditorRow(
    headline: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    moving: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        selected = moving,
        onClick = onClick,
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = trailing?.let { t -> { Text(t, style = MaterialTheme.typography.bodyMedium) } },
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
        colors = ListItemDefaults.colors(
            containerColor = Tokens.SurfaceRaised,
            contentColor = Tokens.OnBackground,
            focusedContainerColor = Tokens.FocusFill,
            focusedContentColor = Tokens.OnFocus,
            selectedContainerColor = Tokens.Accent,
            selectedContentColor = Tokens.OnFocus,
            focusedSelectedContainerColor = Tokens.AccentLight,
            focusedSelectedContentColor = Tokens.OnFocus,
        ),
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}
