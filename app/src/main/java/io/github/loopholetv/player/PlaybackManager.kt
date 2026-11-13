package io.github.loopholetv.player

import android.content.Context
import androidx.core.net.toUri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.DefaultTrackNameProvider
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.cast.net.LocalPeersOnly
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.normaliseAudioDelay
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.engine.EngineParts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.SocketException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Buffering(val channelId: Long) : PlaybackState
    data class Playing(val channelId: Long, val tags: StreamTags) : PlaybackState
    data class Error(val channelId: Long, val message: String) : PlaybackState
}

/** The app's single player. Screens and ViewModels depend on this so they can be tested with fakes. */
interface Playback {
    /** Attach to any number of surfaces (guide preview, full screen); only one is shown at a time. */
    val player: Player
    val state: StateFlow<PlaybackState>

    /** Plays [channel]; a no-op when that channel is already buffering or playing. Main thread only. */
    fun play(channel: ChannelEntity)

    /** Stops playback and drops the current item, cancelling its load. Main thread only. */
    fun stop()

    /**
     * Reconnects [channel] (stop, then play) so stream-start choices such as decoders apply again;
     * the track choices made on it are kept when it is the channel playing. Main thread only.
     */
    fun restart(channel: ChannelEntity) {
        stop()
        play(channel)
    }

    /** The current stream's selectable tracks; [PlayerTracks.NONE] while nothing plays. */
    val tracks: StateFlow<PlayerTracks> get() = NO_TRACKS

    /** Applies a track choice for the current stream only; the next channel follows Settings. Main thread only. */
    fun selectTrack(type: TrackType, choice: TrackChoice) {}

    /** Whether the full-screen surface is attached; tunneled playback is only requested while it is. Main thread only. */
    fun setFullScreenSurface(attached: Boolean) {}

    /** Applies an audio delay (ms) to the playing stream straight away, without reconnecting. Main thread only. */
    fun setAudioDelay(ms: Int) {}

    /**
     * Stops our stream, frees its connections and resolves a fresh link for [channel] to give to another
     * app. Nothing is played here. Null when the link can't be resolved. Main thread only.
     */
    suspend fun handOff(channel: ChannelEntity): Handoff? = null

    /** Live, or the archive programme being replayed. */
    val mode: StateFlow<PlayMode> get() = LIVE_MODE

    /** Replays [request] on [channel]; always releases the current stream first, then opens. Main thread only. */
    fun playArchive(channel: ChannelEntity, request: ArchiveRequest) {}

    fun pause() {}

    fun resume() {}

    /**
     * True while the player is held paused (it won't play when ready): by [pause], audio focus loss
     * or anything else that clears play-when-ready. Any play, reopen or [resume] that plays clears it.
     */
    val paused: StateFlow<Boolean> get() = NOT_PAUSED

    /** The playing archive stream's position in ms from its start. */
    fun positionMs(): Long = 0

    /** Seeks inside the loaded archive stream without reopening it. Main thread only. */
    fun seekWithin(positionMs: Long) {}

    /**
     * Follows another TV's cast: releases the current stream first, then plays [url] (that TV's relay
     * address) as HLS when [hls], otherwise as MPEG-TS. [state] carries [CAST_CHANNEL_ID] meanwhile and
     * [mode] is [PlayMode.Cast]; [play], [playArchive] and [stop] leave it. Main thread only.
     */
    fun playCast(url: String, hls: Boolean) {}

    /**
     * Opens what is playing again through the resolver, after releasing it: the live channel, or the
     * archive from the current position. Nothing happens when idle or following a cast. Main thread only.
     */
    fun reopen() {}

    /**
     * Plays a film or episode from [startMs]: releases the current stream first, then opens [item]'s
     * stream. [state] carries [VOD_CHANNEL_ID] meanwhile and [mode] is [PlayMode.Vod]. Main thread only.
     */
    fun playVod(item: VodPlayable, startMs: Long) {}

    /** What the player is decoding right now (the stream info card); null when not known. Main thread only. */
    fun streamSnapshot(): StreamSnapshot? = null
}

/** The channel id [PlaybackState] carries while following another TV's cast. */
const val CAST_CHANNEL_ID = -2L

/** The channel id [PlaybackState] carries while a film or episode plays. */
const val VOD_CHANNEL_ID = -3L

/**
 * Busy-style HTTP statuses a provider may answer while it still counts a connection that was just
 * released; an open refused with one of them right after a switch is worth trying again.
 */
val SWITCH_RETRY_HTTP_CODES: Set<Int> = setOf(403, 429, 503, 509)

private val LIVE_MODE: StateFlow<PlayMode> = MutableStateFlow<PlayMode>(PlayMode.Live).asStateFlow()

private val NO_TRACKS: StateFlow<PlayerTracks> = MutableStateFlow(PlayerTracks.NONE).asStateFlow()

private val NOT_PAUSED: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * Owns the one [ExoPlayer] for the app lifetime, on the main looper. Providers usually allow a
 * single connection per account, so switching channel stops and clears the previous item, which
 * cancels the previous load before the next one starts. Stream URLs are never logged, and Media3's
 * own logging (which prints exception chains that can carry URLs) is silenced in release builds.
 *
 * The playable URL comes from [resolver] at play time (user agent, UDP proxy, portal links), after
 * the old stream is released. Resolution is asynchronous on [scope] (main thread); a newer
 * [play] or [stop] cancels it. The [ResolvedStream] is kept for this play only: the HLS fallback
 * and live-window recovery reuse it, and only an HTTP 403/404/410 on a portal resolves again, once.
 *
 * Each stream start applies the playback [settings] and the channel's overrides ([channelPrefs]) to the
 * [engine] before loading: decoders, passthrough, buffer preset and audio delay, plus fresh track
 * preferences with the previous channel's track choices cleared. While a stream plays, audio delay and
 * track preferences follow [settings] live; a change to an engine choice restarts the stream once.
 * [exoPlayer] must be built around [engine] (`buildEnginePlayer`).
 *
 * [playArchive] replays a past programme on the same player. Every switch between live and archive
 * (or between two archive requests) releases the old stream and evicts the idle connections before
 * the next URL is resolved; archive streams get no keep-alive pings. A provider may still count a
 * just-released connection for a moment, so the first open after a release (within
 * [SWITCH_WINDOW_MS]) that is refused with a busy-style HTTP status or a dropped connection is
 * retried after each of [SWITCH_RETRY_DELAYS_MS], staying Buffering, before it becomes an error.
 *
 * A live open that fails in a way a fresh connection can fix (an empty or cut-off body, a dropped
 * connection, a server error), or ends, before it has played for [START_GRACE_MS], is released and
 * resolved again after each of [START_RETRY_DELAYS_MS], staying Buffering, before it becomes an error.
 * So is one that has delivered no data at all after [STALL_TIMEOUT_MS], once per open. A film or
 * episode ([playVod]) gets the same start retries for failures, but one that ends has simply finished.
 *
 * [playCast] follows another TV's cast through its own [castHttp] client, with this TV's engine and
 * track settings but no resolver, keep-alive, re-mint, switch retry or HLS fallback. A cast stream that
 * ends stays Buffering (the casting TV switched or paused its stream), and falling behind the live
 * window reloads the same address.
 */
@OptIn(UnstableApi::class)
class PlaybackManager internal constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val exoPlayer: ExoPlayer,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val resolver: StreamResolver = DirectResolver("Loophole/${versionName(context)}"),
    private val scope: CoroutineScope = MainScope(),
    private val engine: EngineParts = EngineParts(),
    settings: Flow<PlaybackSettings> = flowOf(PlaybackSettings()),
    private val awaitSettings: suspend () -> PlaybackSettings = { PlaybackSettings() },
    private val channelPrefs: suspend (ChannelEntity) -> ChannelPlaybackPrefs = { ChannelPlaybackPrefs.NONE },
    /** The channel's saved overrides as they change (an edit from any screen); empty for none. */
    private val observeChannelPrefs: (ChannelEntity) -> Flow<ChannelPlaybackPrefs> = { emptyFlow() },
    private val evictConnections: () -> Unit = { http.connectionPool.evictAll() },
    /** Closes idle pooled connections; by default [evictConnections] on an IO thread, since it may touch the network. */
    private val evictIdleConnections: suspend () -> Unit = { withContext(Dispatchers.IO) { evictConnections() } },
    /** Wall-clock time, passed to the resolver to build archive requests; [clock] times retries. */
    private val wallClock: () -> Instant = Instant::now,
    /** Waits between retries of an open refused right after a switch, or failed as it started. */
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /** The client for another TV's relay; separate from [http], whose pool holds provider connections. Built on first use. */
    castHttp: () -> OkHttpClient = ::defaultCastClient,
) : Playback {

    private val castClient by lazy(LazyThreadSafetyMode.NONE, castHttp)
    private val castUserAgent by lazy(LazyThreadSafetyMode.NONE) { "Loophole/${versionName(context)}" }

    override val player: Player get() = exoPlayer

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var current: ChannelEntity? = null

    /** The film or episode playing in [PlayMode.Vod] (then [current] is null), and where its open starts. */
    private var currentVod: VodPlayable? = null
    private var vodStartMs = 0L

    /** What [current] plays; null while it is being resolved. Never re-derived from `streamUrl`. */
    private var resolved: ResolvedStream? = null

    /** Bumped by every [play]/[stop]; a resolve that finishes for an older value is dropped. */
    private var playGeneration = 0L
    private var resolveJob: Job? = null
    private var keepAliveJob: Job? = null

    /** Whether [current] is being played as HLS (by URL shape or after the one fallback retry). */
    private var currentIsHls = false
    private var hlsFallbackTried = false

    /** Whether an HTTP 403/404/410 may re-resolve [current] ([StreamResolver.canRemint]), and if it did. */
    private var remintable = false
    private var remintTried = false

    /** Times ([clock] ms) of the automatic live-window recoveries within the last [LIVE_WINDOW_SPAN_MS]. */
    private val liveWindowRecoveries = ArrayDeque<Long>()

    private val _tracks = MutableStateFlow(PlayerTracks.NONE)
    override val tracks: StateFlow<PlayerTracks> = _tracks.asStateFlow()
    private val trackNames = DefaultTrackNameProvider(context.resources)

    /** What the last stream start applied, and for which [playGeneration]; live changes compare with it. */
    private var appliedEngine: EngineConfig? = null
    private var appliedPrefs = ChannelPlaybackPrefs.NONE
    private var appliedGeneration = -1L
    private var appliedTrackPrefs: TrackPrefs? = null

    /** The last [settings] emission, or null before the first one. */
    private var latestSettings: PlaybackSettings? = null

    /** False once [settings] has ended (a read error): [latestSettings] is frozen then, so starts read afresh. */
    private var settingsLive = true
    private var tunnelingSetting = false
    private var fullScreenAttached = false

    /** Track parameters to carry into the next start, set only by a same-channel restart. */
    private var restartChoices: TrackSelectionParameters? = null

    /** Follows the saved overrides of [current] while it is shown; see [onChannelPrefs]. */
    private var prefsJob: Job? = null

    /** Set when software audio failed mid-stream and this play continues on the hardware decoder. */
    private var audioFallbackActive = false

    private val _mode = MutableStateFlow<PlayMode>(PlayMode.Live)
    override val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _paused = MutableStateFlow(!exoPlayer.playWhenReady)
    override val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** When ([clock] ms) a buffering or playing stream was last released; null before the first. */
    private var lastReleaseAtMs: Long? = null

    /** Whether the current open came within [SWITCH_WINDOW_MS] of a release, and the retries spent on it. */
    private var openAfterSwitch = false
    private var switchRetries = 0

    /** When ([clock] ms) this open first became ready, the start retries spent on it, and whether a stall used one. */
    private var firstReadyAtMs: Long? = null
    private var startRetries = 0
    private var stallRetried = false
    private var stallJob: Job? = null

    /** Set when an archive was released; cleared only once an eviction has completed, so the next open evicts. */
    private var pendingEvict = false

    /** The cast being followed in [PlayMode.Cast], and whether it is loaded yet (the cast's [resolved]). */
    private var castUrl: String? = null
    private var castHls = false
    private var castLoaded = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _paused.value = !playWhenReady
        }
        override fun onTracksChanged(tracks: Tracks) {
            refreshState()
            refreshTracks()
        }
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = refreshTracks()
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            refreshState()
            refreshTracks()
        }
        override fun onPlayerError(error: PlaybackException) = refreshState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val vod = _mode.value as? PlayMode.Vod
            if (vod != null) {
                if (currentVod != null && resolved != null) refreshVodMode(vod)
                return
            }
            val archive = _mode.value as? PlayMode.Archive ?: return
            if (current?.id == archive.channelId && resolved != null) refreshArchiveMode(archive)
        }
    }

    /** The decoders in use, for [streamSnapshot]. */
    private val decoderNames = DecoderNames()

    init {
        Log.setLogLevel(if (BuildConfig.DEBUG) Log.LOG_LEVEL_ERROR else Log.LOG_LEVEL_OFF)
        exoPlayer.addListener(listener)
        exoPlayer.addAnalyticsListener(decoderNames)
        scope.launch {
            settings.distinctUntilChanged()
                .onCompletion { settingsLive = false }
                // A read error ends the live updates; stream starts then use their own read.
                .catch { }
                .collect(::onSettings)
        }
    }

    /**
     * Plays [channel] live. Coming from an archive this is always a reconnect, and the open is
     * asynchronous (connections are evicted first): callers observe [state] and [mode].
     */
    override fun play(channel: ChannelEntity) {
        val state = _state.value
        val fromArchive = _mode.value is PlayMode.Archive
        val active = (state is PlaybackState.Buffering && state.channelId == channel.id) ||
            (state is PlaybackState.Playing && state.channelId == channel.id)
        if (active && !fromArchive) {
            // Same channel: resume if something paused it (focus loss, noisy, a UI), never reconnect.
            exoPlayer.playWhenReady = true
            return
        }

        if (fromArchive || _mode.value is PlayMode.Vod) pendingEvict = true
        val generation = beginOpen(channel)
        _mode.value = PlayMode.Live
        // Leaving an archive also closes its idle connections before the live link is resolved, even
        // when the open that would have evicted was replaced by another zap before it ran.
        startResolve(channel, generation, evictFirst = pendingEvict)
        watchChannelPrefs(channel)
    }

    /**
     * Replays [request] on [channel]: always a reconnect, even for the same channel and request.
     * The old stream is released and its idle connections evicted before the archive is resolved.
     * The open is asynchronous: callers observe [state] and [mode] rather than the player's item.
     */
    override fun playArchive(channel: ChannelEntity, request: ArchiveRequest) {
        val generation = beginOpen(channel)
        _mode.value = PlayMode.Archive(channel.id, request)
        startArchiveResolve(channel, request, generation)
        watchChannelPrefs(channel)
    }

    /**
     * Follows another TV's cast at [url]: always a reconnect. The old stream is released and idle
     * connections evicted before the cast opens; the engine and track settings are this TV's own.
     */
    override fun playCast(url: String, hls: Boolean) {
        if (_mode.value is PlayMode.Archive || _mode.value is PlayMode.Vod) pendingEvict = true
        prefsJob?.cancel()
        prefsJob = null
        val generation = beginOpen(null)
        castUrl = url
        castHls = hls
        _mode.value = PlayMode.Cast
        startCast(url, hls, generation)
    }

    /**
     * Plays [item] from [startMs]: always a reconnect. The old stream is released and idle connections
     * evicted before the film's stream is resolved; no keep-alive runs and nothing is relayed.
     */
    override fun playVod(item: VodPlayable, startMs: Long) {
        prefsJob?.cancel()
        prefsJob = null
        val generation = beginOpen(null, stateId = VOD_CHANNEL_ID)
        currentVod = item
        vodStartMs = startMs.coerceAtLeast(0)
        _mode.value = PlayMode.Vod(item.ref, durationMs = null, ended = false)
        startVodResolve(item, generation)
    }

    /**
     * Opens the live channel or archive being played again through the resolver, releasing it first.
     * It stays paused if it was, and keeps the track choices made on it. Unlike [restart] it never
     * passes through Idle.
     */
    override fun reopen() {
        if (_mode.value is PlayMode.Cast) return
        val channel = current ?: return
        val playWhenReady = exoPlayer.playWhenReady
        // Until this play has loaded, the player's parameters still hold the previous channel's choices.
        restartChoices = exoPlayer.trackSelectionParameters.takeIf { resolved != null }
        val archive = (_mode.value as? PlayMode.Archive)?.takeIf { it.channelId == channel.id }
        if (archive != null) {
            playArchive(channel, resumeRequest(channel, archive))
        } else {
            val generation = beginOpen(channel)
            _mode.value = PlayMode.Live
            // The released stream's idle connections close before the next one opens.
            startResolve(channel, generation, evictFirst = true)
            watchChannelPrefs(channel)
        }
        exoPlayer.playWhenReady = playWhenReady
    }

    /**
     * Releases the old stream first (one connection per account) and resets the per-play state for
     * [channel] (null: a cast, or a film with [stateId] [VOD_CHANNEL_ID]); returns the new play's
     * generation. Nothing is resolved here.
     */
    private fun beginOpen(channel: ChannelEntity?, stateId: Long = channel?.id ?: CAST_CHANNEL_ID): Long {
        noteRelease()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        cancelJobs()
        val generation = ++playGeneration
        current = channel
        currentVod = null
        resolved = null
        castUrl = null
        castLoaded = false
        hlsFallbackTried = false
        remintable = false
        remintTried = false
        audioFallbackActive = false
        liveWindowRecoveries.clear()
        _tracks.value = PlayerTracks.NONE
        _state.value = PlaybackState.Buffering(stateId)
        exoPlayer.playWhenReady = true
        openAfterSwitch = lastReleaseAtMs?.let { clock() - it < SWITCH_WINDOW_MS } == true
        switchRetries = 0
        firstReadyAtMs = null
        startRetries = 0
        stallRetried = false
        return generation
    }

    /** Records that a stream holding (or opening) a provider connection is being released now. */
    private fun noteRelease() {
        val state = _state.value
        if (state is PlaybackState.Buffering || state is PlaybackState.Playing) lastReleaseAtMs = clock()
    }

    /**
     * Decoders are chosen when a stream starts, so a decoder override saved for the channel being
     * shown (from the guide, the channel editor or the player) restarts it once.
     */
    private fun watchChannelPrefs(channel: ChannelEntity) {
        prefsJob?.cancel()
        prefsJob = scope.launch {
            observeChannelPrefs(channel).catch { }.collect { onChannelPrefs(channel, it) }
        }
    }

    private fun onChannelPrefs(channel: ChannelEntity, p: ChannelPlaybackPrefs) {
        // A start still resolving reads the saved overrides itself when it loads.
        if (current?.id != channel.id || appliedGeneration != playGeneration) return
        val shown = when (val s = _state.value) {
            is PlaybackState.Buffering -> s.channelId == channel.id
            is PlaybackState.Playing -> s.channelId == channel.id
            is PlaybackState.Error -> s.channelId == channel.id
            PlaybackState.Idle -> false
        }
        if (shown && (p.video != appliedPrefs.video || p.audio != appliedPrefs.audio)) restart(channel)
    }

    /**
     * Resolves and loads [channel]. Undispatched, so it may load within the calling [play] when the
     * resolver and the settings reads don't suspend; otherwise (a Room lookup, a portal) it continues on
     * [scope]. [forceHls] keeps a re-minted link on HLS when the previous one needed the fallback.
     * [newChannel] is false for a re-mint or retry of the same play, which keeps the current track choices.
     * [evictFirst] closes idle connections (off the main thread) before resolving.
     */
    private fun startResolve(
        channel: ChannelEntity,
        generation: Long,
        forceHls: Boolean = false,
        newChannel: Boolean = true,
        evictFirst: Boolean = false,
    ) {
        val keepChoices = restartChoices
        restartChoices = null
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (evictFirst && !evictIdle(generation)) return@launch
            val (stream, canRemint) = try {
                resolver.resolve(channel) to resolver.canRemint(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == playGeneration) _state.value = PlaybackState.Error(channel.id, resolveErrorMessage(channel, e))
                return@launch
            }
            // Settings or a channel row that can't be read never block playback: defaults apply.
            val read = readOr(PlaybackSettings()) { awaitSettings() }
            val p = readOr(ChannelPlaybackPrefs.NONE) { channelPrefs(channel) }
            if (generation != playGeneration) return@launch
            // The live settings flow is what restarts compare against, so its latest value wins over
            // the play-time read, which can lag behind it. A value that arrives after this point is
            // compared with what is applied here and restarts the stream if it differs. Once the flow
            // has ended its last value is frozen, so the play-time read wins.
            val s = if (settingsLive) latestSettings ?: read else read
            applyEngine(s, p, generation)
            if (newChannel) applyChannelTracks(s, keepChoices)
            remintable = canRemint
            resolved = stream
            load(channel, stream, asHls = forceHls || isHlsUrl(stream.url.toUri()))
            startKeepAlive(channel)
        }
    }

    /**
     * Resolves and loads [request] on [channel] with the same engine and per-channel decoder choices
     * as live. Idle connections are evicted before the resolve and again before the open, since the
     * player frees the released stream's socket on its loader thread. No keep-alive runs for archives.
     */
    private fun startArchiveResolve(channel: ChannelEntity, request: ArchiveRequest, generation: Long, newChannel: Boolean = true) {
        val keepChoices = restartChoices
        restartChoices = null
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!evictIdle(generation)) return@launch
            val stream = try {
                resolver.resolveArchive(channel, request, wallClock())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The exception may carry the archive URL; only fixed text is shown.
                null
            }
            if (generation != playGeneration) return@launch
            if (stream == null) {
                _state.value = PlaybackState.Error(channel.id, ARCHIVE_ERROR)
                return@launch
            }
            if (!evictIdle(generation)) return@launch
            val read = readOr(PlaybackSettings()) { awaitSettings() }
            val p = readOr(ChannelPlaybackPrefs.NONE) { channelPrefs(channel) }
            if (generation != playGeneration) return@launch
            val s = if (settingsLive) latestSettings ?: read else read
            applyEngine(s, p, generation)
            if (newChannel) applyChannelTracks(s, keepChoices)
            remintable = false
            resolved = stream
            val hls = isHlsUrl(stream.url.toUri())
            _mode.value = PlayMode.Archive(channel.id, request, hls = hls)
            load(channel, stream, asHls = hls)
        }
    }

    /**
     * Resolves and loads [item] from [vodStartMs] once idle connections are evicted, with the playback
     * settings read as [startResolve] reads them and no per-channel overrides. [forceHls] keeps a
     * reopen on HLS when the previous open needed the fallback; [newPlay] is false for a retry of the
     * same film, which keeps the current track choices.
     */
    private fun startVodResolve(item: VodPlayable, generation: Long, forceHls: Boolean = false, newPlay: Boolean = true) {
        val keepChoices = restartChoices
        restartChoices = null
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!evictIdle(generation)) return@launch
            val stream = try {
                resolver.resolveVod(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == playGeneration) _state.value = PlaybackState.Error(VOD_CHANNEL_ID, vodError(item))
                return@launch
            }
            val read = readOr(PlaybackSettings()) { awaitSettings() }
            if (generation != playGeneration) return@launch
            val s = if (settingsLive) latestSettings ?: read else read
            applyEngine(s, ChannelPlaybackPrefs.NONE, generation)
            if (newPlay) applyChannelTracks(s, keepChoices)
            remintable = false
            resolved = stream
            loadVod(stream, vodStartMs, asHls = forceHls || isHlsUrl(stream.url.toUri()))
        }
    }

    /**
     * Loads the cast at [url] once idle connections are evicted, with the playback settings read as
     * [startResolve] reads them and no per-channel overrides. Track choices are kept only for a
     * restart of the same cast.
     */
    private fun startCast(url: String, hls: Boolean, generation: Long) {
        val keepChoices = restartChoices
        restartChoices = null
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!evictIdle(generation)) return@launch
            val read = readOr(PlaybackSettings()) { awaitSettings() }
            if (generation != playGeneration) return@launch
            val s = if (settingsLive) latestSettings ?: read else read
            applyEngine(s, ChannelPlaybackPrefs.NONE, generation)
            applyChannelTracks(s, keepChoices)
            loadCast(url, hls)
        }
    }

    /** Closes idle pooled connections ([evictIdleConnections]); false when a newer play or stop came meanwhile. */
    private suspend fun evictIdle(generation: Long): Boolean {
        evictIdleConnections()
        pendingEvict = false
        return generation == playGeneration
    }

    private suspend inline fun <T> readOr(default: T, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        default
    }

    /** Sets the engine for the stream about to load; read at prepare, codec and sink set-up. */
    private fun applyEngine(s: PlaybackSettings, p: ChannelPlaybackPrefs, generation: Long) {
        val config = engineConfig(s, p)
        engine.codecPolicy.video = config.video
        engine.colourPolicy.mode = config.colour
        engine.codecPolicy.audio = if (audioFallbackActive) DecoderChoice.HARDWARE else config.audio
        engine.sinkPolicy.passthrough = config.passthrough
        engine.sinkPolicy.decodeDtsToPcm = config.decodeDtsToPcm
        engine.loadControl.setPreset(config.buffer)
        engine.audioDelay.delayMs = normaliseAudioDelay(s.audioDelayMs)
        tunnelingSetting = s.tunneling
        appliedEngine = config
        appliedPrefs = p
        appliedGeneration = generation
    }

    /**
     * Fresh track preferences for a new channel; the previous channel's choices are dropped, except
     * on a restart of the same channel, which keeps the choices in [keep].
     */
    private fun applyChannelTracks(s: PlaybackSettings, keep: TrackSelectionParameters?) {
        appliedTrackPrefs = TrackPrefs(s)
        val fresh = exoPlayer.trackSelectionParameters.forNewChannel(s.audioLanguage, s.subtitleLanguage, s.preferSurround)
        val kept = if (keep == null) fresh else fresh.keepingChoicesOf(keep, keepTextState = true)
        setParameters(kept.withTunneling(s.tunneling && fullScreenAttached))
    }

    private fun setParameters(params: TrackSelectionParameters) {
        if (params != exoPlayer.trackSelectionParameters) exoPlayer.trackSelectionParameters = params
    }

    private fun onSettings(s: PlaybackSettings) {
        val previous = latestSettings
        latestSettings = s
        if (previous == null || previous.audioDelayMs != s.audioDelayMs) {
            engine.audioDelay.delayMs = normaliseAudioDelay(s.audioDelayMs)
        }
        val trackPrefs = TrackPrefs(s)
        val appliedTracks = appliedTrackPrefs
        if (appliedTracks != null && trackPrefs != appliedTracks) {
            applyLiveTrackPrefs(s, keepTextState = appliedTracks.subtitleLanguage == s.subtitleLanguage)
            appliedTrackPrefs = trackPrefs
        }
        tunnelingSetting = s.tunneling
        applyTunneling()

        // Only a stream started with other engine choices restarts; a start still reading settings will
        // pick this emission up itself.
        val state = _state.value
        val active = state is PlaybackState.Buffering || state is PlaybackState.Playing
        if (!active || appliedGeneration != playGeneration || engineConfig(s, appliedPrefs) == appliedEngine) return
        if (_mode.value is PlayMode.Cast) {
            // The same cast reopens with the new engine; it never goes through the resolver.
            val url = castUrl ?: return
            restartChoices = exoPlayer.trackSelectionParameters
            playCast(url, castHls)
            return
        }
        restart(current ?: return)
    }

    /** New track preferences for the playing stream, keeping the choices made on it. */
    private fun applyLiveTrackPrefs(s: PlaybackSettings, keepTextState: Boolean) {
        val old = exoPlayer.trackSelectionParameters
        setParameters(old.forNewChannel(s.audioLanguage, s.subtitleLanguage, s.preferSurround).keepingChoicesOf(old, keepTextState))
    }

    /** These parameters plus the track overrides of [old], and its subtitle on/off state when [keepTextState]. */
    private fun TrackSelectionParameters.keepingChoicesOf(old: TrackSelectionParameters, keepTextState: Boolean) =
        buildUpon().apply {
            old.overrides.values.forEach { addOverride(it) }
            if (keepTextState) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT in old.disabledTrackTypes)
        }.build()

    private fun applyTunneling() {
        setParameters(exoPlayer.trackSelectionParameters.withTunneling(tunnelingSetting && fullScreenAttached))
    }

    /** One reconnect: the old stream is released before the new link is resolved. */
    override fun restart(channel: ChannelEntity) {
        val sameChannel = current?.id == channel.id
        val choices = exoPlayer.trackSelectionParameters
        val archive = (_mode.value as? PlayMode.Archive)?.takeIf { it.channelId == channel.id && sameChannel }
        if (archive != null) {
            // A replayed programme reopens where the viewer is, never going through Live or Idle;
            // playArchive releases the old stream first.
            val from = resumeRequest(channel, archive)
            restartChoices = choices
            playArchive(channel, from)
            return
        }
        stop()
        // Same channel: the track choices made on it survive the reconnect.
        if (sameChannel) restartChoices = choices
        play(channel)
    }

    /**
     * The archive request shifted to the current position, so a reopen continues where it was. A
     * minute-granular archive can only start on a whole minute, so the start is floored to it: the
     * reopened stream then really starts at the request's start and positions stay true.
     */
    private fun resumeRequest(channel: ChannelEntity, archive: PlayMode.Archive): ArchiveRequest {
        val position = if (resolved != null) exoPlayer.currentPosition.coerceAtLeast(0) else 0L
        if (position == 0L) return archive.request
        val shifted = archive.request.from.plusMillis(position)
        val from = if (channel.catchup()?.minuteGranular == true) shifted.truncatedTo(ChronoUnit.MINUTES) else shifted
        return archive.request.copy(from = from)
    }

    /**
     * Releases the archive stream and opens [request] again within the same play (a retry or a
     * decoder fallback keeps its per-play state). A newer play or stop drops it.
     */
    private fun reopenArchive(channel: ChannelEntity, request: ArchiveRequest) {
        val generation = ++playGeneration
        resolved = null
        cancelJobs()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState.Buffering(channel.id)
        startArchiveResolve(channel, request, generation, newChannel = false)
    }

    override fun pause() {
        exoPlayer.playWhenReady = false
        _paused.value = true
    }

    override fun resume() {
        exoPlayer.playWhenReady = true
        _paused.value = false
    }

    override fun positionMs(): Long = exoPlayer.currentPosition

    override fun seekWithin(positionMs: Long) {
        val seekable = when (val mode = _mode.value) {
            is PlayMode.Archive -> mode.seekable
            is PlayMode.Vod -> exoPlayer.isCurrentMediaItemSeekable
            else -> false
        }
        if (!seekable || resolved == null) return
        exoPlayer.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun selectTrack(type: TrackType, choice: TrackChoice) {
        setParameters(exoPlayer.trackSelectionParameters.withChoice(exoPlayer.currentTracks, type, choice))
    }

    override fun setFullScreenSurface(attached: Boolean) {
        fullScreenAttached = attached
        applyTunneling()
    }

    override fun setAudioDelay(ms: Int) {
        engine.audioDelay.delayMs = normaliseAudioDelay(ms)
    }

    override suspend fun handOff(channel: ChannelEntity): Handoff? {
        stop()
        val generation = playGeneration
        evictIdleConnections()
        val stream = try {
            resolver.resolve(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // Something else started playing meanwhile: the other app must not open a second stream.
        if (generation != playGeneration) return null
        // The player releases the stream's own connection on its loader thread after stop(), so it
        // may only have become idle by now; evict once more before the other app connects.
        evictIdleConnections()
        pendingEvict = false
        if (generation != playGeneration) return null
        return Handoff(stream, hls = isHlsUrl(stream.url.toUri()))
    }

    private fun refreshTracks() {
        // Nothing playing, or the new channel is still resolving: late events from the previous
        // stream must not publish its tracks or frame rate for the new one.
        val loaded = when (_mode.value) {
            is PlayMode.Cast -> castLoaded
            is PlayMode.Vod -> currentVod != null && resolved != null
            else -> current != null && resolved != null
        }
        if (!loaded) {
            _tracks.value = PlayerTracks.NONE
            return
        }
        val listed = playerTracks(exoPlayer.currentTracks, exoPlayer.trackSelectionParameters, trackNames)
        // The rendered format is more exact than the first selected variant of an adaptive group.
        val fps = exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f }
        _tracks.value = if (fps != null) listed.copy(frameRate = fps) else listed
    }

    private fun startKeepAlive(channel: ChannelEntity) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            try {
                resolver.keepAlive(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed keep-alive never stops the stream; the stream fails on its own if it must.
            }
        }
    }

    private fun cancelJobs() {
        resolveJob?.cancel()
        resolveJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        stallJob?.cancel()
        stallJob = null
    }

    private fun load(channel: ChannelEntity, stream: ResolvedStream, asHls: Boolean) {
        currentIsHls = asHls
        val source = AudioDelayMediaSource(
            mediaSourceFactory(stream).createMediaSource(mediaItem(channel, stream, asHls)), engine.audioDelay,
        )
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        _state.value = PlaybackState.Buffering(channel.id)
        watchForStall(channel)
    }

    /**
     * A live open that has neither tracks nor buffered media after [STALL_TIMEOUT_MS] got no data at
     * all (the provider holds the connection open without sending): reconnect it, once per open.
     */
    private fun watchForStall(channel: ChannelEntity) {
        stallJob?.cancel()
        stallJob = null
        if (_mode.value !is PlayMode.Live || stallRetried || firstReadyAtMs != null) return
        val generation = playGeneration
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            if (generation != playGeneration || resolved == null || firstReadyAtMs != null) return@launch
            val stalled = _state.value == PlaybackState.Buffering(channel.id) &&
                exoPlayer.currentTracks.isEmpty && exoPlayer.totalBufferedDuration <= 0L
            if (stalled && startRetries < START_RETRY_DELAYS_MS.size) {
                stallRetried = true
                retryStart(keepHls = currentIsHls)
            }
        }
    }

    /** Loads (or reloads) the film's [stream] at [startMs]; no stall watch, which is for live opens only. */
    private fun loadVod(stream: ResolvedStream, startMs: Long, asHls: Boolean) {
        currentIsHls = asHls
        val builder = MediaItem.Builder().setUri(stream.url.toUri()).setMediaId(VOD_CHANNEL_ID.toString())
        if (asHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val source = AudioDelayMediaSource(mediaSourceFactory(stream).createMediaSource(builder.build()), engine.audioDelay)
        exoPlayer.setMediaSource(source, startMs)
        exoPlayer.prepare()
        _state.value = PlaybackState.Buffering(VOD_CHANNEL_ID)
    }

    /** Loads (or reloads) the cast through [castClient]; the mime type says TS or HLS, never the URL shape. */
    private fun loadCast(url: String, hls: Boolean) {
        currentIsHls = hls
        castLoaded = true
        val dataSourceFactory = OkHttpDataSource.Factory(castClient).setUserAgent(castUserAgent)
        val item = MediaItem.Builder()
            .setUri(url.toUri())
            .setMediaId(CAST_CHANNEL_ID.toString())
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        val source = AudioDelayMediaSource(
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item), engine.audioDelay,
        )
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        _state.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
    }

    override fun streamSnapshot(): StreamSnapshot = StreamSnapshot(
        video = exoPlayer.videoFormat,
        audio = exoPlayer.audioFormat,
        videoDecoder = decoderNames.video,
        audioDecoder = decoderNames.audio,
        droppedFrames = exoPlayer.videoDecoderCounters?.run {
            ensureUpdated()
            droppedBufferCount
        },
        bufferedMs = exoPlayer.totalBufferedDuration.takeIf { exoPlayer.currentMediaItem != null },
    )

    override fun stop() {
        noteRelease()
        if (_mode.value is PlayMode.Archive || _mode.value is PlayMode.Vod) pendingEvict = true
        prefsJob?.cancel()
        prefsJob = null
        cancelJobs()
        playGeneration++
        current = null
        currentVod = null
        resolved = null
        castUrl = null
        castLoaded = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _tracks.value = PlayerTracks.NONE
        _mode.value = PlayMode.Live
        _state.value = PlaybackState.Idle
    }

    /** For tests only: the app keeps the player for its whole lifetime. */
    @VisibleForTesting
    internal fun release() {
        prefsJob?.cancel()
        cancelJobs()
        playGeneration++
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        current = null
        currentVod = null
        resolved = null
        castUrl = null
        castLoaded = false
        _mode.value = PlayMode.Live
        _state.value = PlaybackState.Idle
        scope.cancel()
    }

    private fun refreshState() {
        if (_mode.value is PlayMode.Cast) return refreshCastState()
        if (_mode.value is PlayMode.Vod) return refreshVodState()
        val channel = current ?: return
        // Still resolving (or re-minting): the player holds nothing of this play yet.
        val stream = resolved ?: return
        val archive = (_mode.value as? PlayMode.Archive)?.also(::refreshArchiveMode)
        val error = exoPlayer.playerError
        if (error != null && recover(channel, stream, error)) return
        val ready = exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY
        // The open succeeded: a later failure is no longer a refused switch.
        if (error == null && ready) {
            openAfterSwitch = false
            if (firstReadyAtMs == null) firstReadyAtMs = clock()
        }
        // A live stream that ends as it starts was most likely cut off by the provider: open it again.
        if (error == null && archive == null && exoPlayer.playbackState == Player.STATE_ENDED && shouldRetryStart(null)) {
            retryStart(keepHls = currentIsHls)
            return
        }
        _state.value = when {
            error != null -> PlaybackState.Error(
                channel.id, if (archive != null) ARCHIVE_ERROR else errorMessage(channel.displayName, error),
            )
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering(channel.id)
            ready -> PlaybackState.Playing(channel.id, streamTags(exoPlayer.videoFormat, exoPlayer.audioFormat))
            // An archive that played to its end is not a failure: the mode says it ended.
            exoPlayer.playbackState == Player.STATE_ENDED && archive != null -> PlaybackState.Buffering(channel.id)
            // A live stream that ends has dropped; surface it so play(sameChannel) can retry.
            exoPlayer.playbackState == Player.STATE_ENDED ->
                PlaybackState.Error(channel.id, "${channel.displayName}: can't play this stream")
            else -> _state.value
        }
    }

    /**
     * A film's state: the live rules without a live-window recovery, and one that plays to its end is
     * not a failure (the mode says it ended) and is never reopened.
     */
    private fun refreshVodState() {
        val item = currentVod ?: return
        // Still resolving or waiting to retry: the player holds nothing of this open yet.
        val stream = resolved ?: return
        (_mode.value as? PlayMode.Vod)?.let(::refreshVodMode)
        val error = exoPlayer.playerError
        if (error != null && recoverVod(stream, error)) return
        val ready = exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY
        if (error == null && ready) {
            openAfterSwitch = false
            if (firstReadyAtMs == null) firstReadyAtMs = clock()
        }
        _state.value = when {
            error != null -> PlaybackState.Error(VOD_CHANNEL_ID, errorMessage(item.title, error))
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering(VOD_CHANNEL_ID)
            ready -> PlaybackState.Playing(VOD_CHANNEL_ID, streamTags(exoPlayer.videoFormat, exoPlayer.audioFormat))
            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackState.Buffering(VOD_CHANNEL_ID)
            else -> _state.value
        }
    }

    /** Publishes the film's length once known, and whether it played to its end. */
    private fun refreshVodMode(vod: PlayMode.Vod) {
        val updated = vod.copy(
            durationMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET },
            ended = exoPlayer.playbackState == Player.STATE_ENDED,
        )
        if (updated != vod) _mode.value = updated
    }

    /**
     * The fixes a film gets: hardware audio from where it is, the HLS fallback, and the switch and
     * start retries, which open it again where it started. Returns true when playback was restarted.
     */
    private fun recoverVod(stream: ResolvedStream, error: PlaybackException): Boolean = when {
        !audioFallbackActive && engine.codecPolicy.audio == DecoderChoice.SOFTWARE && error.isAudioRendererError() -> {
            audioFallbackActive = true
            engine.codecPolicy.audio = DecoderChoice.HARDWARE
            loadVod(stream, exoPlayer.currentPosition.coerceAtLeast(0), asHls = currentIsHls)
            true
        }
        !currentIsHls && !hlsFallbackTried && error.hasCause<UnrecognizedInputFormatException>() -> {
            hlsFallbackTried = true
            loadVod(stream, vodStartMs, asHls = true)
            true
        }
        shouldRetrySwitch(error) -> {
            retrySwitch()
            true
        }
        shouldRetryStart(error) -> {
            retryStart(keepHls = currentIsHls && error.errorCode != PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
            true
        }
        else -> false
    }

    private fun vodError(item: VodPlayable) = cantPlayMessage(item.title)

    private fun refreshCastState() {
        // Still evicting or reading settings: the player holds nothing of this cast yet.
        val url = castUrl?.takeIf { castLoaded } ?: return
        val error = exoPlayer.playerError
        if (error != null && recoverCast(url, error)) return
        val ready = exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY
        _state.value = when {
            error != null -> PlaybackState.Error(CAST_CHANNEL_ID, CAST_ERROR)
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering(CAST_CHANNEL_ID)
            ready -> PlaybackState.Playing(CAST_CHANNEL_ID, streamTags(exoPlayer.videoFormat, exoPlayer.audioFormat))
            // The casting TV switched or paused its stream: wait for the next one, not a failure.
            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackState.Buffering(CAST_CHANNEL_ID)
            else -> _state.value
        }
    }

    /**
     * The fixes a cast gets: back to the live edge of the same address, and software audio that failed
     * reloads it once on the hardware decoder. Nothing re-resolves or retries; returns true when reloaded.
     */
    private fun recoverCast(url: String, error: PlaybackException): Boolean = when {
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && withinLiveWindowBudget() -> {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            _state.value = PlaybackState.Buffering(CAST_CHANNEL_ID)
            true
        }
        !audioFallbackActive && engine.codecPolicy.audio == DecoderChoice.SOFTWARE && error.isAudioRendererError() -> {
            audioFallbackActive = true
            engine.codecPolicy.audio = DecoderChoice.HARDWARE
            loadCast(url, castHls)
            true
        }
        else -> false
    }

    /** Publishes whether the loaded archive can seek, its length once known, and whether it ended. */
    private fun refreshArchiveMode(archive: PlayMode.Archive) {
        val updated = archive.copy(
            seekable = exoPlayer.isCurrentMediaItemSeekable,
            durationMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET },
            ended = exoPlayer.playbackState == Player.STATE_ENDED,
        )
        if (updated != archive) _mode.value = updated
    }

    /** Handles errors that have an automatic fix; returns true when playback was restarted. */
    private fun recover(channel: ChannelEntity, stream: ResolvedStream, error: PlaybackException): Boolean = when {
        _mode.value is PlayMode.Live &&
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && withinLiveWindowBudget() -> {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            _state.value = PlaybackState.Buffering(channel.id)
            true
        }
        // Software audio failed while decoding: there is no automatic fallback after the decoder
        // started, so reopen the same link once with the hardware decoder for the rest of this play.
        !audioFallbackActive && engine.codecPolicy.audio == DecoderChoice.SOFTWARE && error.isAudioRendererError() -> {
            audioFallbackActive = true
            engine.codecPolicy.audio = DecoderChoice.HARDWARE
            val archive = _mode.value as? PlayMode.Archive
            // An archive is released and reopened from the position it reached, so its old
            // connection is closed before the new one opens.
            if (archive == null) load(channel, stream, asHls = currentIsHls)
            else reopenArchive(channel, resumeRequest(channel, archive))
            true
        }
        // Extension-less HLS that progressive extractors can't recognise: try HLS once, same URL.
        !currentIsHls && !hlsFallbackTried && error.hasCause<UnrecognizedInputFormatException>() -> {
            hlsFallbackTried = true
            load(channel, stream, asHls = true)
            true
        }
        // A portal's temporary link expired or was refused: release it and mint once more.
        remintable && !remintTried && error.httpCode() in REMINT_HTTP_CODES -> {
            remintTried = true
            remint(channel)
            true
        }
        // The provider may still count the stream just released: wait and open again.
        shouldRetrySwitch(error) -> {
            retrySwitch()
            true
        }
        // A live open that failed as it started: a fresh connection usually works.
        shouldRetryStart(error) -> {
            // A fallback playlist that wasn't one says nothing about the stream: sniff it afresh.
            retryStart(keepHls = currentIsHls && error.errorCode != PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
            true
        }
        else -> false
    }

    /**
     * Whether a live or film open that failed with [error] (null: it ended) is worth opening again: it
     * has not played for [START_GRACE_MS] yet, start retries remain, the switch backoff has not already
     * given up on it, and the failure is one a new connection can fix.
     */
    private fun shouldRetryStart(error: PlaybackException?): Boolean {
        val mode = _mode.value
        if ((mode !is PlayMode.Live && mode !is PlayMode.Vod) || startRetries >= START_RETRY_DELAYS_MS.size) return false
        if (switchRetries >= SWITCH_RETRY_DELAYS_MS.size) return false
        val readyAt = firstReadyAtMs
        if (readyAt != null && clock() - readyAt >= START_GRACE_MS) return false
        return error == null || error.mayHealOnReconnect()
    }

    /**
     * Failures a fresh connection may fix: an empty, cut-off or garbled body, a dropped or timed-out
     * connection, or a server error shown with the generic message. Refusals with their own message
     * (401/403/404/429/458/509), unsupported formats and decoder failures are left as they are.
     */
    private fun PlaybackException.mayHealOnReconnect(): Boolean {
        val code = httpCode()
        if (code != null) return code in 500..599 && code != 509
        if (this is ExoPlaybackException && type == ExoPlaybackException.TYPE_RENDERER) return false
        return errorCode in HEALABLE_ERROR_CODES
    }

    /**
     * Releases the failed open, stays Buffering, and after the next start backoff resolves and loads
     * what it opened (the live channel, or the film from where it started) again, as Try again would.
     * A newer play or stop cancels the wait.
     */
    private fun retryStart(keepHls: Boolean) {
        val vod = currentVod
        val channel = current
        if (vod == null && channel == null) return
        val wait = START_RETRY_DELAYS_MS[startRetries++]
        val generation = ++playGeneration
        resolved = null
        cancelJobs()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        firstReadyAtMs = null
        hlsFallbackTried = keepHls
        _state.value = PlaybackState.Buffering(channel?.id ?: VOD_CHANNEL_ID)
        resolveJob = scope.launch {
            retryDelay(wait)
            if (generation != playGeneration) return@launch
            if (vod != null) {
                startVodResolve(vod, generation, forceHls = keepHls, newPlay = false)
            } else if (channel != null) {
                startResolve(channel, generation, forceHls = keepHls, newChannel = false, evictFirst = true)
            }
        }
    }

    /**
     * Whether a failed open right after a switch is worth another try: a busy-style HTTP status or a
     * refused/reset connection, while retries remain. A refusal that may mean an expired portal link
     * is left to the re-mint, which runs first.
     */
    private fun shouldRetrySwitch(error: PlaybackException): Boolean {
        if (!openAfterSwitch || switchRetries >= SWITCH_RETRY_DELAYS_MS.size) return false
        val code = error.httpCode() ?: return error.hasCause<SocketException>()
        return code in SWITCH_RETRY_HTTP_CODES && !(remintable && code in REMINT_HTTP_CODES)
    }

    /**
     * Releases the refused stream, stays Buffering, and after the next backoff delay resolves and loads
     * the same live channel, archive request or film again. A newer play or stop cancels the wait.
     */
    private fun retrySwitch() {
        val vod = currentVod
        val channel = current
        if (vod == null && channel == null) return
        val wait = SWITCH_RETRY_DELAYS_MS[switchRetries++]
        val archive = _mode.value as? PlayMode.Archive
        val wasHls = currentIsHls
        // A new generation first, so events from the released stream are ignored.
        val generation = ++playGeneration
        resolved = null
        cancelJobs()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        hlsFallbackTried = wasHls
        _state.value = PlaybackState.Buffering(channel?.id ?: VOD_CHANNEL_ID)
        resolveJob = scope.launch {
            retryDelay(wait)
            if (generation != playGeneration) return@launch
            when {
                vod != null -> startVodResolve(vod, generation, forceHls = wasHls, newPlay = false)
                channel == null -> return@launch
                archive != null -> startArchiveResolve(channel, archive.request, generation, newChannel = false)
                else -> startResolve(channel, generation, forceHls = wasHls, newChannel = false, evictFirst = true)
            }
        }
    }

    private fun remint(channel: ChannelEntity) {
        // A single-use link must not be spent on a progressive attempt when HLS is already known.
        val wasHls = currentIsHls
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        cancelJobs()
        resolved = null
        hlsFallbackTried = wasHls
        _state.value = PlaybackState.Buffering(channel.id)
        startResolve(channel, playGeneration, forceHls = wasHls, newChannel = false)
    }

    private fun resolveErrorMessage(channel: ChannelEntity, e: Exception): String {
        val name = channel.displayName
        return when {
            e is StalkerLinkException && e.code == "limit" ->
                "$name: the portal says too many connections are in use. Try again in a moment."
            e is StalkerAuthException -> "$name: the portal refused the login."
            else -> "$name: can't open this channel."
        }
    }

    /** Records a recovery attempt; false once [MAX_LIVE_WINDOW_RECOVERIES] happened within the span. */
    private fun withinLiveWindowBudget(): Boolean {
        val now = clock()
        while (liveWindowRecoveries.isNotEmpty() && now - liveWindowRecoveries.first() >= LIVE_WINDOW_SPAN_MS) {
            liveWindowRecoveries.removeFirst()
        }
        if (liveWindowRecoveries.size >= MAX_LIVE_WINDOW_RECOVERIES) return false
        liveWindowRecoveries.addLast(now)
        return true
    }

    private fun mediaSourceFactory(stream: ResolvedStream): DefaultMediaSourceFactory {
        val dataSourceFactory = OkHttpDataSource.Factory(http)
            .setUserAgent(stream.userAgent)
            .setDefaultRequestProperties(stream.headers)
        return DefaultMediaSourceFactory(dataSourceFactory)
    }

    private fun mediaItem(channel: ChannelEntity, stream: ResolvedStream, asHls: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(stream.url.toUri()).setMediaId(channel.id.toString())
        if (asHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private companion object {
        const val MAX_LIVE_WINDOW_RECOVERIES = 3
        const val LIVE_WINDOW_SPAN_MS = 30_000L
        val REMINT_HTTP_CODES = setOf(403, 404, 410)

        /** How long after a release an open still counts as the first open after a switch. */
        const val SWITCH_WINDOW_MS = 10_000L
        val SWITCH_RETRY_DELAYS_MS = listOf(500L, 1_000L, 2_000L, 4_000L)

        /** Waits before each reopen of a live open that failed as it started; one per entry. */
        val START_RETRY_DELAYS_MS = listOf(1_000L, 2_500L)

        /** How long after it first played a live stream's failure still counts as a failed start. */
        const val START_GRACE_MS = 5_000L

        /** How long a live open may go without any data before it is reconnected. */
        const val STALL_TIMEOUT_MS = 15_000L

        /** Player error codes (no HTTP status) that a fresh connection may fix. */
        val HEALABLE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )
        const val ARCHIVE_ERROR = "Couldn't play this programme back."
        const val CAST_ERROR = "Can't play this stream."
        const val CAST_READ_TIMEOUT_S = 8L

        /**
         * A plain client for the relay. It never follows a redirect and refuses any host but a TV on
         * the home network, so a peer (or a playlist it serves) can't send the player elsewhere.
         */
        fun defaultCastClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(LocalPeersOnly())
            .readTimeout(CAST_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun versionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        } catch (_: Exception) {
            "dev"
        }
    }
}
