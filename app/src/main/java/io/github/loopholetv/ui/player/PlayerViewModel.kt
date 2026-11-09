package io.github.loopholetv.ui.player

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.guide.GuideFormat
import io.github.loopholetv.ui.manage.ChannelEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Full-screen playback of [channelId] on the app's single player, zapping (CH±/PAGE±) within
 * [scope]. [ui] is null until the channel has loaded (and stays null if it no longer exists).
 *
 * Layers: a zap (channel keys, a number jump) shows the slim info banner, which takes no focus.
 * D-pad Up, Down and OK on bare video or under the banner open the full overlay (banner plus
 * channel row, focus on the current tile), where the D-pad navigates the row and OK on a tile
 * changes channel. Both hide after [OVERLAY_TIMEOUT_MS] without input.
 */
class PlayerViewModel(
    channelId: Long,
    scope: ChannelScope,
    private val playback: Playback,
    private val channels: PlayerChannels,
    /** The programme airing at shown time `at` on a channel and the one after, shifted (see ShiftedProgrammes). */
    private val nowAndNext: suspend (channel: ChannelEntity, at: Instant) -> List<ProgrammeEntity>,
    private val clock: () -> Instant = Instant::now,
    private val formatClock: (Instant) -> String,
    favouriteRefs: Flow<Set<ChannelRef>> = emptyFlow(),
    toggleFavourite: suspend (ChannelEntity) -> Boolean = { false },
    /** Recently watched channels, newest first (History). */
    private val recent: Flow<List<ChannelEntity>> = flowOf(emptyList()),
) : ViewModel() {

    private val toggle = toggleFavourite

    /** The zapping context; a number jump outside it switches to all channels. */
    private var scope: ChannelScope = scope

    private val _ui = MutableStateFlow<PlayerUi?>(null)
    val ui: StateFlow<PlayerUi?> = _ui.asStateFlow()

    private val _row = MutableStateFlow(ChannelRowUi.EMPTY)
    val row: StateFlow<ChannelRowUi> = _row.asStateFlow()

    /** Favourite channels (★ on the row tiles and, via [PlayerUi.favourite], in the info bar). */
    private val _favourites = MutableStateFlow<Set<ChannelRef>>(emptySet())
    val favourites: StateFlow<Set<ChannelRef>> = _favourites.asStateFlow()

    /** The channel whose options dialog is open (long OK / Menu); null when none. */
    private val _optionsFor = MutableStateFlow<ChannelEntity?>(null)
    val optionsFor: StateFlow<ChannelEntity?> = _optionsFor.asStateFlow()

    /** The channel edit picked from the options (its dialog, or a write in progress); null when none. */
    private val _editing = MutableStateFlow<ChannelEditing?>(null)
    val editing: StateFlow<ChannelEditing?> = _editing.asStateFlow()

    /** True once the screen must go back to the guide (the last channel of the scope was hidden). */
    private val _exitToGuide = MutableStateFlow(false)
    val exitToGuide: StateFlow<Boolean> = _exitToGuide.asStateFlow()

    /** Serialises channel changes so rapid presses each step from the previous target. */
    private val switching = Mutex()
    private var overlayJob: Job? = null
    private var entryJob: Job? = null
    private var messageJob: Job? = null
    private var settleJob: Job? = null
    private var programmeJob: Job? = null
    private var rowJob: Job? = null
    private var rowEdgeJob: Job? = null

    /** Set once the user left for the guide: nothing may switch channel after that. */
    private var exited = false

    init {
        viewModelScope.launch {
            favouriteRefs.catch { }.collect { refs ->
                _favourites.value = refs
                update { it.copy(favourite = it.channel.ref in refs) }
            }
        }
        viewModelScope.launch {
            // Visible only: a channel hidden, or of a turned-off playlist, never starts.
            val channel = channels.byId(channelId)?.takeIf { it.visible }
            if (channel == null) {
                exitHiddenOrMissing()
                return@launch
            }
            val at = clock()
            _ui.value = PlayerUi(
                channel = channel, now = null, next = null, tags = StreamTags.NONE, overlayVisible = false,
                bannerVisible = true, numberEntry = null, message = null, error = null, clock = formatClock(at),
                groupLabel = groupLabel(), at = at, favourite = channel.ref in _favourites.value,
            ).withPlayback(playback.state.value)
            // The channel previewing in the guide is already loading or playing: this only resumes it.
            playback.play(channel)
            loadProgrammes(channel)
            restartHideTimer()
            playback.state.collect { s -> update { it.withPlayback(s) } }
        }
    }

    private suspend fun groupLabel() = channels.label(scope)

    /** Channel Up key: the next channel (higher number) of the group, wrapping. */
    fun channelUp() = zap(+1)

    /** Channel Down key: the previous channel (lower number) of the group, wrapping. */
    fun channelDown() = zap(-1)

    private fun zap(direction: Int) {
        if (exited) return
        viewModelScope.launch {
            switching.withLock {
                if (exited) return@withLock
                val from = _ui.value?.channel ?: return@withLock
                val to = channels.neighbour(from, scope, direction) ?: return@withLock
                switchTo(to, playNow = false)
            }
        }
    }

    fun digit(d: Int) {
        require(d in 0..9) { "digit $d" }
        if (exited) return
        val entry = (_ui.value ?: return).numberEntry.orEmpty() + d
        update { it.copy(numberEntry = entry) }
        entryJob?.cancel()
        entryJob = null
        if (entry.length >= MAX_NUMBER_DIGITS) {
            jump(entry)
        } else {
            entryJob = viewModelScope.launch {
                delay(NUMBER_ENTRY_IDLE_MS)
                entryJob = null
                jump(entry)
            }
        }
    }

    /** OK while typing a number: jump now instead of waiting. */
    private fun commitNumberEntry() {
        val entry = _ui.value?.numberEntry ?: return
        entryJob?.cancel()
        entryJob = null
        jump(entry)
    }

    private fun jump(entry: String) {
        update { it.copy(numberEntry = null) }
        val number = entry.toInt()
        viewModelScope.launch {
            switching.withLock {
                if (exited) return@withLock
                val target = channels.byNumber(number)
                when {
                    target == null -> showMessage("No channel $number")
                    target.id == _ui.value?.channel?.id -> showBanner()
                    else -> {
                        if (scope != ChannelScope.All && !channels.contains(target, scope)) {
                            scope = ChannelScope.All
                            val label = groupLabel()
                            update { it.copy(groupLabel = label) }
                        }
                        switchTo(target, playNow = true)
                    }
                }
            }
        }
    }

    /** OK on a channel tile: plays it at once, staying full screen. */
    fun playFromRow(channel: ChannelEntity) {
        viewModelScope.launch {
            switching.withLock {
                if (exited) return@withLock
                if (channel.id == _ui.value?.channel?.id) restartHideTimer() else switchTo(channel, playNow = true)
            }
        }
    }

    private fun switchTo(channel: ChannelEntity, playNow: Boolean, fromHistory: Boolean = false) {
        if (exited) return
        update {
            it.copy(channel = channel, now = null, next = null, favourite = channel.ref in _favourites.value)
                .withPlayback(playback.state.value)
        }
        showBanner()
        loadProgrammes(channel)
        settleJob?.cancel()
        settleJob = null
        if (playNow) {
            startPlayback(channel, fromHistory)
        } else {
            settleJob = viewModelScope.launch {
                delay(ZAP_SETTLE_MS)
                settleJob = null
                if (!exited) startPlayback(channel)
            }
        }
    }

    private fun startPlayback(channel: ChannelEntity, fromHistory: Boolean = false) {
        playback.play(channel)
        update { it.withPlayback(playback.state.value) }
        // The row is only built while the full overlay needs it (once per settled channel).
        if (_ui.value?.overlayVisible != true) return
        // Played from History: the row stays History, the played tile becomes current.
        if (fromHistory && _row.value.mode == RowMode.HISTORY) {
            _row.update { r -> r.channels.indexOfFirst { it.id == channel.id }.let { i -> if (i >= 0) r.copy(currentIndex = i) else r } }
        } else {
            loadRow(channel)
        }
    }

    /** Plays a zap target still waiting for [ZAP_SETTLE_MS] now. */
    private fun flushPendingZap() {
        val job = settleJob ?: return
        job.cancel()
        settleJob = null
        _ui.value?.channel?.let(playback::play)
    }

    /** Leaving for the guide (Back from bare video, the TV guide tile): settle, then ignore input. */
    fun leave() {
        if (exited) return
        flushPendingZap()
        entryJob?.cancel()
        exited = true
    }

    /** The slim info banner (no row, no focus); keeps the full overlay if that is up. */
    private fun showBanner() {
        update { if (it.overlayVisible) it else it.copy(bannerVisible = true) }
        restartHideTimer()
    }

    /** The full overlay: banner plus channel row (focus goes to the row). */
    fun showOverlay() {
        val ui = _ui.value ?: return
        update { it.copy(overlayVisible = true, bannerVisible = false) }
        restartHideTimer()
        // Always opens on the channels (History is left by closing the overlay).
        val row = _row.value
        if ((row.currentId != ui.channel.id || row.mode != RowMode.CHANNELS) && settleJob == null) loadRow(ui.channel)
    }

    /** The History tile: the row lists the recently watched channels, focus on the current one. */
    fun showHistory() {
        val ui = _ui.value ?: return
        if (exited) return
        rowJob?.cancel()
        rowEdgeJob?.cancel()
        rowJob = viewModelScope.launch {
            val list = recent.catch { emit(emptyList()) }.first()
            val index = list.indexOfFirst { it.id == ui.channel.id }.coerceAtLeast(0)
            _row.value = ChannelRowUi(list, index, emptyMap(), RowMode.HISTORY)
            val titles = titlesFor(list)
            _row.update { if (it.mode == RowMode.HISTORY && it.channels == list) it.copy(titles = titles) else it }
        }
    }

    /** The Channels tile (History mode): back to the channels around the current one. */
    fun showChannels() {
        val ui = _ui.value ?: return
        if (exited) return
        loadRow(ui.channel)
    }

    /**
     * OK on a History tile: plays it at once. Outside the zapping scope, zapping switches to all
     * channels. The row stays History.
     */
    fun playFromHistory(channel: ChannelEntity) {
        viewModelScope.launch {
            switching.withLock {
                if (exited) return@withLock
                if (channel.id == _ui.value?.channel?.id) {
                    restartHideTimer()
                    return@withLock
                }
                if (scope != ChannelScope.All && !channels.contains(channel, scope)) {
                    scope = ChannelScope.All
                    val label = groupLabel()
                    update { it.copy(groupLabel = label) }
                }
                switchTo(channel, playNow = true, fromHistory = true)
            }
        }
    }

    /** OK held (or Menu) on bare video or the banner: options for the current channel. */
    fun onLongOk() {
        if (exited) return
        openOptions(_ui.value?.channel ?: return)
    }

    /** OK held (or Menu) on a channel tile: options for that channel. */
    fun onTileLongPress(channel: ChannelEntity) {
        if (exited) return
        openOptions(channel)
    }

    private fun openOptions(channel: ChannelEntity) {
        // Already open (e.g. the tail of the held press that opened it): keep that channel. Nor
        // while an edit's dialog is up (a held OK must not open a second panel under it).
        if (_optionsFor.value != null || _editing.value != null) return
        // The dialog is its own window: nothing of the overlay may stay up (or time out) under it.
        hideOverlay()
        _optionsFor.value = channel
    }

    fun dismissOptions() {
        _optionsFor.value = null
    }

    /** An edit row of the options (from [channelEditOptions]): closes the options, opens the edit. */
    fun pickEdit(channel: ChannelEntity, edit: ChannelEdit) {
        if (exited) return
        _optionsFor.value = null
        _editing.value = ChannelEditing(channel, edit)
    }

    /**
     * The edit finished (saved, written or dismissed): re-reads the channel so the info bar and row
     * show its new name, logo and guide. Hiding the current channel zaps to the next channel of the
     * scope; when the scope has none left, playback stops and the screen goes back to the guide.
     */
    fun onEditDone() {
        val done = _editing.value ?: return
        _editing.value = null
        viewModelScope.launch { switching.withLock { applyEdit(done.channel.id) } }
    }

    private suspend fun applyEdit(id: Long) {
        if (exited) return
        val fresh = channels.byId(id) ?: return
        val current = _ui.value?.channel ?: return
        if (!fresh.visible) {
            _row.update { r ->
                val kept = r.channels.filter { it.id != id }
                if (kept.size == r.channels.size) r else r.copy(channels = kept, currentIndex = kept.indexOfFirst { it.id == r.currentId })
            }
        } else {
            _row.update { r -> r.copy(channels = r.channels.map { if (it.id == id) fresh else it }) }
            // The tile's title (its guide or shift may have changed), outside the switching lock.
            viewModelScope.launch {
                val title = titlesFor(listOf(fresh))[id]
                _row.update { r ->
                    if (r.channels.none { it.id == id }) r
                    else r.copy(titles = if (title != null) r.titles + (id to title) else r.titles - id)
                }
            }
        }
        if (id != current.id) return
        if (fresh.visible) {
            // Now/next stay until the reload replaces them: no "No information" flash.
            update {
                if (it.channel.id != id) it
                else it.copy(channel = fresh, error = relabelError(it.error, it.channel.displayName, fresh.displayName))
            }
            loadProgrammes(fresh)
            return
        }
        // A hidden channel is unreachable, also the one playing.
        val next = channels.neighbour(current, scope, +1)?.takeIf { it.id != id && it.visible }
        if (next != null) switchTo(next, playNow = true) else exitHiddenOrMissing()
    }

    /**
     * The channel to show is hidden, turned off or gone, and nothing replaces it: stop its stream
     * (a guide preview of it, or the channel playing) and go back to the guide.
     */
    private fun exitHiddenOrMissing() {
        // No pending zap may play the hidden channel on the way out (leave() flushes one).
        settleJob?.cancel()
        settleJob = null
        leave()
        playback.stop()
        _exitToGuide.value = true
    }

    /** Adds [channel] (default: the current one) to favourites or removes it, with a short notice. */
    fun toggleFavourite(channel: ChannelEntity? = null) {
        val c = channel ?: _ui.value?.channel ?: return
        viewModelScope.launch {
            val added = try {
                toggle(c)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage("Couldn't change favourites")
                return@launch
            }
            showMessage(if (added) "Added to favourites" else "Removed from favourites")
        }
    }

    fun hideOverlay() {
        overlayJob?.cancel()
        overlayJob = null
        update { it.copy(overlayVisible = false, bannerVisible = false) }
    }

    /** Any key while the banner or overlay is up restarts its [OVERLAY_TIMEOUT_MS]. */
    fun onUserInput() {
        val ui = _ui.value ?: return
        if (ui.overlayVisible || ui.bannerVisible) restartHideTimer()
    }

    private fun restartHideTimer() {
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            overlayJob = null
            update { it.copy(overlayVisible = false, bannerVisible = false) }
        }
    }

    private fun showMessage(text: String) {
        update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            update { it.copy(message = null) }
        }
    }

    fun retry() {
        if (exited) return
        settleJob?.cancel()
        settleJob = null
        _ui.value?.channel?.let(playback::play)
    }

    /**
     * Back: cancels a number entry, else hides the banner/overlay; from bare video it [leave]s and
     * returns false (not consumed) so the screen goes back to the guide.
     */
    fun onBack(): Boolean {
        val ui = _ui.value ?: return false
        return when {
            ui.numberEntry != null -> {
                entryJob?.cancel()
                entryJob = null
                update { it.copy(numberEntry = null) }
                true
            }
            ui.overlayVisible || ui.bannerVisible -> {
                hideOverlay()
                true
            }
            else -> {
                leave()
                false
            }
        }
    }

    /**
     * Key-down routing; true = consumed. Channel Up/Down (and Page Up/Down) always zap. Digits
     * always enter a number, and OK commits one being typed. Without the full overlay (bare video
     * or banner) D-pad Up, Down and OK open it, except OK on bare video with the error card, where
     * it is Try again. With the full overlay the D-pad belongs to the channel row
     * and only restarts the hide timer. A channel changes only by OK on a row tile, a channel key
     * or a number.
     */
    fun onKey(keyCode: Int): Boolean {
        val ui = _ui.value ?: return false
        if (exited) return false
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { channelUp(); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { channelDown(); return true }
        }
        digitOf(keyCode)?.let { digit(it); onUserInput(); return true }
        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk && ui.numberEntry != null) {
            commitNumberEntry()
            return true
        }
        if (ui.overlayVisible) {
            onUserInput()
            return false
        }
        return when {
            // Up/Down never zap: they open the full overlay (focus on the current tile).
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); true }
            // Bare video with the error card: OK is its Try again (the screen consumes OK before
            // the button sees it). With the banner up: the overlay.
            isOk -> { if (ui.error != null && !ui.bannerVisible) retry() else showOverlay(); true }
            else -> { onUserInput(); false }
        }
    }

    /** The screen came (back) to the foreground: resume if leaving the app stopped playback. */
    fun onScreenStarted() {
        if (exited) return
        val ui = _ui.value ?: return
        if (playback.state.value is PlaybackState.Idle) playback.play(ui.channel)
    }

    /** The screen stopped (app in the background): drop a zap still settling; resume plays it. */
    fun onScreenStopped() {
        settleJob?.cancel()
        settleJob = null
    }

    /**
     * Focus reached the first or last channel tile: load up to [ROW_EACH_SIDE] more channels on
     * that side (no-op at the group's real end), so Left/Right never stop early.
     */
    fun onRowEdge(atEnd: Boolean) {
        if (rowEdgeJob?.isActive == true || rowJob?.isActive == true) return
        val row = _row.value
        // History is the whole list already; scope channels don't belong in it.
        if (row.mode != RowMode.CHANNELS) return
        val edge = (if (atEnd) row.channels.lastOrNull() else row.channels.firstOrNull()) ?: return
        val s = scope
        rowEdgeJob = viewModelScope.launch {
            val around = channels.around(edge, s, ROW_EACH_SIDE)
            val at = around.indexOfFirst { it.id == edge.id }
            val known = row.channels.mapTo(HashSet()) { it.id }
            val more = (if (atEnd) around.drop(at + 1) else around.take(at.coerceAtLeast(0))).filter { it.id !in known }
            if (more.isEmpty() || _row.value !== row) return@launch
            val titles = titlesFor(more)
            _row.value = if (atEnd) {
                row.copy(channels = row.channels + more, titles = row.titles + titles)
            } else {
                row.copy(channels = more + row.channels, currentIndex = row.currentIndex + more.size, titles = row.titles + titles)
            }
        }
    }

    /** Clock tick from the screen: refreshes the time and, once the programme ended, now/next. */
    fun tick() {
        val ui = _ui.value ?: return
        val at = clock()
        update { it.copy(clock = formatClock(at), at = at) }
        val now = ui.now
        if (now == null || now.stop <= at) loadProgrammes(ui.channel)
    }

    private fun loadProgrammes(channel: ChannelEntity) {
        programmeJob?.cancel()
        // guideEpgId: the manual choice, else the automatic match; times come back shifted.
        if (channel.guideEpgId == null) {
            // No guide (e.g. the manual one was removed): nothing may stay from the old one.
            update { if (it.channel.id == channel.id) it.copy(now = null, next = null) else it }
            return
        }
        programmeJob = viewModelScope.launch {
            val at = clock()
            val list = nowAndNext(channel, at)
            val now = list.firstOrNull { it.start <= at }
            val next = list.firstOrNull { it.start > at }
            update { if (it.channel.id == channel.id) it.copy(now = now, next = next) else it }
        }
    }

    private fun loadRow(channel: ChannelEntity) {
        rowJob?.cancel()
        rowJob = viewModelScope.launch {
            val list = channels.around(channel, scope, ROW_EACH_SIDE)
            val index = list.indexOfFirst { it.id == channel.id }
            _row.value = ChannelRowUi(list, index, emptyMap())
            _row.value = ChannelRowUi(list, index, titlesFor(list))
        }
    }

    /** The programme airing now on each of [list] (by channel id), where known. */
    private suspend fun titlesFor(list: List<ChannelEntity>): Map<Long, String> {
        val at = clock()
        return buildMap {
            for (c in list) {
                if (c.guideEpgId == null) continue
                nowAndNext(c, at).firstOrNull { it.start <= at }?.let { put(c.id, it.title) }
            }
        }
    }

    private inline fun update(f: (PlayerUi) -> PlayerUi) = _ui.update { it?.let(f) }

    private fun PlayerUi.withPlayback(s: PlaybackState): PlayerUi {
        val id = channel.id
        return copy(
            tags = (s as? PlaybackState.Playing)?.takeIf { it.channelId == id }?.tags ?: StreamTags.NONE,
            error = (s as? PlaybackState.Error)?.takeIf { it.channelId == id }?.message,
            buffering = when (s) {
                is PlaybackState.Buffering -> true
                is PlaybackState.Playing -> s.channelId != id
                is PlaybackState.Error -> s.channelId != id
                PlaybackState.Idle -> false
            },
        )
    }

    companion object {
        fun factory(channelId: Long, scope: ChannelScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                val format = GuideFormat(DateFormat.is24HourFormat(app), ZoneId.systemDefault())
                PlayerViewModel(
                    channelId = channelId,
                    scope = scope,
                    playback = c.playback,
                    channels = DaoPlayerChannels(c.db.channelDao(), c.scopedChannels),
                    nowAndNext = c.shiftedProgrammes::nowAndNext,
                    formatClock = { "${format.date(it)}  ${format.time(it)}" },
                    favouriteRefs = c.favourites.refs,
                    toggleFavourite = c.favourites::toggle,
                    recent = c.history.recent(),
                )
            }
        }
    }
}
