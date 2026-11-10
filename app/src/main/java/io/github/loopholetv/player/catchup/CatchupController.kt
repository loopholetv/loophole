package io.github.loopholetv.player.catchup

import io.github.loopholetv.data.catchup.CatchupSpec
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How long an archive may stay paused before its connection is released. */
const val PAUSE_RELEASE_MS = 60_000L

private val REWIND_STEP: Duration = Duration.ofSeconds(10)

/** How much further than the live lag from live an ended stream may stop and still count as caught up. */
private val CAUGHT_UP_MARGIN: Duration = Duration.ofSeconds(60)

/**
 * Drives catch-up on the app's single [playback]: replaying a past programme, rewinding from live,
 * batched seek keys, pause and resume, moving on to the next programme and going back to live.
 *
 * Only one stream is ever open: every switch goes through [Playback.playArchive] or [Playback.play],
 * which release the current stream before opening the next. Held seek keys are summed by a
 * [SeekBatcher] and turn into one seek after the key is released. A live pause, or an archive pause
 * longer than [PAUSE_RELEASE_MS], stops the stream so the provider connection is free; resuming opens
 * the archive at the paused instant.
 *
 * [ui] is null while live (and not paused). All calls are main thread only, as is [scope].
 * Going back to live goes through [liveOpener], so the caller can honour the channel's own
 * player settings.
 */
class CatchupController(
    private val playback: Playback,
    private val programmes: ProgrammeLookup,
    private val clock: () -> Instant,
    elapsedMs: () -> Long,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val liveOpener: (ChannelEntity) -> Unit = { playback.play(it) },
) {
    private val _ui = MutableStateFlow<CatchupUi?>(null)
    val ui: StateFlow<CatchupUi?> = _ui.asStateFlow()

    private val batcher = SeekBatcher(elapsedMs)

    /** The channel whose archive is open, or whose live stream is paused with catch-up. */
    private var active: Pair<ChannelEntity, CatchupSpec>? = null
    private var seekBase: Instant? = null

    /** The paused archive's stream was stopped to free the connection. */
    private var released = false

    /** Live was paused on a catch-up channel at this instant; [ui] follows once its programme is known. */
    private var livePausedAt: Instant? = null

    /** A batched seek being carried out; a new batch starts from here rather than the old position. */
    private var committedTarget: Instant? = null

    /** Identifies the latest committed seek, so only it clears [committedTarget] when it finishes. */
    private var commitCount = 0

    private var opJob: Job? = null
    private var commitJob: Job? = null
    private var releaseJob: Job? = null
    private var tickJob: Job? = null

    init {
        scope.launch {
            playback.mode.collect { if (it is PlayMode.Archive && it.ended) onModeEnded(it) }
        }
        scope.launch { playback.state.collect(::onState) }
    }

    /** Replays [from]–[until] on [channel]. */
    fun watch(channel: ChannelEntity, spec: CatchupSpec, from: Instant, until: Instant) {
        reset()
        launchOp { openArchive(channel, spec, from, until) }
    }

    /** Replays the programme airing now from its start, never closer to live than the archive reaches. */
    fun watchFromStart(channel: ChannelEntity, spec: CatchupSpec) {
        reset()
        launchOp {
            val now = clock()
            val airing = spanAt(channel, now)
            val from = clampToArchive(minOf(airing.start, now.minus(spec.liveLag)), now, spec)
            // Under the lag the start is not archived yet: open the earlier programme's tail, up to its end.
            if (from.isBefore(airing.start)) openArchive(channel, spec, from, null)
            else openArchive(channel, spec, from, airing.stop, airing)
        }
    }

    /** Takes over an archive that was already opened on [channel] (for example from the guide). */
    fun adopt(channel: ChannelEntity, spec: CatchupSpec) {
        val loaded = playback.mode.value as? PlayMode.Archive ?: return
        if (loaded.channelId != channel.id) return
        reset()
        active = channel to spec
        launchOp {
            val position = positionOf(loaded, playback.positionMs())
            val state = playback.state.value
            val failed = state is PlaybackState.Error && state.channelId == channel.id
            _ui.value = archiveUi(channel, spanAt(channel, loaded.request.from), position).copy(failed = failed)
            if (loaded.ended && !failed) continueAfter(channel, spec, loaded)
        }
    }

    /** Leaves live for the archive, at least the live lag behind now. */
    fun rewindLive(channel: ChannelEntity, spec: CatchupSpec) {
        reset()
        launchOp {
            val now = clock()
            val from = rewindFromLive(now, REWIND_STEP, spec.liveLag, spec.earliest(now), spec.minuteGranular)
            openArchive(channel, spec, from, null)
        }
    }

    /** Adds one seek key press; the seek itself happens after [seekKeyUp]. Ignored while live. */
    fun seekKey(channel: ChannelEntity, spec: CatchupSpec, direction: Int, repeatCount: Int) {
        val current = _ui.value ?: return
        if (current.channelId != channel.id) return
        active = channel to spec
        commitJob?.cancel()
        val base = seekBase ?: (committedTarget ?: currentPosition(current)).also { seekBase = it }
        val offset = batcher.press(direction, repeatCount)
        _ui.value = current.copy(pendingTarget = clampTarget(base.plusMillis(offset), spec), now = clock())
    }

    /** The seek key was released: commits the summed seek once no key follows within [COMMIT_DELAY_MS]. */
    fun seekKeyUp() {
        if (batcher.pending == null) return
        batcher.release()
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(COMMIT_DELAY_MS)
            val offset = batcher.due() ?: return@launch
            val base = seekBase ?: return@launch
            seekBase = null
            val (channel, spec) = active ?: return@launch
            val target = clampTarget(base.plusMillis(offset), spec)
            // The batch is consumed: carry it out as its own op so a new key press cannot drop it.
            committedTarget = target
            val token = ++commitCount
            opJob?.cancel()
            opJob = scope.launch {
                try {
                    seekTo(channel, spec, target)
                } finally {
                    if (commitCount == token) committedTarget = null
                }
            }
        }
    }

    /** Seeks to the start of the programme before the current one. */
    fun previousProgramme() {
        val current = _ui.value ?: return
        val (channel, spec) = active ?: return
        launchOp {
            val previous = spanAt(channel, current.programme.start.minusMillis(1))
            seekTo(channel, spec, clampTarget(previous.start, spec), jump = true)
        }
    }

    /** Seeks to the start of the next programme, or goes live when that is too close to now. */
    fun nextProgramme() {
        val current = _ui.value ?: return
        val (channel, spec) = active ?: return
        launchOp { seekTo(channel, spec, clampTarget(current.programme.stop, spec), jump = true) }
    }

    /**
     * Play/Pause. An archive pauses in place (released after [PAUSE_RELEASE_MS]). Live pauses only on a
     * channel with catch-up ([spec] not null): the stream stops and Play resumes from the archive at the
     * paused instant. Without catch-up, live is not pausable and this does nothing.
     */
    fun togglePause(channel: ChannelEntity, spec: CatchupSpec?) {
        if (livePausedAt != null) return resumeLive()
        val current = _ui.value
        when {
            current == null -> if (spec != null) pauseLive(channel, spec)
            current.paused -> resumeArchive(current)
            else -> pauseArchive(current)
        }
    }

    /** Leaves the archive for the live stream. */
    fun goLive(channel: ChannelEntity) {
        // Already live (a second press while the first open is under way): nothing to leave. An
        // archive still being opened (its programme being looked up) is cancelled, though.
        if (_ui.value == null && livePausedAt == null && opJob?.isActive != true) return
        toLive(channel)
    }

    private fun toLive(channel: ChannelEntity) {
        reset()
        _ui.value = null
        liveOpener(channel)
    }

    /** Opens the archive again at the last known position, after it failed. */
    fun retry(channel: ChannelEntity, spec: CatchupSpec) {
        val current = _ui.value ?: return
        if (current.live) return
        launchOp {
            openArchive(channel, spec, align(current.position, spec), current.programme.stop, current.programme)
        }
    }

    /** Called every second while the overlay shows: follows the position and rolls to the next programme. */
    fun tick() {
        val current = _ui.value ?: return
        val now = clock()
        val loaded = loadedFor(current)
        if (loaded == null || current.paused || current.failed) {
            _ui.value = current.copy(now = now)
            return
        }
        val position = positionOf(loaded, playback.positionMs())
        _ui.value = current.copy(position = position, now = now)
        // An open-ended stream keeps playing past the programme it was opened for; no reconnect needed.
        if (!loaded.seekable && !position.isBefore(current.programme.stop) && tickJob?.isActive != true) {
            val channel = active?.first ?: return
            tickJob = scope.launch {
                val span = spanAt(channel, position)
                _ui.value = _ui.value?.takeIf { it.channelId == channel.id }?.copy(programme = span)
            }
        }
    }

    /** Channel id and position to come back to later, or null while live. */
    fun resumePoint(): Pair<Long, Instant>? {
        val current = _ui.value ?: return null
        return current.channelId to currentPosition(current)
    }

    /** Opens the archive at [at], a point saved with [resumePoint]; goes live when that is too close to now. */
    fun resume(channel: ChannelEntity, spec: CatchupSpec, at: Instant) {
        reset()
        launchOp {
            val now = clock()
            if (isNearLive(at, now, spec.liveLag)) toLive(channel)
            else openArchive(channel, spec, clampToArchive(at, now, spec), null)
        }
    }

    /**
     * Drops a seek still being keyed in or waiting to commit, without touching playback (the screen
     * is going away; the caller keeps the target as its resume point).
     */
    fun cancelSeek() {
        batcher.cancel()
        commitJob?.cancel()
        seekBase = null
        _ui.value = _ui.value?.copy(pendingTarget = null)
    }

    /** Forgets everything (on a channel change or leaving the player) without touching playback. */
    fun clear() {
        reset()
        _ui.value = null
    }

    private fun reset() {
        batcher.cancel()
        seekBase = null
        listOf(opJob, commitJob, releaseJob, tickJob).forEach { it?.cancel() }
        released = false
        active = null
        livePausedAt = null
        committedTarget = null
    }

    private fun launchOp(block: suspend () -> Unit) {
        opJob?.cancel()
        commitJob?.cancel()
        batcher.cancel()
        seekBase = null
        opJob = scope.launch { block() }
    }

    /** A [jump] (to a programme start) is carried out even when it is too small to count as a seek. */
    private suspend fun seekTo(channel: ChannelEntity, spec: CatchupSpec, target: Instant, jump: Boolean = false) {
        val current = _ui.value ?: return
        val now = clock()
        val loaded = loadedFor(current)
        if (loaded == null) {
            if (isNearLive(target, now, spec.liveLag)) toLive(channel)
            else openArchive(channel, spec, clampToArchive(target, now, spec), null)
            return
        }
        val planned = planSeek(
            currentPosition(current), target, loaded, now, spec.earliest(now), spec.liveLag, spec.minuteGranular,
        )
        val plan = if (jump && planned == SeekPlan.Ignore) forcedPlan(loaded, clampToArchive(target, now, spec)) else planned
        when (plan) {
            is SeekPlan.Within -> {
                playback.seekWithin(plan.positionMs)
                val position = positionOf(loaded, plan.positionMs)
                val span = current.programme.takeIf { position.isWithin(it) } ?: spanAt(channel, position)
                _ui.value = current.copy(programme = span, position = position, now = now, pendingTarget = stillPending())
            }
            is SeekPlan.Reopen -> openArchive(channel, spec, plan.from, null)
            SeekPlan.GoLive -> toLive(channel)
            SeekPlan.Ignore -> _ui.value = current.copy(pendingTarget = stillPending())
        }
    }

    private fun forcedPlan(loaded: PlayMode.Archive, target: Instant): SeekPlan {
        val ms = Duration.between(loaded.request.from, target).toMillis()
        val duration = loaded.durationMs
        return if (loaded.seekable && duration != null && ms in 0 until duration) SeekPlan.Within(ms)
        else SeekPlan.Reopen(target)
    }

    /** Opens [from]–[until] (the programme's end when null); the player releases the old stream first. */
    private suspend fun openArchive(
        channel: ChannelEntity,
        spec: CatchupSpec,
        from: Instant,
        until: Instant?,
        span: ProgrammeSpan? = null,
    ) {
        val programme = span ?: spanAt(channel, from)
        releaseJob?.cancel()
        released = false
        livePausedAt = null
        tickJob?.cancel()
        active = channel to spec
        playback.playArchive(channel, ArchiveRequest(from, until ?: programme.stop))
        _ui.value = archiveUi(channel, programme, from).copy(pendingTarget = stillPending())
    }

    private fun pauseLive(channel: ChannelEntity, spec: CatchupSpec) {
        reset()
        playback.stop()
        val at = clock()
        active = channel to spec
        livePausedAt = at
        launchOp {
            val span = spanAt(channel, at)
            if (livePausedAt == at) {
                _ui.value = CatchupUi(channel.id, span, at, clock(), null, paused = true, live = true, failed = false)
            }
        }
    }

    /** The archive only reaches the live lag behind now, so a quick resume starts there. */
    private fun resumeLive() {
        val at = livePausedAt ?: return
        val (channel, spec) = active ?: return
        launchOp {
            val now = clock()
            openArchive(channel, spec, clampToArchive(minOf(at, now.minus(spec.liveLag)), now, spec), null)
        }
    }

    private fun pauseArchive(current: CatchupUi) {
        val position = currentPosition(current)
        playback.pause()
        _ui.value = current.copy(paused = true, position = position, now = clock())
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(PAUSE_RELEASE_MS)
            released = true
            playback.stop()
        }
    }

    private fun resumeArchive(current: CatchupUi) {
        releaseJob?.cancel()
        if (!released) {
            playback.resume()
            _ui.value = current.copy(paused = false, now = clock())
            return
        }
        val (channel, spec) = active ?: return
        launchOp {
            openArchive(channel, spec, align(current.position, spec), current.programme.stop, current.programme)
        }
    }

    /** The archive played to its end: continue with the next programme, or go live when it is too recent. */
    private fun onModeEnded(ended: PlayMode.Archive) {
        val (channel, spec) = active ?: return
        val current = _ui.value ?: return
        if (current.live || ended.channelId != channel.id) return
        launchOp { continueAfter(channel, spec, ended) }
    }

    /**
     * Continues from where [ended] really stopped (its requested end when the position is unknown), so
     * nothing already watched is replayed and nothing is skipped, whatever programme the overlay showed.
     */
    private suspend fun continueAfter(channel: ChannelEntity, spec: CatchupSpec, ended: PlayMode.Archive) {
        val positionMs = playback.positionMs()
        val end = if (positionMs > 0) positionOf(ended, positionMs) else ended.request.until
        val next = spanAt(channel, end)
        val start = maxOf(next.start, end)
        val now = clock()
        // A stream that stopped close to live has caught up as far as the archive goes: reopening it
        // would only fetch the last minute or two again and again, so go live instead.
        if (isNearLive(start, now, spec.liveLag.plus(CAUGHT_UP_MARGIN))) toLive(channel)
        else openArchive(channel, spec, clampToArchive(start, now, spec), next.stop, next)
    }

    private fun onState(state: PlaybackState) {
        val current = _ui.value ?: return
        if (current.live || released) return
        // A paused archive stopped from outside (the app went to the background): Play reopens it.
        if (state is PlaybackState.Idle && current.paused) {
            releaseJob?.cancel()
            released = true
            return
        }
        when {
            // The player keeps its item on an error, so its position is where Try again should resume.
            state is PlaybackState.Error && state.channelId == current.channelId ->
                _ui.value = current.copy(failed = true, position = errorPosition(current))
            // A reopen (Try again) is under way: the error card goes as soon as it starts.
            (state is PlaybackState.Playing || state is PlaybackState.Buffering) &&
                current.failed && state.channelIdOrNull() == current.channelId ->
                _ui.value = current.copy(failed = false)
        }
    }

    private fun PlaybackState.channelIdOrNull(): Long? = when (this) {
        is PlaybackState.Playing -> channelId
        is PlaybackState.Buffering -> channelId
        is PlaybackState.Error -> channelId
        PlaybackState.Idle -> null
    }

    /** The open archive stream behind [current], or null when nothing is loaded to seek in. */
    private fun loadedFor(current: CatchupUi): PlayMode.Archive? {
        if (current.live || released) return null
        return (playback.mode.value as? PlayMode.Archive)?.takeIf { it.channelId == current.channelId }
    }

    private fun errorPosition(current: CatchupUi): Instant {
        val loaded = loadedFor(current) ?: return current.position
        val positionMs = playback.positionMs()
        return if (positionMs > 0) positionOf(loaded, positionMs) else current.position
    }

    private fun currentPosition(current: CatchupUi): Instant {
        if (current.failed) return current.position
        val loaded = loadedFor(current) ?: return current.position
        return positionOf(loaded, playback.positionMs())
    }

    /** The target of a newer seek batch still being keyed in, which stays on screen until it commits. */
    private fun stillPending(): Instant? = if (batcher.pending != null) _ui.value?.pendingTarget else null

    private fun archiveUi(channel: ChannelEntity, programme: ProgrammeSpan, position: Instant) =
        CatchupUi(channel.id, programme, position, clock(), null, paused = false, live = false, failed = false)

    private suspend fun spanAt(channel: ChannelEntity, t: Instant): ProgrammeSpan =
        programmes.at(channel, t) ?: fallbackSpan(t, zone)

    /** Keeps a seek target between the oldest archived instant and now. */
    private fun clampTarget(t: Instant, spec: CatchupSpec): Instant {
        val now = clock()
        val earliest = spec.earliest(now)
        return if (t.isBefore(earliest)) earliest else minOf(t, now)
    }

    /** A start the archive can serve: inside its window, on a whole minute when it is minute-granular. */
    private fun clampToArchive(t: Instant, now: Instant, spec: CatchupSpec): Instant {
        val earliest = spec.earliest(now)
        val aligned = align(maxOf(t, earliest), spec)
        return if (aligned.isBefore(earliest)) aligned.plus(1, ChronoUnit.MINUTES) else aligned
    }

    private fun align(t: Instant, spec: CatchupSpec): Instant =
        if (spec.minuteGranular) t.truncatedTo(ChronoUnit.MINUTES) else t

    private fun Instant.isWithin(span: ProgrammeSpan) = !isBefore(span.start) && isBefore(span.stop)
}
