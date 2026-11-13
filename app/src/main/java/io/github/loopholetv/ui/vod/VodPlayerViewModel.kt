package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.vod.SAVE_INTERVAL_MS
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
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
    /** The film is finished with: leave the player. */
    data object Exit : VodPlayerEvent
}

/** How often the screen calls [VodPlayerViewModel.tick]: it drives the bar, held seeks, the save timer and the end. */
const val VOD_TICK_MS = 250L

/**
 * Plays one film or episode full screen: opens it at [startMs], keeps the seek bar, commits batched
 * seeks and saves the watch position every [SAVE_INTERVAL_MS] while it plays, and on pause, Back,
 * leaving the screen and the end. All calls are on the main thread. [nowMs] is a monotonic clock.
 */
class VodPlayerViewModel(
    private val ref: VodRef,
    startMs: Long,
    private val playback: Playback,
    private val items: suspend (VodRef) -> VodPlayable?,
    private val progress: VodProgressRepository,
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
        viewModelScope.launch {
            playback.state.collect(::onState)
        }
    }

    private fun open(startMs: Long) {
        viewModelScope.launch {
            val item = playable ?: items(ref)
            // Back may have been pressed while the item loaded: nothing may start without a screen.
            if (finished) return@launch
            if (item == null) {
                _error.value = cantPlayMessage("This title")
                return@launch
            }
            playable = item
            title = item.title
            _error.value = null
            opened = true
            lastSaveAt = nowMs()
            playback.playVod(item, startMs)
        }
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
        val mode = playback.mode.value as? PlayMode.Vod ?: return
        if (mode.ref != ref) return
        if (mode.ended) {
            finish(mode)
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
        refreshBar(position, mode)
    }

    private fun refreshBar(position: Long = playback.positionMs(), mode: PlayMode.Vod? = playback.mode.value as? PlayMode.Vod) {
        if (mode == null) return
        _bar.value = vodBarUi(title, position, mode.durationMs, batcher.pending, playback.paused.value)
    }

    /**
     * The stream ended: store where it stopped, then out. It counts as watched only when that is
     * near the known length; a stream cut short, or one of unknown length, keeps its position.
     */
    private fun finish(mode: PlayMode.Vod) {
        finished = true
        record(playback.positionMs(), mode.durationMs)
        playback.stop()
        _events.tryEmit(VodPlayerEvent.Exit)
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

    private fun record(positionMs: Long, durationMs: Long?) {
        // Not cancelled with the screen: leaving is exactly when the last position is written.
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    progress.record(ref, positionMs, durationMs)
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
        finished = true
        save()
        playback.stop()
        _events.tryEmit(VodPlayerEvent.Exit)
    }

    /** Opens again where playback was, after an error. */
    fun retry() {
        _error.value = null
        open(lastPositionMs)
    }

    /** Coming back from the background (the stream was stopped meanwhile): continue where it was. */
    fun onScreenStarted() {
        if (finished || !opened || playback.state.value !is PlaybackState.Idle) return
        playable?.let { playback.playVod(it, lastPositionMs) }
    }

    /** The screen is going away (the stream stops after this): note where it was. */
    fun onScreenStopped() {
        if (finished) return
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
