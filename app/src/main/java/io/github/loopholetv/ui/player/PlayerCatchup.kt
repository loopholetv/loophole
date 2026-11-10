package io.github.loopholetv.ui.player

import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.catchup.CatchupController
import io.github.loopholetv.player.catchup.CatchupUi
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.catchup.ProgrammeLookup
import io.github.loopholetv.player.catchup.fallbackSpan
import io.github.loopholetv.ui.guide.GuideFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** What [PlayerCatchup] needs from the player screen's state. */
internal interface CatchupHost {
    /** The full overlay, opened on the catch-up bar (focus on Play/Pause). */
    fun showOverlayOnBar()

    /** The bar went away (back to live): an overlay focused on it must not be left without focus. */
    fun onBarGone()

    fun showMessage(text: String)

    /** Any catch-up input counts as input (hide timers, sleep warning). */
    fun onUserInput()

    /** Plays [channel] live the way the player opens channels (its external player, if set). */
    fun openLive(channel: ChannelEntity)
}

/**
 * The player's catch-up side around its [controller]: the catch-up keys and bar buttons, whether
 * the seek bar shows, its 1 s position ticker (running only while the bar shows) and the point to
 * resume from after the app was in the background.
 *
 * The bar shows when an archive (or a live pause) starts and on [showBar] (OK, a seek, a pause);
 * it hides [OVERLAY_TIMEOUT_MS] after the last input while playing, but stays while paused.
 */
internal class PlayerCatchup(
    private val playback: Playback,
    private val programmes: ProgrammeLookup,
    clock: () -> Instant,
    elapsedMs: () -> Long,
    private val format: GuideFormat,
    private val scope: CoroutineScope,
    private val host: CatchupHost,
) {
    val controller = CatchupController(playback, programmes, clock, elapsedMs, format.zone, scope, host::openLive)

    val bar: StateFlow<CatchupBarUi?> =
        controller.ui.map { c -> c?.let { catchupBarUi(it, format) } }.stateIn(scope, SharingStarted.Eagerly, null)

    val failed: StateFlow<Boolean> =
        controller.ui.map { it?.failed == true }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _barVisible = MutableStateFlow(false)
    val barVisible: StateFlow<Boolean> = _barVisible.asStateFlow()

    private var hideJob: Job? = null

    /** Channel id and archive position saved when the screen stopped. */
    private var resumeAt: Pair<Long, Instant>? = null

    /** Set by [clear]: the archive was dropped on purpose (a channel change), not left for live. */
    private var cleared = false

    /** The seek key held down, whose key-up commits the batched seek. */
    private var seekKeyCode: Int? = null

    /** The key that rewound from live: its repeats are ignored until it is released, so the archive opens once. */
    private var rewindKeyCode: Int? = null

    /** Replaying the archive, or live paused on a catch-up channel. */
    val active: Boolean get() = controller.ui.value != null

    val paused: Boolean get() = controller.ui.value?.paused == true

    init {
        scope.launch {
            var last: CatchupUi? = null
            controller.ui.collect { c ->
                when {
                    c == null -> {
                        hideBar()
                        if (last != null && !cleared) host.onBarGone()
                        cleared = false
                    }
                    // An archive (or a live pause) started, or a paused live stream resumed from the archive.
                    last == null || last?.channelId != c.channelId || last?.live != c.live -> showBar()
                }
                last = c
            }
        }
        scope.launch {
            _barVisible.collectLatest { visible ->
                while (visible) {
                    controller.tick()
                    delay(CATCHUP_TICK_MS)
                }
            }
        }
    }

    /**
     * The first play with [from] set: the archive from there, taking over the one already opened
     * for [channel] without opening it again. False when [channel] has no catch-up (play live).
     */
    suspend fun openFirst(channel: ChannelEntity, from: Instant): Boolean {
        val spec = channel.catchup() ?: return false
        val loaded = playback.mode.value as? PlayMode.Archive
        if (loaded?.channelId == channel.id) {
            controller.adopt(channel, spec)
        } else {
            val until = (programmes.at(channel, from) ?: fallbackSpan(from, format.zone)).stop
            controller.watch(channel, spec, from, until)
        }
        return true
    }

    /** Carries out a catch-up key [route] on [channel]; false when [route] is not a catch-up one. */
    fun perform(route: PlayerKeyRoute, channel: ChannelEntity, keyCode: Int, repeatCount: Int): Boolean {
        val spec = channel.catchup()
        // A held Rewind from live: the first press opened the archive; its repeats (seen as seeks once
        // the archive shows) would open it a second time, so they are swallowed until key-up.
        if (keyCode == rewindKeyCode && (route is PlayerKeyRoute.Seek || route == PlayerKeyRoute.RewindLive)) return true
        when (route) {
            PlayerKeyRoute.ShowBar -> {
                host.showOverlayOnBar()
                showBar()
            }
            is PlayerKeyRoute.Seek -> if (spec != null) {
                seekKeyCode = keyCode
                controller.seekKey(channel, spec, route.direction, repeatCount)
                showBar()
            }
            PlayerKeyRoute.RewindLive -> if (spec != null && repeatCount == 0) {
                rewindKeyCode = keyCode
                controller.rewindLive(channel, spec)
            }
            PlayerKeyRoute.TogglePause -> {
                controller.togglePause(channel, spec)
                showBar()
                host.onUserInput()
            }
            PlayerKeyRoute.GoLive -> goLive(channel)
            PlayerKeyRoute.WatchFromStart -> watchFromStart(channel)
            PlayerKeyRoute.NoCatchup -> host.showMessage(NO_CATCHUP)
            PlayerKeyRoute.RetryArchive -> retry(channel)
            else -> return false
        }
        return true
    }

    /** A key went up: releasing the held seek key commits its batched seek. */
    fun onKeyUp(keyCode: Int) {
        if (keyCode == rewindKeyCode) rewindKeyCode = null
        if (keyCode != seekKeyCode) return
        seekKeyCode = null
        controller.seekKeyUp()
    }

    /** A catch-up bar button on [channel]; one press of Rewind or Fast-forward is one seek. */
    fun transport(channel: ChannelEntity, button: TransportButton) {
        val spec = channel.catchup() ?: return
        val c = controller
        host.onUserInput()
        when (button) {
            TransportButton.PREVIOUS -> c.previousProgramme()
            TransportButton.REWIND -> { c.seekKey(channel, spec, -1, 0); c.seekKeyUp() }
            TransportButton.PLAY_PAUSE -> c.togglePause(channel, spec)
            TransportButton.FAST_FORWARD -> { c.seekKey(channel, spec, 1, 0); c.seekKeyUp() }
            TransportButton.NEXT -> c.nextProgramme()
            TransportButton.LIVE -> goLive(channel)
        }
        showBar()
    }

    /** Leaves the archive (or a live pause) for live; nothing when already live, so live opens once. */
    fun goLive(channel: ChannelEntity) {
        if (active) controller.goLive(channel)
    }

    fun retry(channel: ChannelEntity) {
        channel.catchup()?.let { controller.retry(channel, it) }
    }

    fun watchFromStart(channel: ChannelEntity) {
        val spec = channel.catchup() ?: return host.showMessage(NO_CATCHUP)
        controller.watchFromStart(channel, spec)
    }

    /** Watch from start is offered while a channel with catch-up plays live. */
    fun startOverOffered(channel: ChannelEntity?): Boolean = !active && channel?.catchup() != null

    /** Shows the bar (when there is one) and restarts its hide timer. */
    fun showBar() {
        if (!active) return
        _barVisible.value = true
        restartHideTimer()
    }

    fun hideBar() {
        hideJob?.cancel()
        hideJob = null
        _barVisible.value = false
    }

    /** Any input while the bar shows keeps it up for another [OVERLAY_TIMEOUT_MS]. */
    fun onInput() {
        if (_barVisible.value) restartHideTimer()
    }

    private fun restartHideTimer() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            hideJob = null
            if (!paused) _barVisible.value = false
        }
    }

    /**
     * The screen stopped: note where the archive was (a held seek's target when one is pending),
     * before leaving the app stops the stream, drop that seek and hide the bar (no ticks unseen).
     */
    fun onStopped() {
        val target = controller.ui.value?.pendingTarget
        resumeAt = controller.resumePoint()?.let { (id, at) -> id to (target ?: at) }
        seekKeyCode = null
        rewindKeyCode = null
        controller.cancelSeek()
        hideBar()
    }

    /**
     * The screen started again with nothing playing: a pause stays paused (its bar shows again),
     * else [channel] goes on from the archive where it was. False when it should play live.
     */
    fun onStarted(channel: ChannelEntity): Boolean {
        if (paused) {
            showBar()
            return true
        }
        if (playback.state.value !is PlaybackState.Idle) return true
        val point = resumeAt ?: controller.resumePoint()
        resumeAt = null
        val at = point?.takeIf { it.first == channel.id }?.second ?: return false
        val spec = channel.catchup() ?: return false
        controller.resume(channel, spec, at)
        return true
    }

    /** A channel change or leaving the player: forget the archive, the bar and the resume point. */
    fun clear() {
        if (active) cleared = true
        controller.clear()
        resumeAt = null
        seekKeyCode = null
        rewindKeyCode = null
        hideBar()
    }
}
