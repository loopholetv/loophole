package io.github.loopholetv.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.vod.SAVE_INTERVAL_MS
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.catchup.SeekBatcher
import io.github.loopholetv.record.LIVE_EDGE_BEHIND_MS
import io.github.loopholetv.record.RecordingsClient
import io.github.loopholetv.record.RemoteRecording
import io.github.loopholetv.ui.vod.VodBarUi
import io.github.loopholetv.ui.vod.vodBarUi
import java.time.Instant
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

sealed interface RecordingPlayerEvent {
    /** Leave the player. */
    data object Exit : RecordingPlayerEvent

    /** The recording ended while the viewer was at its end: carry on with the live channel. */
    data class OpenLive(val channelId: Long) : RecordingPlayerEvent
}

const val CANT_PLAY_RECORDING = "Can't play this recording right now."

/** The viewer follows the live channel when the recording finishes this close to where they are. */
internal const val LIVE_HANDOVER_WITHIN_MS = 30_000L

/** How long a recording that is still being written waits before it is read again. */
internal const val REPLAY_DELAY_MS = 2_000L

/** Reopenings in a row, with no progress between them, before a growing recording shows an error. */
internal const val MAX_REPLAYS_WITHOUT_PROGRESS = 60

private const val REFRESH_MS = 1_000L
private const val REMOTE_REFRESH_MS = 5_000L

/**
 * Plays one recording, from this TV's storage ([peer] null) or from another Loophole TV's. It never
 * touches a provider connection and never writes film progress; a local recording keeps its own
 * position on its row. While the row is still being written the file grows under the player, so a
 * read that reaches the end (or is refused for starting past it) is tried again from the last position.
 * Calls are on the main thread; the screen calls [tick] every few hundred milliseconds.
 */
class RecordingPlayerViewModel(
    private val id: Long,
    private val peer: CastPeer?,
    private val live: Boolean,
    private val db: AppDatabase,
    private val client: RecordingsClient,
    private val playback: Playback,
    private val clock: () -> Instant,
    /** The address of a local recording's file for the player; null while it can't be served. */
    private val localUrl: (Long) -> String?,
    /** The recordings another TV lists; a seam for tests. */
    private val listRemote: suspend (CastPeer) -> List<RemoteRecording> = client::list,
) : ViewModel() {
    private val _bar = MutableStateFlow<VodBarUi?>(null)
    val bar: StateFlow<VodBarUi?> = _bar.asStateFlow()

    /** True while the recording is still being written: the screen shows "● REC". */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<RecordingPlayerEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<RecordingPlayerEvent> = _events.asSharedFlow()

    private val batcher = SeekBatcher { clock().toEpochMilli() }
    private var local: RecordingEntity? = null
    private var remote: RemoteRecording? = null
    private var remoteAt = 0L
    private var title = ""
    private var item: VodPlayable? = null

    private var lastPositionMs = 0L
    private var hasPlayed = false
    private var opened = false
    private var finished = false
    private var screenStopped = false
    private var lastSaveAt = 0L
    private var lastRefreshAt = 0L
    private var refreshing = false
    private var wasRecording = false

    private var retryAt: Long? = null
    private var replays = 0
    private var replayedAt = 0L

    /** Where the next reopening starts when a seek was made while the read was being reopened. */
    private var resumeAt: Long? = null

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch { playback.state.collect(::onState) }
    }

    private suspend fun open() {
        val now = nowMs()
        lastSaveAt = now
        lastRefreshAt = now
        if (!load(now)) return fail()
        val url = (if (peer == null) localUrl(id) else client.fileUrl(peer, id)) ?: return fail()
        if (finished) return
        val ref = VodRef(VodKind.RECORDING, playlistId = -1, key = if (peer == null) "rec:$id" else "rec:${peer.id}:$id")
        val playable = VodPlayable(ref, title, url, userAgent = null, referrer = null)
        item = playable
        wasRecording = recordingNow()
        _recording.value = wasRecording
        val start = startMs(now)
        lastPositionMs = start
        opened = true
        refreshBar(start, now)
        if (!screenStopped) playback.playVod(playable, start)
    }

    private fun fail() {
        _error.value = CANT_PLAY_RECORDING
    }

    private suspend fun load(now: Long): Boolean {
        if (peer == null) {
            val row = db.recordDao().recording(id) ?: return false
            local = row
            title = row.title
        } else {
            val r = listRemote(peer).firstOrNull { it.id == id } ?: return false
            remote = r
            remoteAt = now
            title = r.title
        }
        return true
    }

    private fun startMs(now: Long): Long {
        val length = lengthMs(now)
        if (live) return ((length ?: 0L) - LIVE_EDGE_BEHIND_MS).coerceAtLeast(0L)
        val saved = local?.positionMs ?: 0L
        // A recording watched to its end starts over.
        return if (!recordingNow() && length != null && saved >= length - 1_000) 0L else saved
    }

    private fun nowMs() = clock().toEpochMilli()

    private fun recordingNow(): Boolean = local?.state == RecordingState.RECORDING || remote?.recording == true

    /** The recorded length so far; null while unknown. */
    private fun lengthMs(now: Long): Long? {
        local?.let { r ->
            val start = r.startedAt?.toEpochMilli() ?: return null
            val end = if (r.state == RecordingState.RECORDING) now else r.endedAt?.toEpochMilli() ?: return null
            return (end - start).coerceAtLeast(0L)
        }
        return remote?.let { if (it.recording) it.durationMs + (now - remoteAt) else it.durationMs }
    }

    private fun onState(state: PlaybackState) {
        if (state is PlaybackState.Playing && state.channelId == VOD_CHANNEL_ID) hasPlayed = true
        if (state is PlaybackState.Error && state.channelId == VOD_CHANNEL_ID) {
            lastPositionMs = playback.positionMs().takeIf { it > 0 } ?: lastPositionMs
            // A growing file is read again; only a recording that gave up shows the error.
            if (!(recordingNow() && replays < MAX_REPLAYS_WITHOUT_PROGRESS)) _error.value = state.message
        } else if (state !is PlaybackState.Idle) {
            _error.value = null
        }
    }

    /** Called every few hundred milliseconds by the screen while it shows. */
    fun tick() {
        if (finished || !opened) return
        val now = nowMs()
        refreshIfDue(now)
        val mode = playback.mode.value as? PlayMode.Vod
        // A held seek is only applied while the player can seek; before that it stays pending.
        var position = playback.positionMs()
        if (mode != null && playback.state.value is PlaybackState.Playing) {
            batcher.due()?.let { offset ->
                val top = if (recordingNow()) lengthMs(now)?.minus(LIVE_EDGE_BEHIND_MS) else lengthMs(now)
                val from = if (mode.ended) maxOf(lastPositionMs, position) else position
                position = (from + offset).coerceIn(0L, (top ?: Long.MAX_VALUE).coerceAtLeast(0L))
                if (mode.ended || retryAt != null) {
                    // The read is about to be reopened: the seek becomes where it starts.
                    lastPositionMs = position
                    resumeAt = position
                } else {
                    playback.seekWithin(position)
                }
            }
        }
        retryAt?.let { at ->
            if (now >= at) replay() else refreshBar(lastPositionMs, now)
            return
        }
        val failed = playback.state.value.let { it is PlaybackState.Error && it.channelId == VOD_CHANNEL_ID }
        if (mode?.ended == true || failed) {
            if (onEndOrFailure(now, ended = mode?.ended == true)) return
        }
        if (mode == null) return
        val playing = playback.state.value is PlaybackState.Playing
        if (playing) {
            lastPositionMs = position
            if (position > replayedAt) replays = 0
        }
        if (playing && !playback.paused.value && now - lastSaveAt >= SAVE_INTERVAL_MS) save(now)
        refreshBar(position, now)
    }

    /** True when the tick has dealt with the end or failure of the read. */
    private fun onEndOrFailure(now: Long, ended: Boolean): Boolean {
        if (recordingNow()) {
            if (replays >= MAX_REPLAYS_WITHOUT_PROGRESS) return false
            retryAt = now + REPLAY_DELAY_MS
            return true
        }
        if (!ended) return false
        // Played to the end of a finished recording: next time it starts over.
        lastPositionMs = 0L
        save(now)
        playback.stop()
        exit()
        return true
    }

    private fun replay() {
        retryAt = null
        val playable = item ?: return
        replays++
        // At the end of what was written the player reports that end; the last position seen is the floor.
        val atEnd = (playback.mode.value as? PlayMode.Vod)?.ended == true
        val from = resumeAt ?: maxOf(lastPositionMs, if (atEnd) playback.positionMs() else 0L)
        resumeAt = null
        lastPositionMs = from
        replayedAt = from
        playback.playVod(playable, from)
    }

    private fun refreshIfDue(now: Long) {
        val every = if (peer == null) REFRESH_MS else REMOTE_REFRESH_MS
        if (refreshing || now - lastRefreshAt < every) return
        refreshing = true
        lastRefreshAt = now
        viewModelScope.launch {
            try {
                reload(now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep what is known; the next refresh tries again.
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun reload(now: Long) {
        if (peer == null) {
            db.recordDao().recording(id)?.let { local = it }
        } else {
            listRemote(peer).firstOrNull { it.id == id }?.let {
                remote = it
                remoteAt = now
            }
        }
        val rec = recordingNow()
        val justFinished = wasRecording && !rec
        wasRecording = rec
        _recording.value = rec
        if (justFinished) onFinished(now)
    }

    /** The recording just ended: a viewer at its end carries on with the channel. */
    private suspend fun onFinished(now: Long) {
        replays = 0
        retryAt = null
        if (!live || finished) return
        val length = lengthMs(now) ?: return
        val position = if (playback.state.value is PlaybackState.Playing) playback.positionMs() else lastPositionMs
        if (length - position > LIVE_HANDOVER_WITHIN_MS) return
        val row = local ?: return
        val channel = db.channelDao().byKey(row.sourcePlaylistId ?: row.playlistId, row.sourceChannelKey ?: row.channelKey) ?: return
        if (finished) return
        save(now)
        playback.stop()
        finished = true
        _events.tryEmit(RecordingPlayerEvent.OpenLive(channel.id))
    }

    private fun refreshBar(position: Long, now: Long) {
        _bar.value = vodBarUi(title, position, lengthMs(now), batcher.pending, playback.paused.value)
    }

    override fun onCleared() {
        // Left by anything but Back (an external pop): the file must not keep being read.
        val mode = playback.mode.value as? PlayMode.Vod
        if (!finished && mode != null && mode.ref == item?.ref) playback.stop()
    }

    /** Local recordings only; nothing before the stream has played. */
    private fun save(now: Long) {
        lastSaveAt = now
        if (peer != null || !hasPlayed) return
        if (playback.state.value is PlaybackState.Playing) lastPositionMs = playback.positionMs()
        val position = lastPositionMs
        // Not cancelled with the screen: leaving is exactly when the last position is written.
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    db.recordDao().setPosition(id, position)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Losing one save only costs a little resume accuracy.
                }
            }
        }
    }

    private fun exit() {
        if (finished) return
        finished = true
        _events.tryEmit(RecordingPlayerEvent.Exit)
    }

    fun onSeekKey(direction: Int, repeatCount: Int) {
        batcher.press(direction, repeatCount)
        refreshBar(lastPositionMs, nowMs())
    }

    fun onSeekRelease() = batcher.release()

    fun onPlayPause() {
        if (finished || !opened) return
        if (playback.paused.value) {
            playback.resume()
        } else {
            playback.pause()
            save(nowMs())
        }
        refreshBar(lastPositionMs, nowMs())
    }

    fun onBack() {
        if (finished) return
        if (opened) {
            save(nowMs())
            playback.stop()
        }
        exit()
    }

    /** Opens again where playback was, after an error. */
    fun retry() {
        if (finished) return
        val playable = item ?: return
        _error.value = null
        replays = 0
        playback.playVod(playable, lastPositionMs)
    }

    /** Coming back from the background (the stream was stopped meanwhile): continue where it was. */
    fun onScreenStarted() {
        screenStopped = false
        if (finished || !opened || playback.state.value !is PlaybackState.Idle) return
        item?.let { playback.playVod(it, lastPositionMs) }
    }

    /** The screen is going away (the stream stops after this): note where it was. */
    fun onScreenStopped() {
        screenStopped = true
        if (finished) return
        if (playback.state.value is PlaybackState.Playing) lastPositionMs = playback.positionMs()
        batcher.cancel()
        save(nowMs())
    }
}
