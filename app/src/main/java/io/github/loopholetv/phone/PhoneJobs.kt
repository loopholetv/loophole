package io.github.loopholetv.phone

import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.errorCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PhoneJobKind { ADD, EDIT, REFRESH }

sealed interface PhoneJobPhase {
    data class LoadingChannels(val count: Int) : PhoneJobPhase
    data class LoadingGuide(val programmes: Int) : PhoneJobPhase
    data class Done(val playlistId: Long) : PhoneJobPhase

    /** [message] is fixed copy only, never an exception's message. */
    data class Failed(val message: String) : PhoneJobPhase
}

/** A playlist load started from the phone; [playlistId] is null for an add until it is done. */
data class PhoneJob(
    val id: Long,
    val kind: PhoneJobKind,
    val playlistId: Long?,
    val name: String,
    val phase: PhoneJobPhase,
)

/**
 * A running job's progress callback: call it with each new phase. [claim] marks the playlist an add
 * just created as the job's, so it counts as busy (and shows on [PhoneJobs.current]) until the job
 * ends.
 */
class PhoneJobProgress internal constructor(
    private val onPhase: (PhoneJobPhase) -> Unit,
    private val onClaim: (Long) -> Unit,
) : (PhoneJobPhase) -> Unit {
    override fun invoke(phase: PhoneJobPhase) = onPhase(phase)

    fun claim(playlistId: Long) = onClaim(playlistId)
}

/**
 * Playlist loads started from the phone, run one at a time in the order they came, on [scope] (the
 * app-wide scope): a phone closing its page never cancels one. A job that throws (a stray
 * cancellation included) ends [PhoneJobPhase.Failed] with fixed copy and the queue carries on;
 * nothing is logged.
 */
class PhoneJobs(private val scope: CoroutineScope, private val keepFinishedMs: Long = KEEP_FINISHED_MS) {
    private class Entry(
        val id: Long,
        val kind: PhoneJobKind,
        val playlistId: Long?,
        val name: String,
        val run: suspend (progress: PhoneJobProgress) -> PhoneJobPhase,
    )

    private val _current = MutableStateFlow<PhoneJob?>(null)

    /** The running job, or the last finished one for 30 s after it ends. */
    val current: StateFlow<PhoneJob?> = _current.asStateFlow()

    private val _queued = MutableStateFlow(0)

    /** Jobs waiting behind the running one. */
    val queued: StateFlow<Int> = _queued.asStateFlow()

    private val waiting = Channel<Entry>(Channel.UNLIMITED)
    private var nextId = 1L // Guarded by this.
    private val busy = HashMap<Long, Int>() // Playlist id → running or queued jobs; guarded by this.
    private var clearFinished: Job? = null // Only touched by the worker.

    init {
        scope.launch { for (entry in waiting) runEntry(entry) }
    }

    /** Queues [run]; it gets a progress callback and returns the final phase. Returns the job's id. */
    fun enqueue(
        kind: PhoneJobKind,
        playlistId: Long?,
        name: String,
        run: suspend (progress: PhoneJobProgress) -> PhoneJobPhase,
    ): Long {
        val entry = synchronized(this) {
            val id = nextId++
            if (playlistId != null) hold(playlistId)
            _queued.update { it + 1 }
            Entry(id, kind, playlistId, name, run)
        }
        waiting.trySend(entry)
        return entry.id
    }

    /** Whether a job on [playlistId] is running or queued. */
    fun isBusy(playlistId: Long): Boolean = synchronized(this) { playlistId in busy }

    private suspend fun runEntry(e: Entry) {
        clearFinished?.cancel()
        // Current first: a reader that sees the queue shorter then always sees this job running.
        _current.value = PhoneJob(e.id, e.kind, e.playlistId, e.name, PhoneJobPhase.LoadingChannels(0))
        _queued.update { it - 1 }
        var claimed: Long? = null
        val progress = PhoneJobProgress(
            onPhase = { phase -> _current.update { if (it?.id == e.id) it.copy(phase = phase) else it } },
            onClaim = { id ->
                synchronized(this) {
                    if (claimed == null && id != e.playlistId) {
                        claimed = id
                        hold(id)
                    }
                }
                _current.update { if (it?.id == e.id) it.copy(playlistId = id) else it }
            },
        )
        var end: PhoneJobPhase = PhoneJobPhase.Failed(errorCopy(ImportError.Unexpected))
        try {
            end = runSafely(e, progress)
        } finally {
            finish(e, end, synchronized(this) { claimed })
        }
    }

    /** The job's end; only a cancelled [scope] gets past this. */
    private suspend fun runSafely(e: Entry, progress: PhoneJobProgress): PhoneJobPhase = try {
        e.run(progress)
    } catch (_: CancellationException) {
        // A cancellation that isn't this scope's own is a failure like any other.
        currentCoroutineContext().ensureActive()
        PhoneJobPhase.Failed(errorCopy(ImportError.Unexpected))
    } catch (_: Throwable) {
        PhoneJobPhase.Failed(errorCopy(ImportError.Unexpected))
    }

    private fun finish(e: Entry, end: PhoneJobPhase, claimed: Long?) {
        _current.update { job ->
            if (job?.id != e.id) return@update job
            val playlistId = (end as? PhoneJobPhase.Done)?.playlistId ?: job.playlistId
            job.copy(playlistId = playlistId, phase = end)
        }
        synchronized(this) {
            e.playlistId?.let(::release)
            claimed?.let(::release)
        }
        clearFinished = scope.launch {
            delay(keepFinishedMs)
            _current.update { if (it?.id == e.id) null else it }
        }
    }

    /** Callers hold the lock. */
    private fun hold(playlistId: Long) {
        busy[playlistId] = (busy[playlistId] ?: 0) + 1
    }

    /** Callers hold the lock. */
    private fun release(playlistId: Long) {
        val left = (busy[playlistId] ?: 1) - 1
        if (left > 0) busy[playlistId] = left else busy.remove(playlistId)
    }

    private companion object {
        const val KEEP_FINISHED_MS = 30_000L
    }
}
