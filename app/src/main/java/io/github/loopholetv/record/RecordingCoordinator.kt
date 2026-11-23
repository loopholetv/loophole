package io.github.loopholetv.record

import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PreviewHold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** What the coordinator asks the viewer. */
sealed interface RecordPrompt {
    /** A recording is about to take the stream from the player or the cast ([stops]). */
    data class Warning(val id: Long, val channel: String, val title: String, val stops: Use, val secondsLeft: Int) : RecordPrompt

    /** A recording holds the stream that playing [pending] (null: a film or episode) would need. */
    data class HoldsStream(val id: Long, val title: String, val until: Instant, val pending: ChannelEntity?) : RecordPrompt
}

/** The viewer should now watch recording [recordingId] at its live edge. */
data class Handover(val recordingId: Long)

/**
 * Recording [recordingId] took the stream from the viewer but ended before it wrote anything. [channelId] is
 * the channel that was being watched (null for a cast or the preview), to open again.
 */
data class TakeFailed(val recordingId: Long, val channelId: Long?)

enum class PromptChoice { KEEP_WATCHING, SWITCH_NOW, STOP_RECORDING, CANCEL }

/** How long a run that reached its deadline gets to end on its own before the STOP alarm stops it. */
private const val STOP_GRACE_MS = 30_000L
private const val TICK_MS = 1_000L

/** How long a START alarm keeps the TV awake for the run to reach the recorder (the service then holds it). */
private const val START_LOCK_MS = 60_000L

/** A handover or failed take-over still unconsumed after this long is dropped. */
private const val STALE_SIGNAL_MS = 60_000L

/** How long a play the gate allowed counts as the player's stream before the player shows it. */
private const val PENDING_PLAY_MS = 5_000L

private val LIVE_STATES = setOf(RecordingState.SCHEDULED, RecordingState.RECORDING)

/** Where a booking records from, and that provider's limit on this TV. */
private class Target(val channel: ChannelEntity, val host: String, val limit: Int)

/** A user play waiting for an answer to [RecordPrompt.HoldsStream]. */
private class HoldWait(val id: Long, val answer: CompletableDeferred<PromptChoice>)

/** A play the gate allowed, on [playlistId], that the player may not show yet. */
private class PendingPlay(val playlistId: Long, val channelId: Long?, val at: Instant)

/** A decision, and whether a warning already pending on the provider is what leaves no room. */
private class Decision(val start: StartDecision, val heldByWarning: Boolean)

/**
 * Lets recordings take a provider stream only within the provider's Connections allowed on this TV. Under the
 * limit a recording just opens its own stream. Over it, whatever holds the stream lets go first (the preview
 * silently, the player or a cast after a 30 s warning, the watched channel by handing the viewer over to the
 * recording), and only then does the recorder open. A play started by the user while recordings fill the
 * limit asks first.
 *
 * Every warning and question is answered once: the first answer (or the timeout) wins and the rest are
 * ignored. A pending warning already counts as the stream it will take, so a second booking that would need
 * the same stream is missed (clash) instead of warning too. PREPARE (30 s ahead) only shows the warning a
 * take-over needs; START (the padded start) begins the run. Each booking leaves SCHEDULED when its START
 * is handled, and RECORDING when its STOP is, and the alarms are set again after every change, so nothing
 * handled fires again. A recovered RECORDING row that is not resumed (Keep watching, or no room) is
 * closed STOPPED, with reason USER or CLASH, keeping what it recorded.
 *
 * [scope] runs on the main thread in the app: [playback], [previewHold] and [cast] are used from it.
 */
class RecordingCoordinator(
    private val runner: RecordingRuns,
    db: AppDatabase,
    private val uses: StreamUses,
    private val playback: Playback,
    private val previewHold: PreviewHold,
    private val cast: CastControls,
    private val evictIdle: suspend () -> Unit,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    /** Sets the alarms again from the rows; called after each change of a booking's state. */
    private val rearm: suspend () -> Unit = {},
    /** Started from a START alarm, so the foreground service starts while the alarm allows it. */
    private val service: RecordingServiceControl = RecordingServiceControl.NONE,
    private val startLock: StartWakeLock = StartWakeLock.NONE,
) : AlarmHandler {
    private val dao = db.recordDao()
    private val channels = db.channelDao()
    private val playlists = db.playlistDao()

    /** True while any recording is running. */
    val isRecording: Boolean get() = runner.active.value.isNotEmpty()

    private val _prompt = MutableStateFlow<RecordPrompt?>(null)
    val prompt: StateFlow<RecordPrompt?> = _prompt.asStateFlow()

    private val _handover = MutableStateFlow<Handover?>(null)

    /** Set until [consumeHandover]: the viewer should be taken to this recording. */
    val handover: StateFlow<Handover?> = _handover.asStateFlow()

    private val _takeFailed = MutableStateFlow<TakeFailed?>(null)

    /** Set until [consumeTakeFailed]: a take-over stopped the viewer's stream and then failed to record. */
    val takeFailed: StateFlow<TakeFailed?> = _takeFailed.asStateFlow()

    private val _previewTaken = MutableStateFlow<String?>(null)

    /** The recording's channel name while a recording took the preview's stream. */
    val previewTaken: StateFlow<String?> = _previewTaken.asStateFlow()
    @Volatile private var previewTakenBy: Long? = null

    /**
     * Bookings being started or run from here (a warning shown, a decision under way, a run): nothing else
     * starts them. Changed together with [warnings], [clashes] and [startHolds] under [claims].
     */
    private val handled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Bookings whose PREPARE found another booking's warning holding the stream: their START misses them. */
    private val clashes: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Bookings whose START took a wake lock and started the service from here, until their start settles. */
    private val startHolds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val claims = Any()

    /** Runs begun but not yet listed by the runner: they already count against the limit. */
    private val reserved = ConcurrentHashMap<Long, StreamUse>()

    /** Bookings showing a warning: each counts as the stream it is about to take. */
    private val warningTakes = ConcurrentHashMap<Long, StreamUse>()

    /** Bookings whose STOP is being handled; a repeated STOP alarm meanwhile is ignored. */
    private val stopping: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val pendingPlay = AtomicReference<PendingPlay?>(null)

    /** One decision (and the take-over it leads to) at a time, so two starts can't both see room. */
    private val budget = Mutex()
    private val warnings = ConcurrentHashMap<Long, CompletableDeferred<PromptChoice>>()
    private val holding = AtomicReference<HoldWait?>(null)
    @Volatile private var handoverAt: Instant = Instant.EPOCH
    @Volatile private var takeFailedAt: Instant = Instant.EPOCH

    /**
     * Returns once what must happen while the alarm keeps the TV awake is done: PREPARE's decision (a
     * warning is then shown on [scope]), and START's wake lock and service start. The run itself goes on
     * on [scope].
     */
    override suspend fun onAlarm(action: AlarmAction, id: Long) {
        when (action) {
            AlarmAction.PREPARE -> scope.launch { prepareWarning(id) }.join()
            AlarmAction.START -> scope.launch { startAtAlarm(id) }.join()
            AlarmAction.STOP -> if (stopping.add(id)) scope.launch { stopAtDeadline(id) }
            AlarmAction.REMIND -> Unit
        }
    }

    override suspend fun recover() = runner.recover()

    /**
     * The budget-checked start for recovery and retries; returns at once. [fromArchive] null: as the row says.
     */
    fun startBudgeted(id: Long, fromArchive: Boolean? = null) {
        if (!synchronized(claims) { handled.add(id) }) return
        scope.launch { startNow(id, fromArchive) }
    }

    /**
     * Takes the handover to act on, once: null when there is none, or when it is stale (its recording is no
     * longer recording, or it waited more than [STALE_SIGNAL_MS]).
     */
    suspend fun consumeHandover(): Handover? {
        val h = _handover.value ?: return null
        val at = handoverAt
        if (!_handover.compareAndSet(h, null)) return null
        if (clock().toEpochMilli() - at.toEpochMilli() > STALE_SIGNAL_MS) return null
        return h.takeIf { dao.recording(it.recordingId)?.state == RecordingState.RECORDING }
    }

    /** As [consumeHandover], for a failed take-over: null when there is none or it waited too long. */
    fun consumeTakeFailed(): TakeFailed? {
        val f = _takeFailed.value ?: return null
        val at = takeFailedAt
        if (!_takeFailed.compareAndSet(f, null)) return null
        return f.takeIf { clock().toEpochMilli() - at.toEpochMilli() <= STALE_SIGNAL_MS }
    }

    private fun handOver(id: Long) {
        handoverAt = clock()
        _handover.value = Handover(id)
    }

    /**
     * Answers the shown prompt for recording [id]; only the first answer to each prompt counts. Keep watching
     * skips the booking (a recovered recording is stopped instead, keeping its file).
     */
    fun answer(id: Long, choice: PromptChoice) {
        when (choice) {
            PromptChoice.KEEP_WATCHING, PromptChoice.SWITCH_NOW -> warnings[id]?.complete(choice)
            PromptChoice.STOP_RECORDING, PromptChoice.CANCEL ->
                holding.get()?.takeIf { it.id == id }?.answer?.complete(choice)
        }
    }

    /**
     * Whether [channel] may be opened now. When recordings fill its provider's limit it asks
     * ([RecordPrompt.HoldsStream]) and waits: true once the recording was stopped, false on Cancel or when
     * another play replaced this one. Opening live ([live]) the channel being recorded hands the viewer
     * over to the recording instead, and returns false.
     */
    suspend fun beforeUserPlay(channel: ChannelEntity, live: Boolean = true): Boolean {
        // A newer play makes any question still open stale: its caller goes on with false.
        cancelQuestion()
        // Without a recording nothing is looked up, so the play goes ahead without suspending.
        val allowed = nothingRecording() || run {
            val playlist = playlists.byId(channel.playlistId) ?: return@run true
            gate(providerHost(playlist), playlist.effectiveConnections(), channel.id.takeIf { live }, channel)
        }
        if (allowed) pendingPlay.set(PendingPlay(channel.playlistId, channel.id, clock()))
        return allowed
    }

    /** As [beforeUserPlay], for a film or episode of [ref]'s playlist. */
    suspend fun beforeUserPlayVod(ref: VodRef): Boolean {
        cancelQuestion()
        val allowed = nothingRecording() || run {
            val playlist = playlists.byId(ref.playlistId) ?: return@run true
            gate(providerHost(playlist), playlist.effectiveConnections(), null, null)
        }
        if (allowed) pendingPlay.set(PendingPlay(ref.playlistId, null, clock()))
        return allowed
    }

    // User plays

    private fun nothingRecording() = runner.active.value.isEmpty() && reserved.isEmpty()

    private fun cancelQuestion() {
        holding.getAndSet(null)?.answer?.complete(PromptChoice.CANCEL)
    }

    private suspend fun gate(host: String, limit: Int, liveChannelId: Long?, pending: ChannelEntity?): Boolean {
        // What this TV plays or casts now is released by the new play, so only recordings count.
        val held = recordingsOn(host)
        if (held.size < limit) return true
        held.firstOrNull { liveChannelId != null && it.channelId == liveChannelId }?.let {
            handOver(it.id)
            return false
        }
        return askToStop(held.first().id, pending)
    }

    private fun recordingsOn(host: String): List<ActiveRecording> {
        val active = runner.active.value
        val starting = reserved.filterKeys { it !in active }
            .map { (id, use) -> ActiveRecording(id, use.channelId ?: -1, use.channelId ?: -1, use.host, "", "", false) }
        return (active.values + starting).filter { it.host == host }
    }

    private suspend fun askToStop(id: Long, pending: ChannelEntity?): Boolean {
        val r = dao.recording(id)
        val wait = HoldWait(id, CompletableDeferred())
        // A newer play replaces one still asking.
        holding.getAndSet(wait)?.answer?.complete(PromptChoice.CANCEL)
        val shown = RecordPrompt.HoldsStream(id, r?.title.orEmpty(), r?.window()?.until ?: clock(), pending)
        _prompt.value = shown
        val choice = try {
            wait.answer.await()
        } finally {
            holding.compareAndSet(wait, null)
            _prompt.compareAndSet(shown, null)
        }
        if (choice != PromptChoice.STOP_RECORDING) return false
        runner.stop(id, RecordingReason.USER)
        evictIdle()
        return true // the caller records the play as pending (see beforeUserPlay)
    }

    // PREPARE and START

    /**
     * The warning's decision, 30 s before the padded start: when the take-over will need a warning, it is
     * shown now and counts as the stream it will take. Anything else is left to START.
     */
    private suspend fun prepareWarning(id: Long) {
        val r = dao.recording(id) ?: return
        if (r.state != RecordingState.SCHEDULED || runner.active.value.containsKey(id)) return
        // No channel to record: START fails it.
        val target = targetOf(r) ?: return
        var answer: CompletableDeferred<PromptChoice>? = null
        var stops: Use? = null
        budget.withLock {
            if (synchronized(claims) { id in handled || id in clashes }) return
            val d = decideAndHold(r, target)
            synchronized(claims) {
                val start = d.start
                when {
                    // START came meanwhile, and decides on its own.
                    id in handled -> warningTakes.remove(id)
                    // Another booking's warning will take the only stream: this one clashes with it, unseen.
                    d.heldByWarning -> clashes.add(id)
                    start is StartDecision.Warn -> {
                        handled.add(id)
                        answer = openWarning(id)
                        stops = start.stops
                    }
                    else -> Unit
                }
            }
        }
        val a = answer ?: return
        val w = stops ?: return
        val from = r.window().from
        val now = clock()
        val deadline = if (from > now) from else now.plusMillis(WARNING_LEAD_MS)
        scope.launch {
            var begun = false
            try {
                begun = warnThenTake(r, target, w, deadline, a, r.archiveFrom != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!begun) withContext(NonCancellable) { failIfScheduled(id) }
            } finally {
                settleStart(id, begun)
            }
        }
    }

    /**
     * The padded start. While the alarm still keeps the TV awake it takes a wake lock and starts the service,
     * then begins the run on [scope]: a warning still showing is answered as by its timeout, a clash found by
     * PREPARE is missed, and otherwise (PREPARE decided no warning was needed, or never fired) it decides now.
     */
    private suspend fun startAtAlarm(id: Long) {
        val r = dao.recording(id) ?: return
        if (r.state != RecordingState.SCHEDULED || runner.active.value.containsKey(id)) return
        var warning: CompletableDeferred<PromptChoice>? = null
        var clash = false
        var decide = false
        synchronized(claims) {
            warning = warnings[id]
            if (warning == null && handled.add(id)) {
                if (clashes.remove(id)) clash = true else decide = true
            }
            // Whoever handles the start ends this hold when the start settles (see unclaim).
            if (startHolds.add(id)) {
                startLock.hold(id, START_LOCK_MS)
                service.started(id, r.title)
            }
        }
        when {
            warning != null -> warning?.complete(PromptChoice.SWITCH_NOW)
            clash -> scope.launch {
                try {
                    giveUp(id, RecordingState.MISSED, RecordingReason.CLASH)
                } finally {
                    withContext(NonCancellable) { unclaim(id) }
                }
            }
            decide -> scope.launch { startNow(id, null) }
        }
    }

    /** Decides for [id] now and acts on it: starts, takes the stream, warns first, or misses it (clash). */
    private suspend fun startNow(id: Long, fromArchiveHint: Boolean?) {
        var begun = false
        try {
            val r = dao.recording(id) ?: return
            if (r.state !in LIVE_STATES || runner.active.value.containsKey(id)) return
            val fromArchive = fromArchiveHint ?: (r.archiveFrom != null)
            val target = targetOf(r)
            if (target == null) {
                // The runner fails a booking whose channel is gone; it needs no stream for that.
                begun = true
                beginRun(r, null, fromArchive)
                return
            }
            var answer: CompletableDeferred<PromptChoice>? = null
            var stops: Use? = null
            budget.withLock {
                val d = decideAndHold(r, target)
                val start = d.start
                when {
                    // Another booking's warning will take the only stream: this one clashes with it.
                    d.heldByWarning -> giveUp(id, RecordingState.MISSED, RecordingReason.CLASH)
                    start is StartDecision.Warn -> {
                        answer = synchronized(claims) { openWarning(id) }
                        stops = start.stops
                    }
                    else -> begun = take(r, target, start, fromArchive)
                }
            }
            val a = answer ?: return
            val w = stops ?: return
            begun = warnThenTake(r, target, w, clock().plusMillis(WARNING_LEAD_MS), a, fromArchive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!begun) withContext(NonCancellable) { failIfScheduled(id) }
        } finally {
            settleStart(id, begun)
        }
    }

    /** Called with [claims] held. */
    private fun openWarning(id: Long): CompletableDeferred<PromptChoice> =
        CompletableDeferred<PromptChoice>().also { warnings[id] = it }

    /** A start that ends without a run gives up what it held; a begun run frees it when it ends (see [watch]). */
    private fun settleStart(id: Long, begun: Boolean) {
        warningTakes.remove(id)
        if (!begun) {
            reserved.remove(id)
            unclaim(id)
        }
    }

    /** Lets the booking be started again, and ends its START hold (wake lock and service) if it has one. */
    private fun unclaim(id: Long) = synchronized(claims) {
        handled.remove(id)
        if (startHolds.remove(id)) {
            startLock.release(id)
            service.ended(id)
        }
    }

    private suspend fun targetOf(r: RecordingEntity): Target? {
        val playlistId = r.sourcePlaylistId ?: r.playlistId
        val channel = channels.byKey(playlistId, r.sourceChannelKey ?: r.channelKey) ?: return null
        val playlist = playlists.byId(playlistId) ?: return null
        return Target(channel, providerHost(playlist), playlist.effectiveConnections())
    }

    private suspend fun currentUses(): List<StreamUse> {
        val listed = uses.current()
        val active = runner.active.value
        return listed + listOfNotNull(pendingPlayUse(listed)) + reserved.filterKeys { it !in active }.values
    }

    /** A play the gate allowed, as the player's stream, until the player shows it or [PENDING_PLAY_MS] pass. */
    private suspend fun pendingPlayUse(listed: List<StreamUse>): StreamUse? {
        val p = pendingPlay.get() ?: return null
        if (clock().toEpochMilli() - p.at.toEpochMilli() >= PENDING_PLAY_MS) {
            pendingPlay.compareAndSet(p, null)
            return null
        }
        val host = playlists.byId(p.playlistId)?.let { providerHost(it) } ?: return null
        if (listed.any { it.host == host && it.what != Use.RECORDING }) {
            pendingPlay.compareAndSet(p, null)
            return null
        }
        return StreamUse(host, Use.PLAYER, p.channelId)
    }

    /**
     * Decides for [r]; a Warn is held at once as the stream it will take (so the next booking sees it).
     * Called with [budget] held.
     */
    private suspend fun decideAndHold(r: RecordingEntity, t: Target): Decision {
        val d = decideWith(t, r.id)
        if (d.start is StartDecision.Warn) warningTakes[r.id] = StreamUse(t.host, Use.RECORDING, t.channel.id)
        return d
    }

    /** Other bookings' pending warnings count as recordings that already took the viewer's stream. */
    private suspend fun decideWith(t: Target, self: Long?): Decision {
        val all = currentUses()
        val warned = warningTakes.filter { (id, use) -> id != self && use.host == t.host }.values
        if (warned.isNotEmpty()) {
            val after = all.filterNot { it.host == t.host && it.what != Use.RECORDING } + warned
            val d = decideStart(t.host, t.limit, after, t.channel.id)
            return if (d == StartDecision.OverLimit) Decision(d, heldByWarning = true) else Decision(d, false)
        }
        return Decision(decideMarked(t, all), false)
    }

    private fun decideMarked(t: Target, now: List<StreamUse>): StartDecision {
        val d = decideStart(t.host, t.limit, now, t.channel.id)
        // The recorded channel only in the guide preview: the preview gives way, nobody is handed over.
        val previewOnly = d is StartDecision.TakeWatched && now.none {
            it.host == t.host && it.channelId == d.channelId && it.what != Use.RECORDING && it.what != Use.PREVIEW
        }
        return if (previewOnly) StartDecision.TakePreview else d
    }

    /**
     * Acts on [d] at the padded start; true when the run was begun. Taking from the player or a cast (the
     * watched channel, or after a warning) stops it and then hands the viewer over to the recording.
     */
    private suspend fun take(r: RecordingEntity, t: Target, d: StartDecision, fromArchive: Boolean): Boolean {
        // Counted from here, so a play asked for while the viewer's stream is being released already sees it.
        if (d != StartDecision.OverLimit) reserved[r.id] = StreamUse(t.host, Use.RECORDING, t.channel.id)
        when (d) {
            StartDecision.Start -> beginRun(r, t, fromArchive)
            StartDecision.TakePreview -> {
                previewHold.hold()
                playback.stop()
                evictIdle()
                beginRun(r, t, fromArchive)
                previewTakenBy = r.id
                _previewTaken.value = r.channelName
            }
            is StartDecision.TakeWatched, is StartDecision.Warn -> {
                // What the viewer loses, to open again should the recording fail: not a cast or the preview.
                val watched = currentUses().firstOrNull { it.host == t.host && it.what == Use.PLAYER }?.channelId
                // A cast is ended without reopening playback; the player's own stream is released next.
                if (cast.state.value is CastState.Casting) cast.stopCasting(StopReason.RECORDING)
                playback.stop()
                evictIdle()
                beginRun(r, t, fromArchive)
                scope.launch { handOverOnceRecording(r.id, watched) }
            }
            StartDecision.OverLimit -> {
                giveUp(r.id, RecordingState.MISSED, RecordingReason.CLASH)
                return false
            }
        }
        return true
    }

    private suspend fun warnThenTake(
        r: RecordingEntity,
        t: Target,
        stops: Use,
        deadline: Instant,
        answer: CompletableDeferred<PromptChoice>,
        fromArchive: Boolean,
    ): Boolean {
        // The warning takes the prompt's place: a question it hides could never be answered, so it counts as Cancel.
        holding.get()?.answer?.complete(PromptChoice.CANCEL)
        _prompt.value = RecordPrompt.Warning(r.id, r.channelName, r.title, stops, secondsUntil(deadline))
        val ticker = scope.launch {
            while (true) {
                val left = deadline.toEpochMilli() - clock().toEpochMilli()
                if (left <= 0) break
                val seconds = ((left + TICK_MS - 1) / TICK_MS).toInt()
                _prompt.update { p -> if (p is RecordPrompt.Warning && p.id == r.id) p.copy(secondsLeft = seconds) else p }
                delayMs(left - (seconds - 1) * TICK_MS)
            }
            // No answer: the recording takes the stream.
            answer.complete(PromptChoice.SWITCH_NOW)
        }
        val choice = try {
            answer.await()
        } finally {
            ticker.cancel()
            synchronized(claims) { warnings.remove(r.id, answer) }
            _prompt.update { p -> if (p is RecordPrompt.Warning && p.id == r.id) null else p }
        }
        if (choice == PromptChoice.KEEP_WATCHING) {
            budget.withLock { warningTakes.remove(r.id) }
            giveUp(r.id, RecordingState.SKIPPED, RecordingReason.USER)
            return false
        }
        // Decided again: the player may have stopped, or moved to the recorded channel, during the warning.
        // The held take goes in the same step as the real one, so nobody counts the stream twice.
        return budget.withLock {
            warningTakes.remove(r.id)
            take(r, t, decideWith(t, r.id).start, fromArchive)
        }
    }

    private fun secondsUntil(deadline: Instant): Int {
        val left = (deadline.toEpochMilli() - clock().toEpochMilli()).coerceAtLeast(0)
        return ((left + TICK_MS - 1) / TICK_MS).toInt()
    }

    private suspend fun beginRun(r: RecordingEntity, t: Target?, fromArchive: Boolean) {
        if (t != null) reserved[r.id] = StreamUse(t.host, Use.RECORDING, t.channel.id)
        watch(r.id)
        runner.begin(r.id, fromArchive)
    }

    /** Follows the row until it ends: sets the alarms again on each change and frees what this run held. */
    private fun watch(id: Long) {
        scope.launch {
            try {
                dao.observeRecording(id).map { it?.state }.distinctUntilChanged()
                    .transformWhile { s ->
                        emit(s)
                        s in LIVE_STATES
                    }
                    .collect { s ->
                        // Recording now: the service holds the TV awake from here.
                        if (s == RecordingState.RECORDING) startLock.release(id)
                        if (s != RecordingState.SCHEDULED) rearmQuietly()
                    }
            } finally {
                reserved.remove(id)
                unclaim(id)
                if (previewTakenBy == id) {
                    previewTakenBy = null
                    _previewTaken.value = null
                }
            }
        }
    }

    /** Hands over once the file has its first bytes; if the run ends first, reports [TakeFailed]. */
    private suspend fun handOverOnceRecording(id: Long, watched: Long?) {
        var failed = false
        val recording = combine(runner.active, dao.observeRecording(id)) { active, row ->
            when {
                active[id]?.firstBytes == true -> true
                // Stopped by the viewer: nothing failed, so nothing to report.
                row != null && row.state == RecordingState.STOPPED && row.reason == RecordingReason.USER -> false
                row == null || row.state !in LIVE_STATES -> null.also { failed = true }
                else -> null
            }
        }.first { it != null || failed }
        when {
            recording == true -> handOver(id)
            failed -> {
                takeFailedAt = clock()
                _takeFailed.value = TakeFailed(id, watched)
            }
        }
    }

    /**
     * Ends a booking that won't record: a row still SCHEDULED gets [state]; a recovered one left RECORDING is
     * stopped (STOPPED with [reason], keeping its file).
     */
    private suspend fun giveUp(id: Long, state: RecordingState, reason: RecordingReason) {
        val r = dao.recording(id) ?: return
        when (r.state) {
            RecordingState.SCHEDULED -> dao.updateRecording(r.copy(state = state, reason = reason, endedAt = clock()))
            RecordingState.RECORDING -> runner.stop(id, reason)
            else -> Unit
        }
        rearmQuietly()
    }

    private suspend fun failIfScheduled(id: Long) {
        try {
            val r = dao.recording(id) ?: return
            if (r.state == RecordingState.SCHEDULED) {
                dao.updateRecording(r.copy(state = RecordingState.FAILED, reason = RecordingReason.ERROR, endedAt = clock()))
            }
        } catch (e: Exception) {
            // The next recovery closes it.
        }
        rearmQuietly()
    }

    // STOP

    /** The deadline passed: the run is stopped unless it ends on its own within [STOP_GRACE_MS]. */
    private suspend fun stopAtDeadline(id: Long) {
        try {
            val r = dao.recording(id) ?: return
            if (r.state != RecordingState.RECORDING) return
            // Extended since the alarm was set: the next re-arm sets it again.
            if (clock() < r.window().until) return
            var waited = 0L
            while (runner.active.value.containsKey(id) && waited < STOP_GRACE_MS) {
                delayMs(TICK_MS)
                waited += TICK_MS
            }
            val now = dao.recording(id) ?: return
            if (now.state == RecordingState.RECORDING && clock() >= now.window().until) runner.stop(id, RecordingReason.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { closeIfStillRecording(id) }
        } finally {
            stopping.remove(id)
            withContext(NonCancellable) { rearmQuietly() }
        }
    }

    private suspend fun closeIfStillRecording(id: Long) {
        try {
            val r = dao.recording(id) ?: return
            if (r.state == RecordingState.RECORDING && clock() >= r.window().until) {
                dao.updateRecording(r.copy(state = RecordingState.STOPPED, reason = RecordingReason.NONE, endedAt = clock()))
            }
        } catch (e: Exception) {
            // The next recovery closes it.
        }
    }

    private suspend fun rearmQuietly() {
        try {
            rearm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next change, start or boot sets them again.
        }
    }
}
