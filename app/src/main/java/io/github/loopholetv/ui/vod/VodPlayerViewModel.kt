package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.vod.SAVE_INTERVAL_MS
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.WATCHED_FRACTION
import io.github.loopholetv.data.vod.resumeFrom
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.PlayerTracks
import io.github.loopholetv.player.TrackChoice
import io.github.loopholetv.player.TrackOption
import io.github.loopholetv.player.TrackType
import io.github.loopholetv.player.cantPlayMessage
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.catchup.SeekBatcher
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.player.distinctLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VodPlayerEvent {
    /** Leave the player. [lastEpisodeKey] is the episode playing or just ended; null for a film. */
    data class Exit(val lastEpisodeKey: String?) : VodPlayerEvent
}

/** How often the screen calls [VodPlayerViewModel.tick]: it drives the bar, held seeks, the save timer and the end. */
const val VOD_TICK_MS = 250L

/**
 * Plays one film or episode full screen: opens it at [startMs], keeps the seek bar, commits batched
 * seeks and saves the watch position every [SAVE_INTERVAL_MS] while it plays, and on pause, Back,
 * leaving the screen and the end. All calls are on the main thread. [nowMs] is a monotonic clock.
 *
 * When an episode ends and [autoPlay] is on, it counts down and plays the episode [next] gives in
 * place, asking "Still watching?" every few unattended starts (see [AutoPlayRun]). A stream is only
 * opened once the previous one has stopped, and at most one start runs at a time.
 */
class VodPlayerViewModel(
    ref: VodRef,
    startMs: Long,
    private val playback: Playback,
    private val items: suspend (VodRef) -> VodPlayable?,
    private val progress: VodProgressRepository,
    private val next: suspend (VodRef) -> VodRef?,
    private val label: suspend (VodRef) -> String?,
    private val autoPlay: () -> Boolean,
    private val nowMs: () -> Long,
) : ViewModel() {
    private val _bar = MutableStateFlow<VodBarUi?>(null)
    val bar: StateFlow<VodBarUi?> = _bar.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<VodPlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<VodPlayerEvent> = _events.asSharedFlow()

    /** The picture's fit for this viewing only; the next film starts at Fit. */
    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val run = AutoPlayRun(nowMs)

    /** The Next episode button, the countdown and the "Still watching?" question. */
    val autoNext: StateFlow<AutoNextUi> = run.ui

    /** The item on screen; a run replaces it without changing the route. */
    private var current = ref
    private var nextRef: VodRef? = null
    private var nextKnown = false

    /** True from asking for the next episode until its stream is opened: no second start may begin. */
    private var starting = false
    private var inPip = false
    private var barShowing = false
    private var screenStopped = false

    /** The screen went to the background between episodes: leave as soon as it is back. */
    private var leaveOnReturn = false

    private val batcher = SeekBatcher(nowMs)
    private var playable: VodPlayable? = null
    private var title = ""

    /** Where a reopen starts: the position last seen playing, or the one this screen opened at. */
    private var lastPositionMs = startMs

    /** True once the stream has played; before that there is no position worth saving. */
    private var hasPlayed = false
    private var opened = false
    private var finished = false
    private var lastSaveAt = nowMs()

    init {
        open(startMs)
        lookUpNext()
        viewModelScope.launch {
            playback.state.collect(::onState)
        }
    }

    private fun open(startMs: Long) {
        viewModelScope.launch { openNow(startMs) }
    }

    private suspend fun openNow(startMs: Long) {
        val r = current
        val item = playable ?: items(r)
        // Back may have been pressed while the item loaded: nothing may start without a screen.
        if (finished || r != current) return
        if (item == null) {
            _error.value = cantPlayMessage("This title")
            return
        }
        playable = item
        title = item.title
        _error.value = null
        opened = true
        lastSaveAt = nowMs()
        // In the background the stream stays closed; coming back opens it here.
        if (screenStopped) lastPositionMs = startMs else playback.playVod(item, startMs)
    }

    /** Looks up, once per item, the episode that follows it. */
    private fun lookUpNext() {
        val r = current
        nextRef = null
        nextKnown = r.kind != VodKind.EPISODE
        if (nextKnown) return
        viewModelScope.launch {
            val n = nextSafely(r)
            if (r == current) {
                nextRef = n
                nextKnown = true
            }
        }
    }

    private suspend fun nextOf(r: VodRef): VodRef? = if (nextKnown && r == current) nextRef else nextSafely(r)

    private suspend fun nextSafely(r: VodRef): VodRef? = try {
        next(r)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun onState(state: PlaybackState) {
        if (state is PlaybackState.Playing && state.channelId == VOD_CHANNEL_ID) hasPlayed = true
        if (state is PlaybackState.Error && state.channelId == VOD_CHANNEL_ID) {
            // The position survives the error; an open that never started keeps where it began.
            lastPositionMs = playback.positionMs().takeIf { it > 0 } ?: lastPositionMs
            _error.value = state.message
        } else if (state !is PlaybackState.Idle) {
            _error.value = null
        }
    }

    /** Called every [VOD_TICK_MS] by the screen while it shows. */
    fun tick() {
        if (finished) return
        if (run.between) {
            step(run.tick())
            return
        }
        val mode = playback.mode.value as? PlayMode.Vod ?: return
        if (mode.ref != current) return
        if (mode.ended) {
            onEnded(mode)
            return
        }
        var position = playback.positionMs()
        batcher.due()?.let { offset ->
            position = (position + offset).coerceIn(0L, mode.durationMs ?: Long.MAX_VALUE)
            playback.seekWithin(position)
        }
        val playing = playback.state.value is PlaybackState.Playing
        if (playing) lastPositionMs = position
        if (playing && !playback.paused.value && nowMs() - lastSaveAt >= SAVE_INTERVAL_MS) save()
        // Auto-play off: no Next episode button, so OK only pauses.
        val hasNext = autoPlay() && nextRef != null && !starting
        run.onPlayingTick(position, mode.durationMs, hasNext, playback.paused.value, inPip, barShowing)
        refreshBar(position, mode)
    }

    private fun refreshBar(position: Long = playback.positionMs(), mode: PlayMode.Vod? = playback.mode.value as? PlayMode.Vod) {
        if (mode == null) return
        _bar.value = vodBarUi(title, position, mode.durationMs, batcher.pending, playback.paused.value)
    }

    /**
     * The stream ended: store where it stopped and close it. It counts as watched only when that is
     * near the known length; a stream cut short keeps its position, and one of unknown length starts
     * over. A real end of an episode with a next one counts down to it; anything else leaves.
     */
    private fun onEnded(mode: PlayMode.Vod) {
        val position = playback.positionMs()
        val duration = mode.durationMs
        record(position, duration, ended = true)
        playback.stop()
        val cutShort = duration != null && duration > 0 && position < WATCHED_FRACTION * duration
        if (cutShort || inPip || !autoPlay() || current.kind != VodKind.EPISODE) return exit()
        run.onEnded()
        val r = current
        viewModelScope.launch {
            val n = nextOf(r)
            if (finished || leaveOnReturn) return@launch
            if (n == null) return@launch exit()
            val text = try {
                label(n)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (finished || leaveOnReturn) return@launch
            run.countDown(n, text ?: "Next episode")
        }
    }

    private fun step(s: RunStep) {
        when (s) {
            RunStep.None -> Unit
            RunStep.Exit -> exit()
            is RunStep.Start -> startNext(s.next)
        }
    }

    /**
     * Plays [n] in place of the current item, from its saved position. The caller has already
     * stopped the old stream; [starting] keeps a second start out until this one has opened.
     */
    private fun startNext(n: VodRef) {
        if (starting || finished) return
        starting = true
        current = n
        playable = null
        title = ""
        hasPlayed = false
        opened = false
        lastPositionMs = 0
        batcher.cancel()
        _error.value = null
        lookUpNext()
        viewModelScope.launch {
            try {
                val from = try {
                    resumeFrom(progress.get(n)) ?: 0L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    0L
                }
                lastPositionMs = from
                openNow(from)
            } finally {
                starting = false
            }
        }
    }

    /** Leaves the player for good; the stream is already stopped. */
    private fun exit() {
        if (finished) return
        finished = true
        run.end()
        run.onLeft()
        _events.tryEmit(VodPlayerEvent.Exit(current.key.takeIf { current.kind == VodKind.EPISODE }))
    }

    /**
     * Stores the current position. Nothing before the stream has played. While a reopen is still
     * loading the player reports 0, so then the last position seen playing is stored instead.
     */
    private fun save() {
        lastSaveAt = nowMs()
        if (!hasPlayed) return
        if (playback.state.value is PlaybackState.Playing) lastPositionMs = playback.positionMs()
        val mode = playback.mode.value as? PlayMode.Vod
        record(lastPositionMs, mode?.durationMs)
    }

    private fun record(positionMs: Long, durationMs: Long?, ended: Boolean = false) {
        // Taken now: a run may move on to the next episode before this is written.
        val r = current
        // Not cancelled with the screen: leaving is exactly when the last position is written.
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    if (ended) progress.recordEnd(r, positionMs, durationMs) else progress.record(r, positionMs, durationMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Losing one save only costs a little resume accuracy.
                }
            }
        }
    }

    fun onSeekKey(direction: Int, repeatCount: Int) {
        batcher.press(direction, repeatCount)
        refreshBar()
    }

    fun onSeekRelease() = batcher.release()

    fun onPlayPause() {
        // Between episodes nothing plays, and a save would overwrite the recorded end.
        if (finished || run.between) return
        if (playback.paused.value) {
            playback.resume()
        } else {
            playback.pause()
            save()
        }
        refreshBar()
    }

    fun onBack() {
        if (finished) return
        // Between episodes the stream is closed and the end recorded: nothing to save or stop.
        if (!run.between) {
            save()
            playback.stop()
        }
        exit()
    }

    /** The Next episode button: saves this episode (near its end, so watched) and plays the next. */
    fun onNextPressed() {
        if (finished || starting || !autoPlay()) return
        val s = run.nextPressed(nextRef)
        if (s !is RunStep.Start) return
        save()
        playback.stop()
        startNext(s.next)
    }

    fun onPlayNow() {
        if (!finished) step(run.playNow())
    }

    fun onCancelCountdown() {
        if (!finished && run.countingDown) exit()
    }

    fun onKeepWatching() {
        if (!finished) step(run.keepWatching())
    }

    fun onStopWatching() {
        if (!finished && run.asking) exit()
    }

    /** Any key press on the player: someone is watching. */
    fun onUserKey() = run.onUserKey()

    /** Picture-in-picture: no button, countdown or question there, so a pending one leaves. */
    fun onPipChanged(inPip: Boolean) {
        this.inPip = inPip
        if (inPip && !finished && !leaveOnReturn && run.between) exit()
    }

    /** Whether the info bar shows: the Next episode button comes back with it. */
    fun onBarShowing(showing: Boolean) {
        barShowing = showing
    }

    /** Opens again where playback was, after an error. */
    fun retry() {
        if (finished || run.between) return
        _error.value = null
        open(lastPositionMs)
    }

    /** Coming back from the background (the stream was stopped meanwhile): continue where it was. */
    fun onScreenStarted() {
        screenStopped = false
        if (finished) return
        if (leaveOnReturn) return exit()
        if (!opened || playback.state.value !is PlaybackState.Idle) return
        playable?.let { playback.playVod(it, lastPositionMs) }
    }

    /** The screen is going away (the stream stops after this): note where it was. */
    fun onScreenStopped() {
        screenStopped = true
        if (finished) return
        run.onLeft()
        if (run.between) {
            // The countdown or question stops here; the end is already recorded.
            leaveOnReturn = true
            run.end()
            return
        }
        if (playback.state.value is PlaybackState.Playing) lastPositionMs = playback.positionMs()
        batcher.cancel()
        save()
    }

    fun cycleAspect() {
        _aspect.value = AspectMode.entries[(_aspect.value.ordinal + 1) % AspectMode.entries.size]
    }

    /** Audio and subtitles (when the stream offers a choice) and the aspect ratio; each entry steps to the next value. */
    fun options(): List<ChannelOption> = buildList {
        val tracks = playback.tracks.value
        val audio = distinctLabels(tracks.audio)
        if (audio.size > 1) {
            val current = audio.indexOfFirst { it.selected }.coerceAtLeast(0)
            add(ChannelOption("Audio: ${audio[current].label}") {
                playback.selectTrack(TrackType.AUDIO, TrackChoice.Pick(audio[(current + 1) % audio.size].id))
            })
        }
        val text = distinctLabels(tracks.text)
        if (text.isNotEmpty()) add(subtitleOption(tracks, text))
        add(ChannelOption("Aspect ratio: ${_aspect.value.label}") { cycleAspect() })
    }

    /** Steps Off, then each subtitle language, then Off again. */
    private fun subtitleOption(tracks: PlayerTracks, text: List<TrackOption>): ChannelOption {
        val current = if (tracks.textOff) -1 else text.indexOfFirst { it.selected }
        val label = if (current < 0) "Off" else text[current].label
        return ChannelOption("Subtitles: $label") {
            val next = current + 1
            playback.selectTrack(
                TrackType.TEXT,
                if (next >= text.size) TrackChoice.Off else TrackChoice.Pick(text[next].id),
            )
        }
    }
}
