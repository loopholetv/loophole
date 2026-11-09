package io.github.loopholetv.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.GroupRow
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.MAX_WINDOW_IDS
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.channelScopeFromArg
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.db.toArg
import io.github.loopholetv.data.repo.GuideKey
import io.github.loopholetv.data.repo.guideKey
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.ui.manage.EditActions
import io.github.loopholetv.ui.manage.asEditActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * A row of the groups column: Favourites, All channels, then each playlist's groups. [playlistName]
 * and [sortMode] are set for playlist groups only (headings and group options).
 */
data class GroupItem(
    val scope: ChannelScope,
    val label: String,
    val count: Int,
    val playlistName: String = "",
    val sortMode: GroupSort = GroupSort.PLAYLIST,
)

/** A line of the groups column: a playlist heading (display only) or a group. */
sealed interface GroupEntry {
    /** Precedes [playlistId]'s groups when the groups come from two or more playlists. */
    data class Heading(val playlistId: Long, val name: String) : GroupEntry

    data class Item(val group: GroupItem) : GroupEntry
}

private val GroupItem.playlistId: Long? get() = (scope as? ChannelScope.Group)?.playlistId

/**
 * The groups column's lines: [groups] as given, with a heading before each playlist's groups when
 * they come from two or more playlists. Follows the list order, never `GroupRow.order`. Each
 * playlist gets one heading at most, before its first group (the list keys headings by playlist,
 * so a list that interleaved playlists must not repeat one).
 */
fun withHeadings(groups: List<GroupItem>): List<GroupEntry> {
    val playlists = groups.mapNotNullTo(HashSet()) { it.playlistId }
    if (playlists.size < 2) return groups.map(GroupEntry::Item)
    val out = ArrayList<GroupEntry>(groups.size + playlists.size)
    val headed = HashSet<Long>()
    for (g in groups) {
        val id = g.playlistId
        if (id != null && headed.add(id)) out += GroupEntry.Heading(id, g.playlistName)
        out += GroupEntry.Item(g)
    }
    return out
}

/** [channel] when it is shown in the guide (not hidden, group not hidden, playlist on), else null. */
fun visibleOnly(channel: ChannelEntity?): ChannelEntity? = channel?.takeIf { it.visible }

/** A hide or move that couldn't be saved. */
const val NOTICE_EDIT_FAILED = "Couldn't save the change"

/** Only a playlist's groups have options (Favourites and All channels don't). */
fun hasGroupOptions(group: GroupItem): Boolean = group.scope is ChannelScope.Group

/** What Move mode reorders: favourites, a channel within its group, or a group. */
enum class MoveKind { FAVOURITE, GROUP_CHANNEL, GROUP }

/** "guide number  display name": how the preview and the info area name a channel. */
fun channelLabel(channel: ChannelEntity): String = "${channel.guideNumber}  ${channel.displayName}"

/** Shown in the grid area when playlists exist but every one is turned off. */
const val ALL_PLAYLISTS_OFF = "All playlists are turned off. Turn one on in Settings \u2192 Playlists."

/** The grid area's message for an empty list, or null. */
fun emptyHintFor(allPlaylistsOff: Boolean, scope: ChannelScope, listEmpty: Boolean): String? = when {
    allPlaylistsOff -> ALL_PLAYLISTS_OFF
    scope == ChannelScope.Favourites && listEmpty -> FAVOURITES_EMPTY_HINT
    else -> null
}

/** One scope's channel list ([flow] is cached for as long as [scope] stays selected). */
class ScopePages(val scope: ChannelScope, val flow: Flow<PagingData<ChannelEntity>>)

/** What OK on a guide slot does. */
sealed interface OkAction {
    data object Previewed : OkAction
    data class OpenPlayer(val channelId: Long) : OkAction
    data class ShowDetails(val programme: ProgrammeEntity) : OkAction
}

/** What the info area describes; [programme] null means no guide data for that slot. */
data class FocusedProgramme(val channel: ChannelEntity, val programme: ProgrammeEntity?)

/**
 * Programmes loaded for [keys] (the rows around the visible ones) over [range]; each key's list is
 * at its shown (time-shifted) times.
 */
data class ProgrammeSlice(
    val keys: Set<GuideKey>,
    val range: TimeWindow?,
    val byKey: Map<GuideKey, List<ProgrammeEntity>>,
) {
    companion object {
        val EMPTY = ProgrammeSlice(emptySet(), null, emptyMap())
    }
}

const val GROUP_FOCUS_DEBOUNCE_MS = 250L

/** How long the start waits for the groups list before giving up on the remembered group. */
private const val GROUPS_WAIT_MS = 2_000L

const val NOTICE_ADDED = "Added to favourites"
const val NOTICE_REMOVED = "Removed from favourites"
private const val NOTICE_FAILED = "Couldn't change favourites"

/** The channel loading or playing; null when idle or failed. */
private fun PlaybackState.playingChannelId(): Long? = when (this) {
    is PlaybackState.Buffering -> channelId
    is PlaybackState.Playing -> channelId
    else -> null
}

private fun PlaybackState.channelIdOrNull(): Long? = when (this) {
    is PlaybackState.Buffering -> channelId
    is PlaybackState.Playing -> channelId
    is PlaybackState.Error -> channelId
    PlaybackState.Idle -> null
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    groupRows: Flow<List<GroupRow>>,
    private val channelPages: (scope: ChannelScope) -> PagingSource<Int, ChannelEntity>,
    /** Programmes per guide key over a shown window, time-shifted (`ShiftedProgrammes.inWindow`). */
    private val programmes: (keys: Collection<GuideKey>, from: Instant, to: Instant) -> Flow<Map<GuideKey, List<ProgrammeEntity>>>,
    /** One-shot read of the end of the last guide programme (a full-table scan: never observe it). */
    private val latestStop: suspend () -> Instant?,
    private val playback: Playback,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The programme airing now on [channel] at its time shift (DB lookup), for when it is outside the loaded slice. */
    private val nowFor: suspend (channel: ChannelEntity) -> ProgrammeEntity? = { null },
    /** A visible channel by id (null when gone, hidden or its playlist is off). */
    private val channelById: suspend (id: Long) -> ChannelEntity? = { null },
    /** Favourites whose channel is present (the Favourites row's count). */
    favouriteCount: Flow<Int> = flowOf(0),
    /** Every stored favourite (the ★ marker and the options' Add/Remove). */
    favouriteRefs: Flow<Set<ChannelRef>> = emptyFlow(),
    /** Adds or removes a favourite; returns whether it is one now. */
    toggleFavourite: suspend (ChannelEntity) -> Boolean = { false },
    /** Moves a favourite one place (direction < 0: earlier); false at either end. */
    moveFavourite: suspend (ChannelEntity, Int) -> Boolean = { _, _ -> false },
    /** The group chosen last time. */
    private val initialScope: suspend () -> ChannelScope = { ChannelScope.All },
    /** Remembers the chosen group for the next start. */
    private val saveScope: suspend (ChannelScope) -> Unit = {},
    /** Hide and move edits; the options' other edits go through ChannelEditHost / GroupEditHost. */
    private val edits: EditActions = NoEdits,
    /** How many playlists are turned on. */
    playlistsOn: Flow<Int> = flowOf(1),
    /** Whether any playlist exists at all. */
    playlistsExist: Flow<Boolean> = flowOf(true),
) : ViewModel() {

    // Not properties named like the public functions below (a call would resolve to the function).
    private val toggleFavouriteOp = toggleFavourite
    private val moveFavouriteOp = moveFavourite

    val groups: StateFlow<List<GroupItem>> = combine(groupRows, favouriteCount) { rows, favourites ->
        listOf(
            GroupItem(ChannelScope.Favourites, ChannelScope.Favourites.label(), favourites),
            GroupItem(ChannelScope.All, ChannelScope.All.label(), rows.sumOf { it.count }),
        ) + rows.map {
            GroupItem(ChannelScope.Group(it.playlistId, it.title), it.label, it.count, it.playlistName, it.sortMode)
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** [groups] with the playlist headings. */
    val groupEntries: StateFlow<List<GroupEntry>> = groups.map(::withHeadings)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Playlists exist but every one is turned off: the grid says so. */
    val allPlaylistsOff: StateFlow<Boolean> = combine(playlistsExist, playlistsOn) { exist, on -> exist && on == 0 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _selectedScope = MutableStateFlow<ChannelScope>(ChannelScope.All)
    val selectedScope: StateFlow<ChannelScope> = _selectedScope.asStateFlow()

    /** Keeps the current scope's paging cache alive; cancelled when the scope changes. */
    private var pagesJob: Job? = null

    private fun pagesFor(scope: ChannelScope): ScopePages {
        pagesJob?.cancel()
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        pagesJob = job
        val flow = Pager(PAGING) { channelPages(scope) }.flow
            .cachedIn(CoroutineScope(viewModelScope.coroutineContext + job))
        return ScopePages(scope, flow)
    }

    private val _pages = MutableStateFlow<ScopePages?>(null)

    /**
     * The selected scope's channel list: a new [ScopePages.flow] for every scope, so a list the
     * screen collects never presents another scope's rows (no stale rows to land focus on, and an
     * empty list is really this scope's). The screen takes the scope from here, not [selectedScope].
     * Null at start until the remembered group is known, so no list is loaded only to be replaced.
     */
    val pages: StateFlow<ScopePages?> = _pages.asStateFlow()

    private fun setScope(scope: ChannelScope) {
        if (scope == _selectedScope.value && _pages.value != null) return
        if (_moveKind.value != MoveKind.GROUP) endMove()
        _pages.value = pagesFor(scope)
        _selectedScope.value = scope
    }

    val favourites: StateFlow<Set<ChannelRef>> = favouriteRefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-shot message for a toast ("Added to favourites"); see [consumeNotice]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    private val _moving = MutableStateFlow<ChannelEntity?>(null)

    /** The channel being reordered (Move mode: a favourite, or a channel within its group), or null. */
    val moving: StateFlow<ChannelEntity?> = _moving.asStateFlow()

    private val _movingGroup = MutableStateFlow<GroupItem?>(null)

    /** The group being reordered (group Move mode), or null. */
    val movingGroup: StateFlow<GroupItem?> = _movingGroup.asStateFlow()

    private val _moveKind = MutableStateFlow<MoveKind?>(null)

    /** What Move mode reorders; null when not moving. */
    val moveKind: StateFlow<MoveKind?> = _moveKind.asStateFlow()

    /** Keeps toggles and moves in press order. */
    private val favouriteOps = Mutex()

    /** Adds [c] to the favourites (at the end) or removes it; the result shows as [notice]. */
    fun toggleFavourite(c: ChannelEntity) {
        viewModelScope.launch {
            val added = runCatching { favouriteOps.withLock { toggleFavouriteOp(c) } }.getOrElse {
                _notice.value = NOTICE_FAILED
                return@launch
            }
            if (!added && _moveKind.value == MoveKind.FAVOURITE && _moving.value?.id == c.id) endMove()
            _notice.value = if (added) NOTICE_ADDED else NOTICE_REMOVED
        }
    }

    /** Whether [c] can be moved within the shown list: the list is one group and [c] is in it. */
    fun canMoveInGroup(c: ChannelEntity): Boolean {
        val scope = _pages.value?.scope as? ChannelScope.Group ?: return false
        return c.playlistId == scope.playlistId && c.groupTitle == scope.title
    }

    /**
     * Move mode for [c]: in the Favourites list on a favourite, or in a group's list on one of
     * its channels; anywhere else nothing happens.
     */
    fun startMove(c: ChannelEntity) {
        val kind = when {
            _pages.value?.scope == ChannelScope.Favourites && c.ref in favourites.value -> MoveKind.FAVOURITE
            canMoveInGroup(c) -> MoveKind.GROUP_CHANNEL
            else -> return
        }
        _movingGroup.value = null
        _moving.value = c
        _moveKind.value = kind
    }

    /** Group Move mode for [group] (a playlist's group only). */
    fun startGroupMove(group: GroupItem) {
        if (!hasGroupOptions(group)) return
        pendingGroup?.cancel()
        pendingGroup = null
        _moving.value = null
        _movingGroup.value = group
        _moveKind.value = MoveKind.GROUP
    }

    /** Moves whatever is in Move mode one place up (direction < 0) or down. */
    fun moveBy(direction: Int) {
        val kind = _moveKind.value ?: return
        val channel = _moving.value
        val group = _movingGroup.value?.scope as? ChannelScope.Group
        // A press while the previous move is still running is dropped, not queued: queued moves
        // would carry on after the key is released on a slow device.
        if (moveJob?.isActive == true) return
        moveJob = viewModelScope.launch {
            try {
                when (kind) {
                    MoveKind.FAVOURITE -> if (channel != null) favouriteOps.withLock { moveFavouriteOp(channel, direction) }
                    MoveKind.GROUP_CHANNEL -> if (channel != null) edits.move(channel, direction)
                    MoveKind.GROUP -> if (group != null) edits.moveGroup(group.playlistId, group.title, direction)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed favourites move is silent; a group or channel move shows a notice.
                if (kind != MoveKind.FAVOURITE) _notice.value = NOTICE_EDIT_FAILED
            }
        }
    }

    private var moveJob: Job? = null

    fun endMove() {
        _moving.value = null
        _movingGroup.value = null
        _moveKind.value = null
    }

    /**
     * Hides [c]. A previewing [c] stops first, so its stream never plays on under an empty
     * preview, and a Move of it ends. The screen moves focus off its row before calling this.
     */
    fun hideChannel(c: ChannelEntity) {
        if (_previewing.value?.id == c.id) stopPreview()
        if (_moving.value?.id == c.id) endMove()
        if (_focused.value?.channel?.id == c.id) _focused.value = null
        // Its group's last channel: the group leaves the column with it, so unless the screen is
        // already on its way to another group, the list goes to All channels.
        if (isLastOfShownGroup(c) && pendingGroup == null) setScope(ChannelScope.All)
        edit { edits.setHidden(c.ref, true) }
    }

    /** Whether [c] is the only channel of the group whose list is shown (hiding it removes the group). */
    fun isLastOfShownGroup(c: ChannelEntity): Boolean {
        if (!canMoveInGroup(c)) return false
        val scope = _pages.value?.scope ?: return false
        return (groups.value.firstOrNull { it.scope == scope }?.count ?: 0) <= 1
    }

    /** Runs a hide or move; a failure shows [NOTICE_EDIT_FAILED] (the row stays as it was). */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = NOTICE_EDIT_FAILED
            }
        }
    }

    /**
     * Hides [group], so every channel of it. A preview from it stops; when it is the shown list
     * and no other group is about to be shown, the list goes to All channels.
     */
    fun hideGroup(group: GroupItem) {
        val scope = group.scope as? ChannelScope.Group ?: return
        val previewing = _previewing.value
        if (previewing != null && previewing.playlistId == scope.playlistId && previewing.groupTitle == scope.title) {
            stopPreview()
        }
        val focusedChannel = _focused.value?.channel
        if (focusedChannel != null && focusedChannel.playlistId == scope.playlistId && focusedChannel.groupTitle == scope.title) {
            _focused.value = null
        }
        if (_movingGroup.value?.scope == scope) endMove()
        if (_selectedScope.value == scope && pendingGroup == null) setScope(ChannelScope.All)
        edit { edits.setGroupHidden(scope.playlistId, scope.title, true) }
    }

    /**
     * Channel [id] was edited (rename, logo, TV guide, time shift): the info area and the preview
     * show its new values. The preview is not replayed (same channel, same stream).
     */
    fun onChannelEdited(id: Long) {
        val before = _focused.value
        viewModelScope.launch {
            val fresh = channelById(id) ?: return@launch
            // A focus change meanwhile (the grid refocusing the edited row) already carries the
            // fresh row and its block: never overwrite it with an older snapshot.
            _focused.value?.takeIf { it === before }?.let { f ->
                // A changed time shift moves the programmes: show what airs now instead.
                if (f.channel.id == id) {
                    val keep = f.channel.epgShiftMinutes == fresh.epgShiftMinutes && f.channel.guideEpgId == fresh.guideEpgId
                    _focused.value = FocusedProgramme(fresh, if (keep) f.programme else null)
                }
            }
            if (_previewing.value?.id == id) _previewing.value = fresh
        }
    }

    private fun stopPreview() {
        _previewing.value = null
        playback.stop()
    }

    private var pendingGroup: Job? = null
    private var pendingScope: ChannelScope = ChannelScope.All

    /**
     * Focusing a group switches the channel list to it after [GROUP_FOCUS_DEBOUNCE_MS], so scrolling
     * quickly through the groups column doesn't restart paging for every group passed.
     */
    fun onGroupFocused(scope: ChannelScope) {
        // Group Move mode: focus follows the moved group; the channel list stays.
        if (_moveKind.value == MoveKind.GROUP) return
        pendingGroup?.cancel()
        pendingGroup = null
        if (scope == _selectedScope.value) return
        pendingScope = scope
        pendingGroup = viewModelScope.launch {
            delay(GROUP_FOCUS_DEBOUNCE_MS)
            pendingGroup = null
            setScope(scope)
        }
    }

    /**
     * Focus left the groups column (grid, channels, rail): switch to the pending group now, so the
     * list never changes later under a focused cell.
     */
    fun commitPendingGroup() {
        val job = pendingGroup ?: return
        job.cancel()
        pendingGroup = null
        setScope(pendingScope)
    }

    /** Immediate switch (OK on a group). */
    fun selectGroup(scope: ChannelScope) {
        pendingGroup?.cancel()
        pendingGroup = null
        setScope(scope)
    }

    /** Last read of [latestStop]; refreshed at start, on screen start and when a forward shift is blocked. */
    private var latestEnd: Instant? = null
    private var latestEndJob: Job? = null

    private fun refreshLatestEnd() {
        latestEndJob?.cancel()
        latestEndJob = viewModelScope.launch { latestEnd = latestStop() }
    }

    init {
        refreshLatestEnd()
    }

    private val _window = MutableStateFlow(initialWindow(clock(), zone))
    val window: StateFlow<TimeWindow> = _window.asStateFlow()

    private val _windowShifted = MutableStateFlow(false)

    /** Whether the window was moved into the future; if not, it follows the clock. */
    val windowShifted: StateFlow<Boolean> = _windowShifted.asStateFlow()

    /**
     * Moves the window by [steps] half hours (Right/Left at a strip's edge); returns false when it
     * is already at the limit: the guide's end, or "now" (there is no past navigation).
     */
    fun shiftWindow(steps: Int): Boolean {
        val now = clock()
        val next = shiftWindow(_window.value, steps, now, zone, latestEnd)
        if (next == _window.value) {
            if (steps > 0) requeryLimitFor(steps)
            return false
        }
        applyWindow(next, now)
        return true
    }

    private fun applyWindow(next: TimeWindow, now: Instant) {
        _window.value = next
        _windowShifted.value = next != initialWindow(now, zone)
    }

    private var blockedSteps = 0

    /**
     * A forward shift hit the clamp: the guide may have grown since the last read, so re-read the
     * limit (one query in flight at a time; presses meanwhile are folded into it) and apply the
     * shift if the new limit allows it.
     */
    private fun requeryLimitFor(steps: Int) {
        blockedSteps += steps
        if (latestEndJob?.isActive == true) return
        latestEndJob = viewModelScope.launch {
            latestEnd = latestStop()
            val pending = blockedSteps
            blockedSteps = 0
            val now = clock()
            val next = shiftWindow(_window.value, pending, now, zone, latestEnd)
            if (next != _window.value) applyWindow(next, now)
        }
    }

    /** Snaps the window back to "now" (Back while shifted); false when it already was there. */
    fun resetWindow(): Boolean {
        val now = initialWindow(clock(), zone)
        val changed = _window.value != now
        _window.value = now
        _windowShifted.value = false
        return changed
    }

    /**
     * Called by the screen's clock tick: an unshifted window follows the current half hour, and a
     * shifted one the clock has caught up with becomes the "now" window again (never the past).
     */
    fun onTick() {
        val nowWindow = initialWindow(clock(), zone)
        if (!_windowShifted.value || _window.value.start <= nowWindow.start) {
            _window.value = nowWindow
            _windowShifted.value = false
        }
    }

    private val visibleKeys = MutableStateFlow<List<GuideKey>>(emptyList())

    /** The guide keys of the rows on screen (plus a small buffer); never the whole playlist. */
    fun setVisibleKeys(keys: Collection<GuideKey>) {
        visibleKeys.value = keys.distinct().take(MAX_WINDOW_IDS)
    }

    val slice: StateFlow<ProgrammeSlice> = combine(
        visibleKeys,
        _window.map { queryRange(it) }.distinctUntilChanged(),
    ) { keys, range -> keys to range }
        .distinctUntilChanged()
        .flatMapLatest { (keys, range) ->
            if (keys.isEmpty()) {
                flowOf(ProgrammeSlice.EMPTY)
            } else {
                programmes(keys, range.start, range.end).map { ProgrammeSlice(keys.toSet(), range, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgrammeSlice.EMPTY)

    private val _focused = MutableStateFlow<FocusedProgramme?>(null)

    /**
     * The focused slot. An empty strip (programme null) shows the programme airing now: from the
     * loaded rows when present, else from the database.
     */
    val focused: StateFlow<FocusedProgramme?> = combine(_focused, slice) { f, s -> f to s }
        .mapLatest { (f, s) ->
            if (f == null || f.programme != null) return@mapLatest f
            val key = f.channel.guideKey ?: return@mapLatest f
            val now = clock()
            // Shown (shifted) times on both paths.
            val airing = s.byKey[key]?.firstOrNull { it.start <= now && it.stop > now }
                // Outside the loaded slice (window in the past or far ahead): ask the database.
                ?: nowFor(f.channel)
            f.copy(programme = airing)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _previewing = MutableStateFlow<ChannelEntity?>(null)

    /** The channel in the preview (null until the first OK). Focus never changes it. */
    val previewing: StateFlow<ChannelEntity?> = _previewing.asStateFlow()

    /** Moving focus only updates the info area; the preview changes on OK ([onOk]). */
    fun onFocus(channel: ChannelEntity, programme: ProgrammeEntity?) {
        commitPendingGroup()
        _focused.value = FocusedProgramme(channel, programme)
    }

    /**
     * OK on a programme block, or an empty strip (programme null), list style:
     * - a row whose channel is not previewing → preview it (no navigation);
     * - the previewing channel's "No information" block or programme airing now → full screen
     *   (same player, no reconnect) while it is loading or playing; after an error, retry instead;
     * - a past or future programme of the previewing channel → its details.
     */
    fun onOk(channel: ChannelEntity, programme: ProgrammeEntity?): OkAction {
        if (_previewing.value?.id != channel.id) {
            _previewing.value = channel
            playback.play(channel)
            return OkAction.Previewed
        }
        val now = clock()
        if (programme != null && !(programme.start <= now && programme.stop > now)) {
            return OkAction.ShowDetails(programme)
        }
        // Full screen only while the preview is actually loading or playing; after an error (or a
        // stop) the second OK retries in place.
        return when (val state = playback.state.value) {
            is PlaybackState.Buffering, is PlaybackState.Playing ->
                if (state.channelIdOrNull() == channel.id) OkAction.OpenPlayer(channel.id) else retry(channel)
            else -> retry(channel)
        }
    }

    /**
     * OK on the focused preview: full screen on the previewing channel (same path as the second OK,
     * no reconnect; a failed preview retries in place). Null when nothing is previewing: no-op.
     */
    fun onPreviewOk(): OkAction? {
        val channel = _previewing.value ?: return null
        return onOk(channel, null)
    }

    /** An OpenPlayer was passed on and the guide hasn't been on screen again since. */
    private var playerPending = false

    /**
     * The channel to open full screen for [action], once: further OpenPlayers are ignored until the
     * guide is on screen again ([onScreenStarted]), so a quick second OK can't navigate twice.
     */
    fun claimPlayer(action: OkAction?): Long? {
        val open = action as? OkAction.OpenPlayer ?: return null
        if (playerPending) return null
        playerPending = true
        return open.channelId
    }

    /**
     * Replays the previewing [channel] in place, after a visible-only re-check (it may have been
     * hidden, or its playlist turned off, from Settings over the guide): when it is gone the preview
     * clears and nothing plays.
     */
    private fun retry(channel: ChannelEntity): OkAction {
        viewModelScope.launch {
            val fresh = channelById(channel.id)
            if (fresh == null) {
                if (_previewing.value?.id == channel.id) _previewing.value = null
            } else if (_previewing.value?.id == channel.id) {
                playback.play(fresh)
            }
        }
        return OkAction.Previewed
    }

    /** The player's channel when it differs from the previewing one (zapped in full screen), else null. */
    fun returnChannelId(): Long? {
        val id = playback.state.value.channelIdOrNull() ?: return null
        return id.takeIf { _previewing.value != null && it != _previewing.value?.id }
    }

    private val _refocus = MutableStateFlow<Long?>(null)

    /** A channel the grid should move focus to (set when coming back from the player); see [consumeRefocus]. */
    val refocus: StateFlow<Long?> = _refocus.asStateFlow()

    fun consumeRefocus() {
        _refocus.value = null
    }

    /**
     * The guide is on screen again. If the player switched channel meanwhile (channel up/down in
     * full screen), the preview and the focused row follow it; if playback was stopped (app in the
     * background), the previewing channel resumes. With nothing previewing but a channel loading
     * or playing (full screen opened from Search, or at start), that channel becomes the preview,
     * so audio never plays under an idle preview pane.
     */
    fun onScreenStarted() {
        playerPending = false
        refreshLatestEnd()
        when (val state = playback.state.value) {
            is PlaybackState.Idle -> _previewing.value?.let { previous ->
                viewModelScope.launch {
                    // The channel may be gone, hidden or turned off meanwhile: don't replay a ghost.
                    val channel = channelById(previous.id)
                    if (channel == null) {
                        _previewing.value = null
                    } else {
                        playback.play(channel)
                    }
                }
            }
            else -> {
                val adopted = if (_previewing.value == null) state.playingChannelId() else null
                adopt(adopted ?: returnChannelId() ?: return)
            }
        }
    }

    /** The preview and the focused row follow channel [id] (the player's channel). */
    private fun adopt(id: Long) {
        _refocus.value = id
        viewModelScope.launch {
            val channel = channelById(id) ?: return@launch
            _previewing.value = channel
            _focused.value = FocusedProgramme(channel, null)
        }
    }

    // Last in the class: on Dispatchers.Main.immediate this may run inline, so every property it
    // touches must be initialised already.
    init {
        viewModelScope.launch {
            val remembered = runCatching { initialScope() }.getOrDefault(ChannelScope.All)
            // A choice made meanwhile (OK on a group) wins, and is a change to remember.
            val chosenMeanwhile = _pages.value != null
            if (!chosenMeanwhile) {
                val listed = withTimeoutOrNull(GROUPS_WAIT_MS) { groups.first { it.isNotEmpty() } }.orEmpty()
                val item = listed.firstOrNull { it.scope == remembered }
                // A group that is gone, or Favourites left empty, starts on All channels.
                val start = if (item == null || (item.scope == ChannelScope.Favourites && item.count == 0)) {
                    ChannelScope.All
                } else {
                    remembered
                }
                if (_pages.value == null) setScope(start)
            }
            // Every committed change from here on (OK on a group, the focus debounce, leaving the
            // groups column) is remembered; the start scope itself is not a new choice.
            _selectedScope.drop(if (chosenMeanwhile) 0 else 1).distinctUntilChanged().collect { scope ->
                runCatching { saveScope(scope) }
            }
        }
    }

    companion object {

        /** When no edits are given (tests that don't edit): every edit does nothing. */
        private object NoEdits : EditActions {
            override suspend fun setHidden(ref: ChannelRef, hidden: Boolean) = Unit
            override suspend fun rename(ref: ChannelRef, name: String?) = Unit
            override suspend fun setLogo(ref: ChannelRef, url: String?) = Unit
            override suspend fun setEpgOverride(ref: ChannelRef, epgId: String?) = Unit
            override suspend fun setShift(ref: ChannelRef, minutes: Int) = Unit
            override suspend fun move(channel: ChannelEntity, direction: Int) = false
            override suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean) = Unit
            override suspend fun renameGroup(playlistId: Long, title: String, name: String?) = Unit
            override suspend fun moveGroup(playlistId: Long, title: String, direction: Int) = false
            override suspend fun setGroupSort(playlistId: Long, title: String, sort: GroupSort) = Unit
        }

        private val PAGING = PagingConfig(pageSize = 60, prefetchDistance = 60, initialLoadSize = 120)

        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                val channelDao = c.db.channelDao()
                val programmeDao = c.db.programmeDao()
                val playlists = c.db.playlistDao().all()
                return GuideViewModel(
                    groupRows = channelDao.groups(),
                    channelPages = { c.scopedChannels.pages(it) },
                    programmes = c.shiftedProgrammes::inWindow,
                    latestStop = { programmeDao.latestStop() },
                    playback = c.playback,
                    nowFor = { channel ->
                        val now = Instant.now()
                        c.shiftedProgrammes.nowAndNext(channel, now).firstOrNull { it.start <= now }
                    },
                    // Visible only: a hidden or turned-off channel is never resumed or adopted.
                    channelById = { id -> visibleOnly(channelDao.byId(id)) },
                    favouriteCount = c.favourites.count,
                    favouriteRefs = c.favourites.refs,
                    toggleFavourite = c.favourites::toggle,
                    moveFavourite = c.favourites::move,
                    initialScope = {
                        c.scopedChannels.resolve(channelScopeFromArg(c.settingsStore.current().lastGuideScope))
                    },
                    saveScope = { scope -> c.settingsStore.update { it.copy(lastGuideScope = scope.toArg()) } },
                    edits = c.channelEdits.asEditActions(),
                    playlistsOn = playlists.map { list -> list.count { it.enabled } },
                    playlistsExist = playlists.map { it.isNotEmpty() },
                ) as T
            }
        }
    }
}
