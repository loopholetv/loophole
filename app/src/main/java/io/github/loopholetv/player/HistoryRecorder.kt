package io.github.loopholetv.player

import android.os.SystemClock
import io.github.loopholetv.data.db.ChannelEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** A channel enters History once it has played this long (the guide preview counts too). */
const val HISTORY_MIN_PLAY_MS = 10_000L

/**
 * Records a channel in History once it has been [PlaybackState.Playing] for [HISTORY_MIN_PLAY_MS]
 * within one viewing, i.e. without switching to another channel or [PlaybackState.Idle] meanwhile.
 * A viewing is keyed on the channel id: [PlaybackState.Buffering] / [PlaybackState.Error] of the
 * same channel pause the count without resetting it, and a re-emitted Playing (stream tags
 * changed, behind-live-window recovery) changes nothing. Records at most once per viewing and
 * never throws (a failed write is dropped).
 */
class HistoryRecorder(
    private val state: StateFlow<PlaybackState>,
    private val channelById: suspend (Long) -> ChannelEntity?,
    private val record: suspend (ChannelEntity) -> Unit,
    /** Only measures accumulated Playing time; waiting itself uses [delay]. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val started = AtomicBoolean(false)

    // Touched only from the collector; collectLatest runs one block at a time.
    private var viewingId: Long? = null
    private var playedMs = 0L
    private var playingSince: Long? = null
    private var recorded = false

    /** Starts watching [state] in [scope]; null if already started. */
    fun start(scope: CoroutineScope): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch { state.collectLatest(::onState) }
    }

    private suspend fun onState(s: PlaybackState) {
        val id = when (s) {
            PlaybackState.Idle -> null
            is PlaybackState.Buffering -> s.channelId
            is PlaybackState.Playing -> s.channelId
            is PlaybackState.Error -> s.channelId
        }
        if (id != viewingId) {
            viewingId = id
            playedMs = 0L
            playingSince = null
            recorded = false
        }
        if (id == null || recorded) return
        if (s !is PlaybackState.Playing) {
            playingSince?.let { playedMs += clock() - it }
            playingSince = null
            return
        }
        val since = playingSince ?: clock().also { playingSince = it }
        val remaining = HISTORY_MIN_PLAY_MS - playedMs - (clock() - since)
        if (remaining > 0) delay(remaining)
        recorded = true
        // A later emission (e.g. new tags) must not cut the write short.
        withContext(NonCancellable) { save(id) }
    }

    private suspend fun save(id: Long) {
        try {
            channelById(id)?.let { record(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // History is a convenience: a failed write is not worth surfacing.
        }
    }
}
