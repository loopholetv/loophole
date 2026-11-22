package io.github.loopholetv.record

import android.os.SystemClock
import android.util.Log
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.SeriesKind
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamResolver
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.record.storage.RecordingStorage
import io.github.loopholetv.record.storage.StorageFactory
import io.github.loopholetv.record.storage.StorageResult
import io.github.loopholetv.record.storage.recordingFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A recording being written right now. [channelId] is the channel actually recorded (a clash fallback's
 * source), which stream accounting uses; [bookedChannelId] is the viewer's own channel the recording was
 * booked on, which the player and the guide show it against. [fileName] is empty until the first byte opens the file.
 */
data class ActiveRecording(
    val id: Long,
    val channelId: Long,
    val bookedChannelId: Long,
    val host: String,
    val fileName: String,
    val storageId: String,
    val firstBytes: Boolean,
)

/** The channels these recordings were booked on. */
fun Map<Long, ActiveRecording>.bookedChannelIds(): Set<Long> = values.mapTo(HashSet()) { it.bookedChannelId }

/** Ids of the recordings booked on [channelId]. */
fun Map<Long, ActiveRecording>.idsBookedOn(channelId: Long): List<Long> =
    values.filter { it.bookedChannelId == channelId }.map { it.id }

private const val STORAGE_RETRY_STEP_MS = 10_000L
private const val MAX_NAME_SUFFIX = 999
private const val ARCHIVE_END_SLACK_MS = 60_000L
private const val OFFSET_IGNORED_MS = 5_000L
private const val TAG = "RecordingRunner"

/** What a run leaves the row as. A null [reason] keeps the row's own (started late); null [gaps] keeps its count. */
private class Terminal(
    val state: RecordingState,
    val reason: RecordingReason? = null,
    val gaps: Int? = null,
    val bytes: Long? = null,
)

/** One run's facts that the closing step needs, even when the run ends by cancellation. */
private class Run(val resume: Boolean) {
    @Volatile var store: RecordingStorage? = null
    @Volatile var fileName: String? = null
    @Volatile var format: String? = null
}

/** What the coordinator needs of the runs: what is recording, and starting, stopping and recovering them. */
interface RecordingRuns {
    val active: StateFlow<Map<Long, ActiveRecording>>

    /** How [recover] (and retries) start a run; the coordinator replaces it with its stream-budget check. */
    var runGate: suspend (id: Long, fromArchive: Boolean) -> Unit

    /** The caller has already cleared the budget. A recording already running is left alone. */
    suspend fun begin(id: Long, fromArchive: Boolean = false)

    /** Ends a run: the file is kept and the row becomes STOPPED with [reason]. */
    suspend fun stop(id: Long, reason: RecordingReason = RecordingReason.USER)

    /** Picks up what was running or due before a restart, through [runGate]. */
    suspend fun recover()
}

/**
 * Starts, finishes, recovers and retries recordings. Each run lives in [scope]; whatever ends it (the
 * recorder's result, a stop, a crash, the scope itself) moves the row out of RECORDING and tells [service].
 */
class RecordingRunner(
    db: AppDatabase,
    private val storage: StorageFactory,
    private val settings: suspend () -> AppSettings,
    private val resolver: StreamResolver,
    private val recorder: Recorder,
    private val channelFor: suspend (playlistId: Long, key: String) -> ChannelEntity?,
    private val service: RecordingServiceControl,
    private val clock: () -> Instant,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : RecordingRuns {
    private val dao = db.recordDao()
    private val playlists = db.playlistDao()
    private val running = MutableStateFlow<Map<Long, ActiveRecording>>(emptyMap())
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopReasons = ConcurrentHashMap<Long, RecordingReason>()

    override val active: StateFlow<Map<Long, ActiveRecording>> = running.asStateFlow()

    /** How [recover] and [retry] start a run; the coordinator replaces it with its stream-budget check. */
    @Volatile override var runGate: suspend (id: Long, fromArchive: Boolean) -> Unit = { id, fromArchive -> begin(id, fromArchive) }

    /** The caller (the coordinator) has already cleared the budget. A recording already running is left alone. */
    override suspend fun begin(id: Long, fromArchive: Boolean) {
        launchRun(id, fromArchive)
    }

    /** Ends a run the way the user asked: the file is kept and the row becomes STOPPED. */
    override suspend fun stop(id: Long, reason: RecordingReason) {
        val job = jobs[id]
        if (job != null) {
            stopReasons[id] = reason
            job.cancelAndJoin()
            return
        }
        val r = dao.recording(id) ?: return
        if (r.state == RecordingState.RECORDING) close(r, RecordingState.STOPPED, reason)
    }

    /**
     * After a restart: a RECORDING row nobody is running either resumes into its file (the programme is still
     * on) or is closed with the bytes found; a SCHEDULED row whose start has passed begins late or is missed.
     * Safe to call again: a row with a run is skipped, a closed row is no longer listed.
     */
    override suspend fun recover() {
        val now = clock()
        for (r in dao.inState(RecordingState.RECORDING)) {
            if (jobs.containsKey(r.id)) continue
            if (now < r.window().until) runGate(r.id, r.archiveFrom != null)
            else close(r, RecordingState.STOPPED, RecordingReason.NONE)
        }
        for (r in dao.inState(RecordingState.SCHEDULED)) {
            if (jobs.containsKey(r.id) || r.window().from > now) continue
            val startable = if (r.archiveFrom != null) {
                // A catch-up row is not late: it is wanted while the archive still holds that time.
                channelOf(r)?.catchup()?.canPlay(r.archiveFrom, now) == true
            } else {
                startTiming(r, now) != StartTiming.MISSED
            }
            if (startable) runGate(r.id, r.archiveFrom != null) else close(r, RecordingState.MISSED, RecordingReason.TV_OFF)
        }
    }

    /**
     * Records the programme again as a new row that starts now. From catch-up it fetches the whole programme
     * from the archive, and only when the channel can play that time.
     */
    suspend fun retry(id: Long, fromArchive: Boolean) {
        val old = dao.recording(id) ?: return
        if (old.state == RecordingState.SCHEDULED || old.state == RecordingState.RECORDING) return
        val now = clock()
        if (!fromArchive && old.stop <= now) return // the programme is over; live would record whatever comes next
        val fresh = old.copy(
            id = 0, start = now, state = RecordingState.SCHEDULED, reason = RecordingReason.NONE, fileName = null,
            storageId = null, bytes = 0, startedAt = null, endedAt = null, positionMs = 0, gaps = 0, createdAt = now,
            padBeforeMin = 0,
        )
        val row = if (fromArchive) {
            val channel = channelFor(old.sourcePlaylistId ?: old.playlistId, old.sourceChannelKey ?: old.channelKey)
            if (channel?.catchup()?.canPlay(old.start, now) != true) return
            fresh.copy(
                stop = now.plus(Duration.between(old.start, old.stop)), padAfterMin = DEFAULT_PAD_AFTER_MIN,
                archiveFrom = old.start, archiveUntil = old.stop,
            )
        } else {
            fresh.copy(archiveFrom = null, archiveUntil = null)
        }
        val newId = dao.insertRecording(row)
        if (newId < 0) return
        runGate(newId, fromArchive)
    }

    /** Deletes the file, then the row; false (row kept) when the file can't be reached. */
    suspend fun delete(id: Long): Boolean {
        stop(id)
        val r = dao.recording(id) ?: return true
        if (!deleteFileOf(r)) return false
        dao.deleteRecording(id)
        return true
    }

    /** Removes the file of [r]: true when it is gone (or never existed), false when its storage is no longer configured. */
    suspend fun deleteFileOf(r: RecordingEntity): Boolean {
        val name = r.fileName ?: return true
        val store = r.storageId?.let { storage.byId(it, settings()) } ?: return false
        store.delete(name)
        return true
    }

    /** Waits for every run to end. */
    internal suspend fun awaitIdle() {
        while (true) {
            val all = jobs.values.toList()
            if (all.isEmpty()) return
            all.forEach { it.join() }
        }
    }

    private suspend fun channelOf(r: RecordingEntity): ChannelEntity? {
        val source = r.sourcePlaylistId
        return if (source != null) channelFor(source, r.sourceChannelKey ?: r.channelKey) else channelFor(r.playlistId, r.channelKey)
    }

    private fun launchRun(id: Long, fromArchive: Boolean) {
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(id, fromArchive) }
        if (jobs.putIfAbsent(id, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private suspend fun execute(id: Long, fromArchive: Boolean) {
        var run = Run(false)
        var end: Terminal? = null
        var announced = false
        try {
            val row = dao.recording(id)
            // A RECORDING row nobody runs (after a restart) is picked up where it was.
            if (row == null || (row.state != RecordingState.SCHEDULED && row.state != RecordingState.RECORDING)) return
            run = Run(row.state == RecordingState.RECORDING)
            service.started(id, row.title)
            announced = true
            end = body(row, run, fromArchive)
        } catch (e: CancellationException) {
            end = Terminal(RecordingState.STOPPED, stopReasons.remove(id) ?: RecordingReason.ERROR)
            throw e
        } catch (e: Throwable) {
            end = Terminal(RecordingState.FAILED, RecordingReason.ERROR)
        } finally {
            withContext(NonCancellable) {
                try {
                    end?.let { settle(id, run, it) }
                } catch (e: Exception) {
                    // The row is closed again by the next recover(); nothing more can be done here.
                    Log.w(TAG, "Could not close a recording")
                } finally {
                    running.update { it - id }
                    stopReasons.remove(id)
                    jobs.remove(id)
                    if (announced) service.ended(id)
                }
            }
        }
    }

    private suspend fun body(row: RecordingEntity, run: Run, fromArchive: Boolean): Terminal {
        val now = clock()
        val span = row.archiveFrom?.let { from -> row.archiveUntil?.let { ArchiveRequest(from, it) } }
        val archive = span ?: if (fromArchive) ArchiveRequest(row.start, row.stop) else null
        val live = archive == null
        if (!run.resume && live && startTiming(row, now) == StartTiming.MISSED) {
            return Terminal(RecordingState.MISSED, RecordingReason.TV_OFF)
        }
        val channel = channelOf(row)
            ?: return Terminal(RecordingState.FAILED, RecordingReason.ERROR)
        if (archive != null && !run.resume && channel.catchup()?.canPlay(archive.from, now) != true) {
            return Terminal(RecordingState.FAILED, RecordingReason.ERROR)
        }
        // A resumed file is appended to; one that never got a file starts again under a new name.
        val resumeFile = run.resume && row.fileName != null
        val preferred = if (resumeFile) row.storageId else null
        if (resumeFile) {
            run.store = preferred?.let { storage.byId(it, settings()) }
            run.fileName = row.fileName
        }
        val store = acquireStorage(preferred) ?: return if (resumeFile) {
            Terminal(RecordingState.STOPPED, RecordingReason.ERROR)
        } else {
            Terminal(RecordingState.FAILED, RecordingReason.STORAGE_UNREACHABLE)
        }
        run.store = store

        val begun = clock()
        val late = !run.resume && live && startTiming(row, begun) != StartTiming.ON_TIME
        val started = row.copy(
            state = RecordingState.RECORDING,
            reason = if (late) RecordingReason.STARTED_LATE else row.reason,
            startedAt = if (run.resume) row.startedAt ?: begun else begun,
            storageId = store.id,
            gaps = if (resumeFile) row.gaps + 1 else row.gaps,
        )
        dao.updateRecording(started)
        val host = playlists.byId(channel.playlistId)?.let { providerHost(it) }.orEmpty()
        val booked = if (row.sourcePlaylistId == null) channel.id else channelFor(row.playlistId, row.channelKey)?.id ?: channel.id
        running.update { it + (row.id to ActiveRecording(row.id, channel.id, booked, host, run.fileName.orEmpty(), store.id, false)) }
        return record(started, run, channel, archive, store)
    }

    /** The storage to write to, once it passes its test; null after [STORAGE_RETRY_MS] without one. */
    private suspend fun acquireStorage(preferredId: String?): RecordingStorage? {
        val began = clock()
        while (true) {
            val s = settings()
            val candidate = if (preferredId != null) storage.byId(preferredId, s) else storage.current(s)
            if (candidate == null && preferredId != null) return null
            if (candidate != null && works(candidate)) return candidate
            if (Duration.between(began, clock()).toMillis() >= STORAGE_RETRY_MS) return null
            pause(STORAGE_RETRY_STEP_MS)
        }
    }

    private suspend fun works(s: RecordingStorage): Boolean = try {
        s.test() is StorageResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun record(
        row: RecordingEntity,
        run: Run,
        channel: ChannelEntity,
        archive: ArchiveRequest?,
        store: RecordingStorage,
    ): Terminal {
        val untilMs = AtomicLong(row.window().until.toEpochMilli())
        val archiveEnded = AtomicBoolean(false)
        val firstBytes = AtomicBoolean(false)
        val outcome = coroutineScope {
            // Extending or re-padding the recording moves the deadline at once.
            val follow = launch {
                try {
                    dao.observeRecording(row.id).collect { r -> r?.let { untilMs.set(it.window().until.toEpochMilli()) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the last known deadline.
                }
            }
            try {
                recorder.record(
                    open = {
                        if (archive == null) resolver.resolve(channel) else openArchive(row, archive, channel, archiveEnded)
                    },
                    until = {
                        if (archiveEnded.get()) 0L else elapsed() + (untilMs.get() - clock().toEpochMilli())
                    },
                    openSink = { openSink(row, run, store, firstBytes) },
                    keepAlive = { resolver.keepAlive(channel) },
                    onFormat = { run.format = it },
                    // The archive's own end is the end of the programme; only a broken connection is worth reopening.
                    onDropped = { clean -> if (archive != null && clean) archiveEnded.set(true) },
                )
            } finally {
                follow.cancel()
            }
        }
        val own = dao.recording(row.id)?.gaps ?: row.gaps
        // The archive ended (clean end, or a reopen after the whole programme) before this run got any data:
        // what the file already holds is the programme.
        if (archiveEnded.get() && outcome is RecordOutcome.Failed && (sizeOf(store, run.fileName) ?: 0L) > 0L) {
            return Terminal(RecordingState.DONE, if (own > 0) RecordingReason.GAPS else null, own)
        }

        return when (outcome) {
            is RecordOutcome.Finished -> {
                val gaps = own + outcome.gaps
                Terminal(RecordingState.DONE, if (gaps > 0) RecordingReason.GAPS else null, gaps, outcome.bytes)
            }
            is RecordOutcome.Refused -> Terminal(RecordingState.FAILED, RecordingReason.PROVIDER_BUSY)
            RecordOutcome.StorageFull -> Terminal(RecordingState.STOPPED, RecordingReason.STORAGE_FULL)
            is RecordOutcome.StorageError -> Terminal(RecordingState.STOPPED, RecordingReason.ERROR)
            is RecordOutcome.Failed -> Terminal(RecordingState.FAILED, RecordingReason.ERROR)
        }
    }

    /**
     * The archive from where the recording has got to: the wall-clock time since it started stands for what
     * was recorded. Once that covers the programme (less a minute) the archive is over and the run ends normally.
     */
    private suspend fun openArchive(
        row: RecordingEntity,
        span: ArchiveRequest,
        channel: ChannelEntity,
        ended: AtomicBoolean,
    ): ResolvedStream {
        val now = clock()
        val recorded = Duration.between(row.startedAt ?: now, now).coerceAtLeast(Duration.ZERO)
        val total = Duration.between(span.from, span.until)
        if (recorded.toMillis() >= total.toMillis() - ARCHIVE_END_SLACK_MS) {
            ended.set(true)
            throw IOException("The archive ended")
        }
        val from = if (recorded.toMillis() < OFFSET_IGNORED_MS) span.from else span.from.plus(recorded)
        return resolver.resolveArchive(channel, ArchiveRequest(from, span.until), now) ?: throw IOException("No archive")
    }

    private suspend fun openSink(row: RecordingEntity, run: Run, store: RecordingStorage, firstBytes: AtomicBoolean): RecordSink {
        val name = run.fileName ?: chooseName(row, store, run.format ?: "ts").also { chosen ->
            run.fileName = chosen
            dao.recording(row.id)?.let { dao.updateRecording(it.copy(fileName = chosen)) }
            running.update { m -> m[row.id]?.let { m + (row.id to it.copy(fileName = chosen)) } ?: m }
        }
        val out = try {
            store.openAppend(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (isNoSpace(e)) StorageFullException(e) else e
        }
        return RecordSink(out) {
            if (firstBytes.compareAndSet(false, true)) {
                running.update { m -> m[row.id]?.let { m + (row.id to it.copy(firstBytes = true)) } ?: m }
            }
        }
    }

    /** The name from the booking, with " (2)", " (3)" before the extension while a file of that name exists. */
    private suspend fun chooseName(row: RecordingEntity, store: RecordingStorage, ext: String): String {
        val base = recordingFileName(row.channelName, row.title, row.start, zone, ext)
        if (store.size(base) == null) return base
        val stem = base.removeSuffix(".$ext")
        for (n in 2..MAX_NAME_SUFFIX) {
            val candidate = "$stem ($n).$ext"
            if (store.size(candidate) == null) return candidate
        }
        return "$stem (${System.currentTimeMillis()}).$ext"
    }

    /** Writes the end state of a run, then trims the series. */
    private suspend fun settle(id: Long, run: Run, t: Terminal) {
        val r = dao.recording(id) ?: return
        val size = sizeOf(run.store, run.fileName)
        dao.updateRecording(
            r.copy(
                state = t.state, reason = t.reason ?: r.reason, gaps = t.gaps ?: r.gaps,
                endedAt = clock(), bytes = size ?: t.bytes ?: r.bytes,
            ),
        )
        keepLast(r)
    }

    /** Closes a row nobody is running, with the bytes its file has. */
    private suspend fun close(r: RecordingEntity, state: RecordingState, reason: RecordingReason) {
        val store = r.storageId?.let { storage.byId(it, settings()) }
        val size = sizeOf(store, r.fileName)
        val current = dao.recording(r.id) ?: return
        dao.updateRecording(current.copy(state = state, reason = reason, endedAt = clock(), bytes = size ?: current.bytes))
        keepLast(r)
    }

    private suspend fun sizeOf(store: RecordingStorage?, name: String?): Long? {
        if (store == null || name == null) return null
        return try {
            store.size(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes the oldest finished episodes of the row's series beyond its "keep the last". */
    private suspend fun keepLast(r: RecordingEntity) {
        val seriesId = r.seriesId ?: return
        val rule = dao.series(seriesId)?.takeIf { it.kind == SeriesKind.RECORD } ?: return
        for (old in keepLastDeletions(dao.seriesEpisodes(seriesId), rule.keepLast)) {
            try {
                if (!deleteFileOf(old)) continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // the row stays, so the next run tries again
            }
            dao.deleteRecording(old.id)
        }
    }
}
