package io.github.loopholetv.ui.player

import io.github.loopholetv.ui.splash.StartGate
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.NoCast
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.player.PipGate
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.PreviewHold
import io.github.loopholetv.player.SleepState
import io.github.loopholetv.player.SleepTimer
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.catchup.ProgrammeLookup
import io.github.loopholetv.ui.guide.GuideFormat
import io.github.loopholetv.ui.manage.ChannelEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
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
 * changes channel. Both hide after [OVERLAY_TIMEOUT_MS] without input. Held OK or Menu on bare
 * video or the banner opens the quick menu ([quickMenu]); holding OK on a tile opens that
 * channel's options. A channel set to an external player is handed to that app when it is
 * opened here instead of playing.
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
    /** The first play waits for it (closed while the start-up splash covers this screen). */
    private val startGate: StartGate = StartGate(),
    settings: Flow<PlaybackSettings> = flowOf(PlaybackSettings()),
    updatePlayback: suspend ((PlaybackSettings) -> PlaybackSettings) -> Unit = {},
    /** A channel's playback overrides (aspect, decoders, external player), kept current. */
    private val channelPlayback: (ChannelEntity) -> Flow<ChannelPlaybackPrefs> = { flowOf(ChannelPlaybackPrefs.NONE) },
    /** Saves a channel's aspect; null is Fit. */
    private val setAspect: suspend (ChannelEntity, AspectMode?) -> Unit = { _, _ -> },
    private val sleepTimer: SleepTimer? = null,
    /** Armed when leaving for the guide must not restart its preview (sleep timer, other player). */
    private val previewHold: PreviewHold = PreviewHold(),
    pipSupported: Boolean = false,
    /** Survives process death: remembers that the channel went to another player. */
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** Cleared as soon as the screen heads back to the guide (no picture-in-picture on the way out). */
    private val pipGate: PipGate? = null,
    /** Remote-control behaviour: what the seek keys do and the button bindings. */
    remote: Flow<RemoteSettings> = flowOf(RemoteSettings()),
    /** The guide programme at a shown instant; by default the one [nowAndNext] has airing then. */
    programmeLookup: ProgrammeLookup? = null,
    /** Replay the archive from this shown instant instead of playing live. */
    private val archiveFrom: Instant? = null,
    elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    /** Times on the catch-up bar. */
    format: GuideFormat = GuideFormat(true, ZoneId.systemDefault()),
    /** Casting what plays here to the other TVs (the quick menu's cast tile). */
    private val cast: CastControls = NoCast,
) : ViewModel() {

    private val toggle = toggleFavourite

    /** The zapping context; a number jump outside it switches to all channels. */
    private var scope: ChannelScope = scope

    private val _ui = MutableStateFlow<PlayerUi?>(null)
    val ui: StateFlow<PlayerUi?> = _ui.asStateFlow()

    private val footer = FooterRow(viewModelScope, channels, nowAndNext, clock, recent)
    val row: StateFlow<ChannelRowUi> = footer.row

    /** Favourite channels (★ on the row tiles and, via [PlayerUi.favourite], in the info bar). */
    private val _favourites = MutableStateFlow<Set<ChannelRef>>(emptySet())
    val favourites: StateFlow<Set<ChannelRef>> = _favourites.asStateFlow()

    private val dialogs = ChannelDialogs(::hideOverlay)

    /** The channel whose options dialog is open (long OK / Menu); null when none. */
    val optionsFor: StateFlow<ChannelEntity?> = dialogs.optionsFor

    /** The channel edit picked from the options (its dialog, or a write in progress); null when none. */
    val editing: StateFlow<ChannelEditing?> = dialogs.editing

    /** True once the screen must go back to the guide (the last channel of the scope was hidden). */
    private val _exitToGuide = MutableStateFlow(false)
    val exitToGuide: StateFlow<Boolean> = _exitToGuide.asStateFlow()

    /** Serialises channel changes so rapid presses each step from the previous target. */
    private val switching = Mutex()
    private var overlayJob: Job? = null
    private var messageJob: Job? = null
    private var settleJob: Job? = null
    private var programmeJob: Job? = null

    /** Set once the user left for the guide: nothing may switch channel after that. */
    private var exited = false

    /** Set once the first play went out (after [startGate]); resuming before that never plays. */
    private var started = false

    private var inPip = false

    /** The current channel's playback overrides. */
    private val _prefs = MutableStateFlow(ChannelPlaybackPrefs.NONE)
    val channelPlaybackPrefs: StateFlow<ChannelPlaybackPrefs> = _prefs.asStateFlow()

    /** How the current channel's picture fills the screen (its saved aspect, else Fit). */
    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    private val _autoFrameRate = MutableStateFlow(false)
    val autoFrameRate: StateFlow<Boolean> = _autoFrameRate.asStateFlow()

    val sleep: StateFlow<SleepState> = sleepTimer?.state ?: MutableStateFlow<SleepState>(SleepState.Off).asStateFlow()

    /** Whether the sleep warning shows; unlike [sleep] it does not change with every second counted. */
    val sleepWarningShown: StateFlow<Boolean> =
        sleep.map { it is SleepState.Warning }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Buffered until the screen collects them, so a hand-off is never lost. */
    private val _events = Channel<PlayerEvent>(Channel.BUFFERED)
    val events: Flow<PlayerEvent> = _events.receiveAsFlow()

    private val menu = QuickMenuController(
        scope = viewModelScope,
        playback = playback,
        itemsNow = { quickItems(pipSupported, startOver = catchup.startOverOffered(_ui.value?.channel)) },
        sleepTimer = sleepTimer,
        updatePlayback = updatePlayback,
        host = object : QuickMenuHost {
            override fun values() = QuickValues(
                tracks = playback.tracks.value,
                aspect = _aspect.value,
                sleep = sleep.value,
                favourite = _ui.value?.favourite == true,
                casting = cast.state.value is CastState.Casting,
            )
            override fun showChannels() = showOverlay()
            override fun watchFromStart() = this@PlayerViewModel.watchFromStart()
            override fun toggleFavourite() = this@PlayerViewModel.toggleFavourite()
            override fun toggleCasting() {
                if (cast.state.value is CastState.Casting) cast.stopCasting() else cast.startCasting()
            }
            override fun openOptions() { _ui.value?.channel?.let(::openOptions) }
            override fun saveAspect(mode: AspectMode) {
                val c = _ui.value?.channel ?: return
                _aspect.value = mode
                viewModelScope.launch {
                    try {
                        setAspect(c, mode.takeIf { it != AspectMode.FIT })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Back to what is saved for the channel, if it is still the one showing.
                        if (_ui.value?.channel?.id == c.id) {
                            _aspect.value = _prefs.value.aspect ?: AspectMode.FIT
                            showMessage(ASPECT_SAVE_FAILED)
                        }
                    }
                }
            }
            override fun send(event: PlayerEvent) { _events.trySend(event) }
            override fun stayAwake() = this@PlayerViewModel.stayAwake()
        },
    )

    /** The open quick menu; null when closed. */
    val quickMenu: StateFlow<QuickMenuUi?> = menu.ui

    /** How many TVs follow this one's cast; null while it is not casting. */
    val castingFollowers: StateFlow<Int?> =
        cast.state.map { (it as? CastState.Casting)?.followers }
            .stateIn(viewModelScope, SharingStarted.Eagerly, (cast.state.value as? CastState.Casting)?.followers)

    private val opener = ChannelOpener(playback, channelPlayback, cast)

    private val catchup = PlayerCatchup(
        playback, programmeLookup ?: airingLookup(nowAndNext), clock, elapsedMs, format, viewModelScope,
        object : CatchupHost {
            override fun showOverlayOnBar() {
                showOverlay()
                update { it.copy(barFocused = true) }
            }
            override fun onBarGone() { if (_ui.value?.overlayVisible == true) hideOverlay() }
            override fun showMessage(text: String) = this@PlayerViewModel.showMessage(text)
            override fun onUserInput() = this@PlayerViewModel.onUserInput()
            // Dropped if an archive starts while the channel's settings are read.
            override fun openLive(channel: ChannelEntity) { viewModelScope.launch { open(channel, liveOnly = true) } }
        },
    )

    private val numbers = NumberEntry(viewModelScope, { _ui.value?.numberEntry }, { e -> update { it.copy(numberEntry = e) } }, ::jump)

    /** The catch-up seek bar's content; null while live. */
    val catchupBar: StateFlow<CatchupBarUi?> = catchup.bar

    /** Whether the catch-up bar shows (it hides with the overlay timeout, never while paused). */
    val barVisible: StateFlow<Boolean> = catchup.barVisible

    /** The archive failed to play: its error card offers Try again and Watch live. */
    val archiveFailed: StateFlow<Boolean> = catchup.failed

    private var remoteSettings = RemoteSettings()


    /** The channel last handed to another player (played here if that player can't open). */
    private var handedOff: ChannelEntity? = null

    /**
     * True while the current channel belongs to another player whose link could not be resolved:
     * nothing plays, and coming back to the app must not play it here either.
     */
    private var notPlayedHere = false

    init {
        viewModelScope.launch {
            settings.catch { }.collect { s ->
                _subtitleStyle.value = s.subtitleStyle
                _autoFrameRate.value = s.autoFrameRate
                menu.onStoredDelay(s.audioDelayMs)
            }
        }
        viewModelScope.launch {
            opener.follow(_ui.map { it?.channel }.filterNotNull()) { p ->
                _prefs.value = p
                _aspect.value = p.aspect ?: AspectMode.FIT
            }
        }
        viewModelScope.launch {
            merge(
                playback.tracks, _aspect, sleep, catchupBar.map { it == null }.distinctUntilChanged(),
                cast.state.map { it is CastState.Casting }.distinctUntilChanged(),
            ).collect { menu.refresh() }
        }
        viewModelScope.launch { remote.catch { }.collect { remoteSettings = it } }
        // Not lifecycle-bound: the expiry is not replayed, and the exit must not be missed.
        sleepTimer?.let { timer ->
            viewModelScope.launch {
                timer.expired.collect {
                    previewHold.hold()
                    exitQuietly()
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            favouriteRefs.catch { }.collect { refs ->
                _favourites.value = refs
                update { it.copy(favourite = it.channel.ref in refs) }
                menu.refresh()
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
            loadProgrammes(channel)
            // Recreated after the app was killed behind another player: back to the guide quietly.
            if (savedState.get<Boolean>(KEY_HANDED_OFF) == true) {
                previewHold.hold()
                exitQuietly()
                return@launch
            }
            startGate.await()
            // The channel previewing in the guide is already loading or playing: this only resumes it
            // (or hands it to its external player).
            val first = _ui.value?.channel ?: channel
            // From the guide's past: the archive (recordings always play here), else live.
            if (!exited && archiveFrom?.let { catchup.openFirst(first, it) } != true) open(first)
            started = true
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
        if (!exited && _ui.value != null) numbers.digit(d)
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

    private suspend fun switchTo(channel: ChannelEntity, playNow: Boolean, fromHistory: Boolean = false) {
        if (exited) return
        // A new channel starts live: the archive, its bar and its resume point are forgotten.
        catchup.clear()
        update {
            it.copy(channel = channel, now = null, next = null, favourite = channel.ref in _favourites.value, barFocused = false)
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

    private suspend fun startPlayback(channel: ChannelEntity, fromHistory: Boolean = false) {
        if (!open(channel)) return
        update { it.withPlayback(playback.state.value) }
        // The row is only built while the full overlay needs it (once per settled channel).
        if (_ui.value?.overlayVisible != true) return
        // Played from History: the row stays History, the played tile becomes current.
        if (fromHistory && row.value.mode == RowMode.HISTORY) {
            footer.update { r -> r.channels.indexOfFirst { it.id == channel.id }.let { i -> if (i >= 0) r.copy(currentIndex = i) else r } }
        } else {
            loadRow(channel)
        }
    }

    /**
     * Opens [channel]: plays it here, or, when it is set to an external player, stops our stream and
     * hands a fresh link to that app. False when nothing was played here. [liveOnly]: going live
     * from a recording, dropped if an archive started meanwhile.
     */
    private suspend fun open(channel: ChannelEntity, liveOnly: Boolean = false): Boolean {
        val result = opener.open(channel) {
            !exited && _ui.value?.channel?.id == channel.id && !(liveOnly && catchup.active)
        }
        if (result == Opened.Dropped) return false
        notPlayedHere = result == Opened.ExternalFailed
        if (result == Opened.Here) return true
        update { it.withPlayback(playback.state.value) }
        if (result is Opened.External) {
            handedOff = channel
            savedState[KEY_HANDED_OFF] = true
            _events.trySend(PlayerEvent.LaunchExternal(result.choice, result.handoff, channel.displayName))
        } else {
            showMessage(EXTERNAL_RESOLVE_FAILED)
        }
        return false
    }

    /** The other player opened: back to the guide, whose preview stays stopped. */
    fun onExternalLaunched() {
        previewHold.hold()
        exitQuietly()
    }

    /** The other player is gone: the channel plays here instead. */
    fun onExternalFailed() {
        if (exited) return
        val c = handedOff ?: _ui.value?.channel ?: return
        handedOff = null
        notPlayedHere = false
        savedState[KEY_HANDED_OFF] = false
        showMessage(EXTERNAL_LAUNCH_FAILED)
        playback.play(c)
        update { it.withPlayback(playback.state.value) }
    }

    /** Plays a zap target still waiting for [ZAP_SETTLE_MS] now. */
    private fun flushPendingZap() {
        val job = settleJob ?: return
        job.cancel()
        settleJob = null
        _ui.value?.channel?.let(playback::play)
    }

    /**
     * Leaving for the guide (Back from bare video, the TV guide tile): settle, then ignore input.
     * A recording or a paused live stream is switched to the channel live here ([toLive]), so the
     * guide's preview shows live; that never goes to an external player.
     */
    fun leave(toLive: Boolean = true) {
        pipGate?.playerShowing = false
        menu.flushDelay()
        val live = _ui.value?.channel?.takeIf { toLive && settleJob == null && isRecording(it) }
        catchup.clear()
        if (exited) return
        if (live != null) playback.play(live) else flushPendingZap()
        numbers.cancel()
        exited = true
    }

    /** Replaying [channel]'s archive, or its live stream paused. */
    private fun isRecording(channel: ChannelEntity): Boolean =
        catchup.active || (playback.mode.value as? PlayMode.Archive)?.channelId == channel.id

    /** The slim info banner (no row, no focus); keeps the full overlay if that is up. */
    private fun showBanner() {
        update { if (it.overlayVisible) it else it.copy(bannerVisible = true) }
        restartHideTimer()
    }

    /** The full overlay: banner plus channel row (focus goes to the row). */
    fun showOverlay() {
        val ui = _ui.value ?: return
        update { it.copy(overlayVisible = true, bannerVisible = false, barFocused = false) }
        restartHideTimer()
        // Always opens on the channels (History is left by closing the overlay).
        val row = this.row.value
        if ((row.currentId != ui.channel.id || row.mode != RowMode.CHANNELS) && settleJob == null) loadRow(ui.channel)
    }

    /** The History tile: the row lists the recently watched channels, focus on the current one. */
    fun showHistory() {
        val ui = _ui.value ?: return
        if (exited) return
        footer.history(ui.channel.id)
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

    /** OK held (or Menu) on bare video or the banner: the quick menu. */
    fun onLongOk() {
        if (exited) return
        openQuickMenu()
    }

    /** Opens the quick menu on the tile used last (Search the first time); hides the overlay. */
    fun openQuickMenu() {
        stayAwake()
        if (exited || inPip || _ui.value == null) return
        // Not over the options or an edit (the tail of a held OK must not stack a second dialog).
        if (dialogs.isOpen || menu.isOpen) return
        hideOverlay()
        menu.open()
    }

    fun dismissQuickMenu() {
        stayAwake()
        menu.dismiss()
    }

    fun pickQuickItem(item: QuickItem) = menu.pick(item)

    fun pickChoice(item: QuickItem, key: String) = menu.choose(item, key)

    /** Left (-1) or Right (+1) in the audio delay list. */
    fun stepAudioDelay(direction: Int) = menu.step(direction)

    /** Back from a list: the tiles again, focus on that list's tile. */
    fun closePanel() = menu.closePanel()

    /** The quick menu, one of its lists, or a channel's options or edit is open. */
    fun dialogOpen(): Boolean = menu.isOpen || dialogs.isOpen

    /** In picture-in-picture only the video shows: every overlay, menu and number entry closes. */
    fun onPipChanged(inPip: Boolean) {
        this.inPip = inPip
        if (!inPip) return
        hideOverlay()
        catchup.hideBar()
        numbers.cancel()
        menu.dismiss()
        dialogs.closeAll()
    }

    /**
     * During the sleep timer's last minute any key keeps watching and does nothing else.
     * True when the key was taken for that.
     */
    fun consumeSleepWarning(): Boolean {
        if (sleep.value !is SleepState.Warning) return false
        sleepTimer?.keepWatching()
        return true
    }

    private fun stayAwake() {
        if (sleep.value is SleepState.Warning) sleepTimer?.keepWatching()
    }

    /** OK held (or Menu) on a channel tile: options for that channel. */
    fun onTileLongPress(channel: ChannelEntity) {
        stayAwake()
        if (exited) return
        openOptions(channel)
    }

    private fun openOptions(channel: ChannelEntity) = dialogs.openOptions(channel)

    fun dismissOptions() {
        stayAwake()
        dialogs.dismissOptions()
    }

    /** An edit row of the options (from [channelEditOptions]): closes the options, opens the edit. */
    fun pickEdit(channel: ChannelEntity, edit: ChannelEdit) {
        stayAwake()
        if (!exited) dialogs.pickEdit(channel, edit)
    }

    /**
     * The edit finished (saved, written or dismissed): re-reads the channel so the info bar and row
     * show its new name, logo and guide. Hiding the current channel zaps to the next channel of the
     * scope; when the scope has none left, playback stops and the screen goes back to the guide.
     */
    fun onEditDone() {
        stayAwake()
        val done = dialogs.takeDone() ?: return
        viewModelScope.launch { switching.withLock { applyEdit(done.channel.id) } }
    }

    private suspend fun applyEdit(id: Long) {
        if (exited) return
        val fresh = channels.byId(id) ?: return
        val current = _ui.value?.channel ?: return
        // The tile's title reloads outside the switching lock.
        footer.onEdited(fresh)
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
        exitQuietly()
        playback.stop()
    }

    /** Back to the guide without playing anything on the way (leave() would flush a pending zap). */
    private fun exitQuietly() {
        settleJob?.cancel()
        settleJob = null
        leave(toLive = false)
        menu.dismiss()
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
        update { it.copy(overlayVisible = false, bannerVisible = false, barFocused = false) }
    }

    /** Any key while the banner, overlay or catch-up bar is up restarts its [OVERLAY_TIMEOUT_MS]. */
    fun onUserInput() {
        stayAwake()
        catchup.onInput()
        val ui = _ui.value ?: return
        if (ui.overlayVisible || ui.bannerVisible) restartHideTimer()
    }

    private fun restartHideTimer() {
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            overlayJob = null
            update { it.copy(overlayVisible = false, bannerVisible = false, barFocused = false) }
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
                numbers.cancel()
                true
            }
            ui.overlayVisible || ui.bannerVisible || barVisible.value -> {
                hideOverlay()
                catchup.hideBar()
                true
            }
            else -> {
                leave()
                false
            }
        }
    }

    /**
     * Key-down routing (see [routePlayerKey]); true = consumed. Digits always enter a number, and
     * OK commits one being typed; bound buttons (channel keys, Rewind, Play/Pause…) act everywhere.
     * Without the full overlay (bare video or banner) D-pad Up opens it on the channel row and Down
     * opens the quick menu (the overlay again while the seek bar shows); neither changes channel.
     * OK opens the overlay too (on the catch-up bar while replaying), except OK on bare video with
     * an error card, where it is Try again. With the full overlay the D-pad belongs to it and
     * only restarts the hide timer. [repeatCount] sizes held seek keys; their key-up ([onKeyUp])
     * commits one seek.
     */
    fun onKey(keyCode: Int, repeatCount: Int = 0): Boolean {
        val ui = _ui.value ?: return false
        if (exited) return false
        if (consumeSleepWarning()) return true
        val route = routePlayerKey(ui.keyState(catchup.active, archiveFailed.value, barVisible.value), keyCode, remoteSettings)
        if (route == PlayerKeyRoute.Pass) {
            onUserInput()
            return false
        }
        perform(route, ui.channel, keyCode, repeatCount)
        return true
    }

    private fun perform(route: PlayerKeyRoute, channel: ChannelEntity, keyCode: Int, repeatCount: Int) {
        if (!catchup.perform(route, channel, keyCode, repeatCount)) performPlayerRoute(route)
    }

    internal fun commitNumber() = numbers.commit()

    /** A button bound to the guide: leave for it. */
    internal fun goToGuide() {
        leave()
        _exitToGuide.value = true
    }

    internal fun openSearch() { _events.trySend(PlayerEvent.OpenSearch) }

    /** Opens the quick menu straight on [item]'s list. */
    internal fun openQuickPanel(item: QuickItem) {
        openQuickMenu()
        if (menu.isOpen) menu.openPanel(item)
    }

    /** A key went up: releasing the held seek key commits its batched seek. */
    fun onKeyUp(keyCode: Int) = catchup.onKeyUp(keyCode)

    /**
     * OK clicked at the root (bare video or banner). On the archive's error card it presses the
     * button that has focus ([watchLiveFocused]: Watch live, else Try again).
     */
    fun onRootClick(watchLiveFocused: Boolean = false) {
        if (archiveFailed.value && watchLiveFocused && _ui.value?.let { !it.bannerVisible } == true) watchLive()
        else onKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** A catch-up bar button. */
    fun onTransport(button: TransportButton) { current()?.let { catchup.transport(it, button) } }

    /** Leaves the archive (or a live pause) for the live stream. */
    fun watchLive() { current()?.let(catchup::goLive) }

    /** Try again on the archive's error card: opens it again where it failed. */
    fun retryArchive() { current()?.let(catchup::retry) }

    /** Replays the programme airing now from its start. */
    fun watchFromStart() { current()?.let(catchup::watchFromStart) }

    private fun current(): ChannelEntity? = if (exited) null else _ui.value?.channel

    /**
     * The screen came (back) to the foreground: resume if leaving the app stopped playback, live
     * or the archive where it was. A pause (live or archive) stays paused.
     */
    fun onScreenStarted() {
        if (exited || !started || notPlayedHere) return
        val ui = _ui.value ?: return
        if (!catchup.onStarted(ui.channel) && playback.state.value is PlaybackState.Idle) playback.play(ui.channel)
    }

    override fun onCleared() {
        // A stepped audio delay still waiting for its write is saved, not dropped with the screen.
        menu.flushDelay()
    }

    /**
     * The screen stopped (app in the background): drop a zap still settling and note where the
     * archive was (the stream stops after this; a held seek's target counts); resume plays from
     * there. The catch-up bar hides, so its ticker stops while unseen.
     */
    fun onScreenStopped() {
        settleJob?.cancel()
        settleJob = null
        catchup.onStopped()
    }

    /**
     * Focus reached the first or last channel tile: load up to [ROW_EACH_SIDE] more channels on
     * that side (no-op at the group's real end), so Left/Right never stop early.
     */
    fun onRowEdge(atEnd: Boolean) {
        footer.extend(atEnd, scope)
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
        programmeJob = footer.nowNext(channel) { now, next ->
            update { if (it.channel.id == channel.id) it.copy(now = now, next = next) else it }
        }
    }

    private fun loadRow(channel: ChannelEntity) = footer.load(channel, scope)

    private inline fun update(f: (PlayerUi) -> PlayerUi) = _ui.update { it?.let(f) }

    companion object {
        private const val KEY_HANDED_OFF = "handedOff"

        /** [archiveFrom]: replay the archive from this shown instant instead of playing live. */
        fun factory(channelId: Long, scope: ChannelScope, archiveFrom: Instant? = null): ViewModelProvider.Factory =
            playerViewModelFactory(channelId, scope, archiveFrom)
    }
}
