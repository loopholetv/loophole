package io.github.loopholetv.ui.guide

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.loopholetv.update.UpdateAction
import io.github.loopholetv.update.UpdateStatus
import io.github.loopholetv.update.backActionFor
import io.github.loopholetv.update.cardFor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.toArg
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.OPTION_ADD_FAVOURITE
import io.github.loopholetv.ui.common.OPTION_MOVE
import io.github.loopholetv.ui.common.OPTION_REMOVE_FAVOURITE
import io.github.loopholetv.ui.common.OkPressTracker
import io.github.loopholetv.ui.common.channelOptionsTitle
import io.github.loopholetv.ui.manage.ChannelEdit
import io.github.loopholetv.ui.manage.ChannelEditHost
import io.github.loopholetv.ui.manage.GroupEdit
import io.github.loopholetv.ui.manage.GroupEditHost
import io.github.loopholetv.ui.manage.channelEditOptions
import io.github.loopholetv.ui.manage.rememberChannelPlayback
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.ui.manage.groupEditOptions
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.paging.PagingData
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

private const val CLOCK_TICK_MS = 15_000L

/** The groups column with its 8 dp gap to the grid (animated as one width). */
private val GROUPS_WIDTH = 178.dp
private const val GROUPS_SLIDE_MS = 150

/** Home screen: rail, live preview + details on top, groups → channels → timeline below. */
@Composable
fun GuideScreen(
    onPlay: (channelId: Long, scope: ChannelScope) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    vm: GuideViewModel = viewModel(factory = GuideViewModel.Factory),
    /** OK on a finished programme: its archive is playing from [from]; open it full screen. */
    onPlayArchive: (channelId: Long, scope: ChannelScope, from: Instant) -> Unit = { _, _, _ -> },
    /** The side menu's TVs: the other TVs on the home network. */
    onTvs: () -> Unit = {},
    /** The side menu's Movies and Series. */
    onMovies: () -> Unit = {},
    onSeries: () -> Unit = {},
    /** "Copy from another TV" under the no-playlist hint: the TV picker. */
    onCopyFromAnotherTv: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LoopholeApp).container }
    val playback = container.playback
    val subtitleStyle by remember(container) {
        container.settingsStore.settings.map { it.playback.subtitleStyle }.distinctUntilChanged().catch { }
    }.collectAsStateWithLifecycle(SubtitleStyle())
    val format = rememberGuideFormat()

    val railItems by vm.railItems.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupEntries by vm.groupEntries.collectAsStateWithLifecycle()
    val allPlaylistsOff by vm.allPlaylistsOff.collectAsStateWithLifecycle()
    val noPlaylists by vm.noPlaylists.collectAsStateWithLifecycle()
    val moveKind by vm.moveKind.collectAsStateWithLifecycle()
    val movingGroup by vm.movingGroup.collectAsStateWithLifecycle()
    // One state for the scope and its list, so they always change in the same frame. Null only at
    // start, until the remembered group is known.
    val pages by vm.pages.collectAsStateWithLifecycle()
    val pendingScope by vm.selectedScope.collectAsStateWithLifecycle()
    val selectedScope = pages?.scope ?: pendingScope
    val window by vm.window.collectAsStateWithLifecycle()
    val pastWindow by vm.pastWindow.collectAsStateWithLifecycle()
    val slice by vm.slice.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    // A new LazyPagingItems per scope (keyed on the flow): it starts empty and Loading, so until the
    // new scope's first page arrives no grid focus request acts on the previous scope's rows, and
    // an empty list below is really this scope's. Before the start scope is known: a list that
    // never loads (empty and Loading, so no empty-list fallback either).
    val noPages = remember { emptyFlow<PagingData<ChannelEntity>>() }
    val channels = (pages?.flow ?: noPages).collectAsLazyPagingItems()
    // The selected list has finished loading and is empty (not just "not loaded yet").
    val listEmpty = channels.itemCount == 0 &&
        channels.loadState.refresh is LoadState.NotLoading &&
        channels.loadState.append.endOfPaginationReached
    // For callbacks remembered once (the grid's key actions).
    val currentScope by rememberUpdatedState(selectedScope)
    val now by produceState(Instant.now()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = Instant.now()
            vm.onTick()
        }
    }

    // Returning from the background (where MainActivity stopped playback) resumes the previewing channel.
    LifecycleStartEffect(vm) {
        vm.onScreenStarted()
        onStopOrDispose { }
    }

    var details by remember { mutableStateOf<ProgrammeEntity?>(null) }
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val selectedGroupFocus = remember { FocusRequester() }
    // The "Copy from another TV" button under the no-playlist hint.
    val copyFromFocus = remember { FocusRequester() }
    val previewFocus = remember { FocusRequester() }
    // Where a grid focus request lands: this channel's row strip if it is on screen, else the first
    // visible row's (see GuideGrid). Channel cells are not focus stops: every grid focus path lands
    // on a strip. Starts on the row focused before going full screen (the view model outlives this
    // composition).
    var focusTarget by remember { mutableStateOf(vm.focused.value?.channel?.id) }
    // Starts true: the first composition places focus in the grid; unless the player zapped to
    // another channel, in which case the refocus below scrolls to that channel first.
    var gridFocusPending by remember { mutableStateOf(vm.returnChannelId() == null) }
    var shownGroup by rememberSaveable { mutableStateOf(selectedScope.toArg()) }
    var refocusAfterGroupSwitch by remember { mutableStateOf(false) }
    // Where focus was last (set as the grid, groups, preview or rail gain it). A row leaving the
    // list with focus loses it without moving it anywhere.
    var focusArea by remember { mutableStateOf(GuideArea.None) }
    val focusInGrid = focusArea == GuideArea.Grid
    // The groups column collapses completely while focus is in the grid; see groupsShownFor.
    var groupsShown by remember { mutableStateOf(false) }
    fun focusMovedTo(area: GuideArea) {
        focusArea = area
        groupsShown = groupsShownFor(area, groupsShown)
    }
    val railFocus = remember { FocusRequester() }
    val timeAnchor = remember { mutableStateOf<Instant?>(null) }

    /** Moves focus into the grid on the row focused last (or the first visible one), at the anchor time. */
    fun focusGrid() {
        focusTarget = vm.focused.value?.channel?.id
        gridFocusPending = true
    }

    LaunchedEffect(selectedScope) {
        if (selectedScope.toArg() != shownGroup) {
            shownGroup = selectedScope.toArg()
            listState.scrollToItem(0)
            // The group was committed as focus entered the grid: that cell belonged to the old list.
            if (refocusAfterGroupSwitch) {
                refocusAfterGroupSwitch = false
                focusTarget = null
                gridFocusPending = true
            }
        }
    }
    LaunchedEffect(gridFocusPending, channels, channels.itemCount > 0, listEmpty, noPlaylists) {
        if (!gridFocusPending) return@LaunchedEffect
        if (channels.itemCount == 0) {
            // An empty list (e.g. no favourites yet) has nothing to land on: the selected group,
            // or with no playlist at all the "Copy from another TV" button, so OK works at once.
            if (listEmpty) {
                for (attempt in 0 until 20) {
                    if (if (noPlaylists) copyFromFocus.tryFocus() else selectedGroupFocus.tryFocus()) break
                    delay(50)
                }
                gridFocusPending = false
            }
            return@LaunchedEffect
        }
        // The row focus lands on is shown whole (the first visible one can be cut off at the top).
        val visible = listState.layoutInfo.visibleItemsInfo
        val landing = visible.firstOrNull { focusTarget != null && it.key == focusTarget }
            ?: visible.firstOrNull { it.index == listState.firstVisibleItemIndex }
        if (landing != null && landing.offset < 0) listState.scrollToItem(landing.index)
        // The requester attaches once the list has laid out its rows (a frame or two). Every row has
        // a strip (blank while its programmes load), so the request always has a target.
        repeat(20) {
            if (gridFocus.tryFocus()) {
                gridFocusPending = false
                return@LaunchedEffect
            }
            delay(50)
        }
        // Nothing to land on in the grid: the selected group is always there.
        selectedGroupFocus.tryFocus()
        gridFocusPending = false
    }

    // The list emptied under a focused strip (e.g. the last favourite removed): the selected group.
    LaunchedEffect(channels, listEmpty) {
        if (!listEmpty || !focusInGrid) return@LaunchedEffect
        focusArea = GuideArea.None
        gridFocusPending = false
        for (attempt in 0 until 20) {
            if (selectedGroupFocus.tryFocus()) break
            delay(50)
        }
    }

    // Back from the player after zapping: focus the channel it ended on (scrolled into view when
    // loaded; a channel outside this group, reached by number, falls back to the first visible row).
    val refocus by vm.refocus.collectAsStateWithLifecycle()
    LaunchedEffect(refocus) {
        val id = refocus ?: return@LaunchedEffect
        vm.consumeRefocus()
        // A freshly composed list may not have its first page yet.
        withTimeoutOrNull(1_000) { snapshotFlow { channels.itemCount }.first { it > 0 } }
        val index = channels.itemSnapshotList.indexOfFirst { it?.id == id }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        focusTarget = id
        gridFocusPending = true
    }


    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        vm.consumeNotice()
    }

    // Move mode: the moved row stays in view and keeps focus as the list reorders under it.
    val movingId = moving?.id
    LaunchedEffect(movingId, channels) {
        val id = movingId ?: return@LaunchedEffect
        snapshotFlow { channels.itemSnapshotList.indexOfFirst { it?.id == id } }
            .filter { it >= 0 }
            .distinctUntilChanged()
            .collect { index ->
                val info = listState.layoutInfo
                val fully = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
                when {
                    fully.isEmpty() -> Unit
                    index < fully.first().index -> listState.scrollToItem(index)
                    index > fully.last().index -> listState.scrollToItem((index - fully.size + 1).coerceAtLeast(0))
                }
                focusTarget = id
                gridFocusPending = true
            }
    }
    val moveOk = remember { OkPressTracker() }

    // The guide buttons' requests: a page key scrolls the list a page with focus keeping its place
    // on screen; Search and Settings open as from the rail.
    val playArchive by rememberUpdatedState(onPlayArchive)
    val openSearch by rememberUpdatedState(onSearch)
    val openSettings by rememberUpdatedState(onSettings)
    val rows by rememberUpdatedState(channels)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is GuideEvent.PageRows -> {
                    val focusedId = vm.focused.value?.channel?.id
                    val focusedIndex = rows.itemSnapshotList.indexOfFirst { it != null && it.id == focusedId }
                    val target = pageTarget(listState.firstVisibleItemIndex, focusedIndex, rows.itemCount, event.rows)
                    if (target.focus < 0) return@collect
                    listState.scrollToItem(target.firstVisible)
                    focusTarget = rows.peek(target.focus)?.id
                    gridFocusPending = true
                }
                GuideEvent.OpenSearch -> openSearch()
                GuideEvent.OpenSettings -> openSettings()
            }
        }
    }

    // Hidden from the preview: once the channel has left the list, focus moves from the
    // emptied preview to the first row (or the selected group when the list is empty).
    var focusAfterPreviewHide by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(focusAfterPreviewHide) {
        val hiddenId = focusAfterPreviewHide ?: return@LaunchedEffect
        try {
            withTimeoutOrNull(1_500) { snapshotFlow { channels.itemSnapshotList.none { it?.id == hiddenId } }.first { it } }
            if (channels.itemCount > 0) listState.scrollToItem(0)
            focusTarget = channels.itemSnapshotList.firstOrNull { it != null && it.id != hiddenId }?.id
            gridFocusPending = true
        } finally {
            // Cleared last: resetting the key earlier would cancel this effect mid-way.
            if (focusAfterPreviewHide == hiddenId) focusAfterPreviewHide = null
        }
    }

    // The options panel (hold OK or Menu on a row, or on the preview).
    var options by remember { mutableStateOf<OptionsTarget?>(null) }

    /** Focus goes back to [channelId]'s row once the panel has closed (null: leave it where it is). */
    fun closeOptions(channelId: Long?) {
        options = null
        if (channelId != null) {
            focusTarget = channelId
            gridFocusPending = true
        }
    }

    // A row leaving the list (a favourite removed from Favourites, a channel hidden): focus moves
    // off it first (to a neighbouring row, or to the selected group when it is the only one) and the
    // removal waits for that (a focused row removed from the list drops focus to the rail).
    var removeAfterFocus by remember { mutableStateOf<RowRemoval?>(null) }
    var removeAfterGroupFocus by remember { mutableStateOf<RowRemoval?>(null) }
    LaunchedEffect(removeAfterFocus) {
        val removal = removeAfterFocus ?: return@LaunchedEffect
        withTimeoutOrNull(1_500) { snapshotFlow { gridFocusPending }.first { !it } }
        removeAfterFocus = null
        removal.remove()
    }
    LaunchedEffect(removeAfterGroupFocus) {
        val removal = removeAfterGroupFocus ?: return@LaunchedEffect
        // The options dialog is closing: the group takes focus once its window has gone.
        for (attempt in 0 until 20) {
            if (selectedGroupFocus.tryFocus()) break
            delay(50)
        }
        removeAfterGroupFocus = null
        removal.remove()
    }

    /** Takes [channel]'s row out of the list with [remove], moving focus off it first. */
    fun removeRow(channel: ChannelEntity, remove: () -> Unit) {
        val removal = RowRemoval(channel.id, remove)
        when (val to = removalFocusFor(channels.itemSnapshotList.map { it?.id }, channel.id)) {
            is RemovalFocus.Row -> { closeOptions(to.channelId); removeAfterFocus = removal }
            // The only row: focus to the selected group first, then remove.
            RemovalFocus.Group -> { closeOptions(null); removeAfterGroupFocus = removal }
            RemovalFocus.Stay -> { remove(); closeOptions(channel.id) }
        }
    }

    // Focus onto a given group (scrolled into view first), then [GroupFocus.then]: before a group is
    // hidden, and onto a group entering Move mode.
    val groupTargetFocus = remember { FocusRequester() }
    var groupFocus by remember { mutableStateOf<GroupFocus?>(null) }
    // The group holding focus (null outside the column). Entering the column from outside (the grid,
    // a dialog) lands on the selected group (its focus restorer), so the request repeats until the
    // target itself has focus: the second one starts inside the column and isn't redirected.
    var focusedGroup by remember { mutableStateOf<ChannelScope?>(null) }
    LaunchedEffect(groupFocus) {
        val request = groupFocus ?: return@LaunchedEffect
        for (attempt in 0 until 20) {
            if (groupTargetFocus.tryFocus() && focusedGroup == request.scope) break
            delay(50)
        }
        groupFocus = null
        // Couldn't land: don't leave the column shown over a focused grid.
        if (focusedGroup != request.scope) groupsShown = groupsShownFor(focusArea, groupsShown)
        request.then()
    }

    /** Shows the groups column with focus on the current group (scrolled into view). */
    fun focusSelectedGroup() {
        groupsShown = true
        groupFocus = GroupFocus(currentScope) {}
    }

    /** An empty list: the "Copy from another TV" button when there is no playlist, else the selected group. */
    fun focusEmptyList() {
        if (noPlaylists && copyFromFocus.tryFocus()) return
        focusSelectedGroup()
    }

    // Back: a window shifted away from now (past or future) snaps back to now first (focus staying
    // where it is); then programme → groups list (on the current group) → side rail → the app's
    // confirm-exit.
    val windowShifted by vm.windowShifted.collectAsStateWithLifecycle()
    val back = guideBackFor(focusArea, windowShifted)
    BackHandler(enabled = back != GuideBack.Exit) {
        when (back) {
            GuideBack.SnapToNow -> {
                vm.resetWindow()
                timeAnchor.value = null
                // Focus elsewhere (groups, preview, rail) stays there.
                if (focusInGrid) focusGrid()
            }
            GuideBack.ShowGroups -> focusSelectedGroup()
            GuideBack.OpenRail -> railFocus.tryFocus()
            GuideBack.Exit -> Unit
        }
    }
    // Composed after the one above, so in Move mode (a channel or a group) Back finishes the move first.
    BackHandler(enabled = moveKind != null) { vm.endMove() }

    // The update card (top-right of the programme details). Only while the guide is the screen in
    // front: a dialog destination over it (Settings) puts this entry below RESUMED.
    val updater = container.updater
    val updateStatus by updater.status.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val updateCard = cardFor(updateStatus).takeIf { lifecycle.isAtLeast(Lifecycle.State.RESUMED) }
    val cardFocus = remember { FocusRequester() }
    var cardHasFocus by remember { mutableStateOf(false) }

    /** Focus back to where it was in the guide before the card took it. */
    fun leaveCard() {
        when (focusArea) {
            GuideArea.Preview -> if (!previewFocus.tryFocus()) focusGrid()
            GuideArea.Rail -> if (!railFocus.tryFocus()) focusGrid()
            GuideArea.Groups -> focusSelectedGroup()
            GuideArea.Grid, GuideArea.None -> if (channels.itemCount > 0) focusGrid() else focusEmptyList()
        }
    }

    fun onCardAction(action: UpdateAction) {
        updater.perform(action)
        if (action == UpdateAction.LATER || action == UpdateAction.CLOSE) leaveCard()
    }
    val cardShown = updateCard != null
    // The first time the card appears in this process, focus moves to its first button (once the
    // guide has placed its own start focus).
    LaunchedEffect(cardShown) {
        if (!cardShown) return@LaunchedEffect
        withTimeoutOrNull(3_000) { snapshotFlow { gridFocusPending }.first { !it } }
        if (!updater.claimFirstFocus()) return@LaunchedEffect
        repeat(20) {
            if (cardFocus.tryFocus()) return@LaunchedEffect
            delay(50)
        }
    }
    // Composed last: while the card has focus, Back means Later (or just leaves a running download).
    BackHandler(enabled = cardHasFocus && updateCard != null) {
        backActionFor(updateStatus)?.let(updater::perform)
        leaveCard()
    }

    // The cast banner beside the update card while this TV casts; Up from the first row reaches it.
    val casting by vm.casting.collectAsStateWithLifecycle()
    val castFocus = remember { FocusRequester() }
    var castHasFocus by remember { mutableStateOf(false) }
    // The cast ended (here or from another screen) while the banner had focus: back where it was.
    LaunchedEffect(casting) {
        if (!casting && castHasFocus) {
            castHasFocus = false
            leaveCard()
        }
    }
    BackHandler(enabled = castHasFocus && casting) { leaveCard() }

    // The edit dialog open after an option was picked (rename, logo, TV guide, time shift, sort...).
    var channelEdit by remember { mutableStateOf<PendingChannelEdit?>(null) }
    var groupOptions by remember { mutableStateOf<GroupItem?>(null) }
    var groupEdit by remember { mutableStateOf<Pair<GroupEdit, GroupItem>?>(null) }

    val actions = remember(vm) {
        GridActions(
            onFocus = { channel: ChannelEntity, p: ProgrammeEntity? ->
                val before = vm.selectedScope.value
                vm.onFocus(channel, p)
                if (vm.selectedScope.value != before) refocusAfterGroupSwitch = true
            },
            onOk = { channel: ChannelEntity, p: ProgrammeEntity? ->
                when (val action = vm.onOk(channel, p)) {
                    OkAction.Previewed -> Unit
                    // Once: a quick second OK while the player opens is ignored.
                    is OkAction.OpenPlayer -> vm.claimPlayer(action)?.let { onPlay(it, vm.selectedScope.value) }
                    is OkAction.ShowDetails -> details = action.programme
                    is OkAction.PlayArchive ->
                        vm.claimArchive(action)?.let { playArchive(it.channelId, vm.selectedScope.value, it.from) }
                }
            },
            onLongPress = { channel: ChannelEntity -> options = OptionsTarget(channel, fromPreview = false) },
            onVisibleIds = vm::setVisibleKeys,
            keys = StripKeys(
                shift = vm::shiftWindow,
                // Left at the row's limit ("now" without catch-up): the groups column, shown, on
                // the selected group (scrolled into view).
                exitLeft = { focusSelectedGroup(); true },
            ),
        )
    }

    /** OK on a group: switch to it (at once) and go into its list. */
    fun selectGroupAndEnter(scope: ChannelScope) {
        val before = vm.selectedScope.value
        vm.selectGroup(scope)
        // An empty list keeps focus on the group (see the grid-focus effect).
        if (vm.selectedScope.value != before) refocusAfterGroupSwitch = true else if (channels.itemCount > 0) focusGrid()
    }

    /** Right from the groups column: into the grid on the row focused last, at the anchor time. */
    fun enterGridFromGroups() {
        val before = vm.selectedScope.value
        vm.commitPendingGroup()
        // A group switch replaces the list: the refocus waits for it (see LaunchedEffect(selectedScope)).
        // An empty list keeps focus on the group (see the grid-focus effect).
        if (vm.selectedScope.value != before) refocusAfterGroupSwitch = true else if (channels.itemCount > 0) focusGrid()
    }

    fun openPreview() {
        // Null: nothing previewing, a failed preview retrying in place, or the player already opening.
        vm.claimPlayer(vm.onPreviewOk())?.let { onPlay(it, vm.selectedScope.value) }
    }

    /** Hold OK on a playlist's group: its options. */
    fun openGroupOptions(group: GroupItem) {
        if (moveKind == null) groupOptions = group
    }

    /** Hides [group]: focus moves to a neighbouring group first (the hidden one leaves the column). */
    fun hideGroup(group: GroupItem) {
        val next = groupFocusAfterHiding(groups, group)
        if (next == null) {
            vm.hideGroup(group)
        } else {
            groupFocus = GroupFocus(next.scope) { vm.hideGroup(group) }
        }
    }

    val moveTitle = movingGroup?.label ?: moving?.let(::channelLabel)

    /**
     * Page, day and the other remappable buttons, wherever focus is (a row, the preview, the
     * groups, the rail). They act on the previewing
     * channel from the preview, else on the row the info area describes. A day jump (or back to
     * now) keeps the focus at the same time of day on the new window. Not in Move mode.
     */
    fun onGuideButton(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || moveKind != null) return false
        val row = if (focusArea == GuideArea.Preview) vm.previewing.value else vm.focused.value?.channel
        val before = vm.window.value
        val used = vm.onGuideKey(e.key.nativeKeyCode, row, repeat = e.nativeKeyEvent.repeatCount > 0)
        val after = vm.window.value
        if (after != before) timeAnchor.value = anchorAfterJump(timeAnchor.value, vm.now(), before, after)
        return used
    }

    // No background here: the NavHost already paints it (avoids a full-screen overdraw layer).
    // The preview takes what is left once exactly 8 rows fit below the header: at 1080p
    // (540 dp tall) 12 + 141 preview + 8 + 22 group name + 28 header + 8 × 37 rows (3 apart) + 12.
    // Read in the layout phase only (below): the slide relayouts, it doesn't recompose the screen.
    val groupsWidth = animateDpAsState(
        targetValue = if (groupsShown) GROUPS_WIDTH else 0.dp,
        animationSpec = tween(GROUPS_SLIDE_MS),
        label = "groupsWidth",
    )
    val groupName = groups.firstOrNull { it.scope == selectedScope }?.label.orEmpty()
    // Seen before the focused element: the list itself would otherwise scroll on Page Up/Down
    // without moving focus. Bound buttons are never arrows, OK, Back or Menu.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent(::onGuideButton)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = RAIL_WIDTH + 8.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)) {
            val previewHeight = guidePreviewHeight(maxHeight)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(previewHeight), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PreviewPane(
                        playback = playback,
                        previewing = previewing,
                        focusRequester = previewFocus,
                        onOk = ::openPreview,
                        onLongPress = { channel -> options = OptionsTarget(channel, fromPreview = true) },
                        subtitleStyle = subtitleStyle,
                        rightTo = castFocus.takeIf { casting } ?: cardFocus.takeIf { updateCard?.buttons?.isNotEmpty() == true },
                        onDown = { if (channels.itemCount > 0) focusGrid() else focusEmptyList() },
                        modifier = Modifier.fillMaxHeight().onFocusChanged { if (it.hasFocus) focusMovedTo(GuideArea.Preview) },
                    )
                    // The cast banner and the update card stack at the top end, inside the details
                    // (not a row of their own: the grid keeps its 8 rows); the title ends before them.
                    Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProgrammeInfo(focused, now, format, Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp), moving = moveTitle)
                        if (casting || updateCard != null) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (casting) {
                                    CastBanner(
                                        focus = castFocus,
                                        onStop = {
                                            castHasFocus = false
                                            vm.stopCasting()
                                            leaveCard()
                                        },
                                        onDown = {
                                            if (updateCard?.buttons?.isNotEmpty() != true || !cardFocus.tryFocus()) leaveCard()
                                        },
                                        onLeft = { if (!previewFocus.tryFocus()) leaveCard() },
                                        onFocusChange = { castHasFocus = it },
                                    )
                                }
                                updateCard?.let { card ->
                                    UpdateCard(
                                        card = card,
                                        progress = (updateStatus as? UpdateStatus.Downloading)?.percent,
                                        primaryFocus = cardFocus,
                                        onAction = ::onCardAction,
                                        onLeave = ::leaveCard,
                                        onFocusChange = { cardHasFocus = it },
                                        banner = castFocus.takeIf { casting },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxSize().padding(top = PREVIEW_GAP)) {
                    // Always composed (zero wide while collapsed), so a focus request on a group always
                    // has its target; gaining focus shows it again.
                    GroupsColumn(
                        entries = groupEntries,
                        selected = selectedScope,
                        onSelect = ::selectGroupAndEnter,
                        onFocusGroup = { focusedGroup = it; vm.onGroupFocused(it) },
                        selectedFocus = selectedGroupFocus,
                        upFromTop = previewFocus,
                        onRight = ::enterGridFromGroups,
                        onLongPress = ::openGroupOptions,
                        movingScope = movingGroup?.scope,
                        onMoveStep = vm::moveBy,
                        onMoveEnd = vm::endMove,
                        focusTarget = groupFocus?.scope,
                        targetFocus = groupTargetFocus,
                        modifier = Modifier
                            .layout { measurable, constraints ->
                                val width = groupsWidth.value.roundToPx().coerceIn(0, constraints.maxWidth)
                                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                                layout(width, placeable.height) { placeable.place(0, 0) }
                            }
                            .fillMaxHeight().clipToBounds()
                            .padding(top = GROUP_NAME_HEIGHT + HEADER_HEIGHT, end = 8.dp)
                            .onFocusChanged {
                                if (!it.hasFocus) focusedGroup = null
                                if (it.hasFocus) focusMovedTo(GuideArea.Groups)
                            },
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // Where the user is, above the channel list (the column itself may be hidden).
                        Text(
                            text = groupName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Tokens.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // At least its line; a larger font scale grows it (rather than clipping the name).
                            modifier = Modifier.fillMaxWidth().heightIn(min = GROUP_NAME_HEIGHT).padding(start = 8.dp, top = 1.dp),
                        )
                        GuideGrid(
                            channels = channels,
                            slice = slice,
                            window = window,
                            now = now,
                            format = format,
                            listState = listState,
                            focusRequester = gridFocus,
                            focusTargetId = focusTarget,
                            focusPending = gridFocusPending,
                            anchor = timeAnchor,
                            upFromTop = if (casting) castFocus else previewFocus,
                            actions = actions,
                            favourites = favourites,
                            movingId = movingId,
                            pastWindow = pastWindow,
                            emptyHint = emptyHintFor(allPlaylistsOff, selectedScope, listEmpty, noPlaylists),
                            emptyButtonFocus = copyFromFocus,
                            onCopyFromAnotherTv = onCopyFromAnotherTv,
                            modifier = Modifier.fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusMovedTo(GuideArea.Grid) }
                                // Move mode: Up/Down move the row, OK finishes; nothing else leaves the row.
                                .onPreviewKeyEvent { e ->
                                    if (moving == null) return@onPreviewKeyEvent false
                                    moveModeKey(e, moveOk, vm::moveBy, vm::endMove)
                                },
                        )
                    }
                }
            }
        }
        SideRail(
            items = railItems,
            onFocused = vm::commitPendingGroup,
            // Right from any rail item enters the grid like TV does (never focus search to the preview).
            onRight = { if (channels.itemCount > 0) focusGrid() else focusEmptyList() },
            onSelect = { item ->
                when (item) {
                    // Lands on the row focused last if visible, else the first visible row, else
                    // (empty list) on the selected group.
                    RailItem.TV -> if (channels.itemCount > 0) focusGrid() else focusEmptyList()
                    RailItem.SETTINGS -> onSettings()
                    RailItem.SEARCH -> onSearch()
                    RailItem.TVS -> onTvs()
                    RailItem.MOVIES -> onMovies()
                    RailItem.SERIES -> onSeries()
                    else -> Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                }
            },
            entryFocus = railFocus,
            modifier = Modifier.onFocusChanged { if (it.hasFocus) focusMovedTo(GuideArea.Rail) },
        )
    }

    details?.let { p -> ProgrammeDetailsDialog(p, format, onClose = { details = null }) }

    options?.let { target ->
        val channel = target.channel
        // From the preview focus simply returns there; from a row, back onto that row.
        val back = if (target.fromPreview) null else channel.id
        val isFavourite = channel.ref in favourites
        val list = buildList {
            add(
                ChannelOption(if (isFavourite) OPTION_REMOVE_FAVOURITE else OPTION_ADD_FAVOURITE) {
                    val removesRow = isFavourite && selectedScope == ChannelScope.Favourites && !target.fromPreview
                    if (removesRow) {
                        removeRow(channel) { vm.toggleFavourite(channel) }
                    } else {
                        vm.toggleFavourite(channel)
                        closeOptions(back)
                    }
                },
            )
            // Reordering is for the Favourites list, from its rows.
            if (isFavourite && selectedScope == ChannelScope.Favourites && !target.fromPreview) {
                add(
                    ChannelOption(OPTION_MOVE) {
                        vm.startMove(channel)
                        closeOptions(channel.id)
                    },
                )
            }
            // Then the channel's edits. "Move" within a group's list, from its rows.
            val canMove = !target.fromPreview && vm.canMoveInGroup(channel)
            addAll(
                channelEditOptions(channel, hidden = false, canMove = canMove, playback = rememberChannelPlayback(channel) ?: ChannelPlaybackPrefs.NONE) { edit ->
                    when (edit) {
                        ChannelEdit.Move -> {
                            vm.startMove(channel)
                            closeOptions(channel.id)
                        }
                        // Its row leaves every list: focus moves off it first. From the preview
                        // focus stays there (the preview stops).
                        ChannelEdit.Hide -> if (target.fromPreview) {
                            vm.hideChannel(channel)
                            closeOptions(null)
                            // The preview stops and empties: focus goes to the list's first row.
                            focusAfterPreviewHide = channel.id
                        } else if (vm.isLastOfShownGroup(channel)) {
                            // The group leaves the column with its last channel: focus goes to a
                            // neighbouring group first (its list follows), as when hiding a group.
                            val shown = groups.firstOrNull { it.scope == selectedScope }
                            val next = shown?.let { groupFocusAfterHiding(groups, it) }
                                ?: groups.firstOrNull { it.scope == ChannelScope.All }
                            closeOptions(null)
                            if (next == null) {
                                vm.hideChannel(channel)
                            } else {
                                groupFocus = GroupFocus(next.scope) { vm.hideChannel(channel) }
                            }
                        } else {
                            removeRow(channel) { vm.hideChannel(channel) }
                        }
                        else -> {
                            options = null
                            channelEdit = PendingChannelEdit(edit, channel, back)
                        }
                    }
                },
            )
        }
        ChannelOptionsDialog(title = channelOptionsTitle(channel), options = list, onDismiss = { closeOptions(back) })
    }

    channelEdit?.let { pending ->
        ChannelEditHost(pending.edit, pending.channel, container.channelEdits, rememberChannelPlayback(pending.channel)) {
            channelEdit = null
            vm.onChannelEdited(pending.channel.id)
            closeOptions(pending.back)
        }
    }

    // The guide lists only shown groups, so the options offer "Hide group" (never "Show group").
    groupOptions?.let { group ->
        val list = groupEditOptions(hidden = false, sort = group.sortMode) { edit ->
            groupOptions = null
            when (edit) {
                GroupEdit.Move -> {
                    vm.startGroupMove(group)
                    groupFocus = GroupFocus(group.scope) {}
                }
                GroupEdit.Hide -> hideGroup(group)
                else -> groupEdit = edit to group
            }
        }
        ChannelOptionsDialog(title = group.label, options = list, onDismiss = { groupOptions = null })
    }

    groupEdit?.let { (edit, group) ->
        val scope = group.scope as ChannelScope.Group
        GroupEditHost(edit, scope.playlistId, scope.title, group.label, group.sortMode, container.channelEdits) {
            groupEdit = null
        }
    }
}

/** Whose options are open: a guide row's channel, or the previewing one. */
private data class OptionsTarget(val channel: ChannelEntity, val fromPreview: Boolean)

/** An edit dialog open for [channel]; focus goes back to row [back] (null: where it was) when it closes. */
private data class PendingChannelEdit(val edit: ChannelEdit, val channel: ChannelEntity, val back: Long?)

/** A row about to leave the list: [remove] runs once focus has moved off row [channelId]. */
private class RowRemoval(val channelId: Long, val remove: () -> Unit)

/** Focus onto group [scope], then [then] (also when focus couldn't land). */
private class GroupFocus(val scope: ChannelScope, val then: () -> Unit)

@Composable
private fun ProgrammeDetailsDialog(p: ProgrammeEntity, format: GuideFormat, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { close.tryFocus() }
    Dialog(onDismissRequest = onClose) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(520.dp)
                .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
                .padding(28.dp),
        ) {
            Text(p.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground)
            Text(format.range(p), style = MaterialTheme.typography.bodyMedium, color = Tokens.Accent)
            Text(
                text = p.description?.takeIf { it.isNotBlank() } ?: "No description",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Muted,
            )
            Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp).focusRequester(close)) { Text("Close") }
        }
    }
}

/** [FocusRequester.requestFocus]'s result; false (not a crash) when the target isn't attached yet. */
internal fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)
