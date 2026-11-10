package io.github.loopholetv.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.DefaultTrackNameProvider
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.normaliseAudioDelay
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.player.engine.EngineParts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
}

private val NO_TRACKS: StateFlow<PlayerTracks> = MutableStateFlow(PlayerTracks.NONE).asStateFlow()

/** The engine choices that need a stream restart to change. */
private data class EngineConfig(
    val buffer: BufferPreset,
    val video: DecoderChoice,
    val audio: DecoderChoice,
    val passthrough: Passthrough,
    val decodeDtsToPcm: Boolean,
)

private fun engineConfig(s: PlaybackSettings, p: ChannelPlaybackPrefs) = EngineConfig(
    buffer = s.bufferPreset,
    video = p.video ?: s.videoDecoder,
    audio = p.audio ?: s.audioDecoder,
    passthrough = s.passthrough,
    decodeDtsToPcm = s.decodeDtsToPcm,
)

/** The track preferences that apply live, without a restart. */
private data class TrackPrefs(val audioLanguage: String?, val subtitleLanguage: String?, val preferSurround: Boolean) {
    constructor(s: PlaybackSettings) : this(s.audioLanguage, s.subtitleLanguage, s.preferSurround)
}

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
) : Playback {

    override val player: Player get() = exoPlayer

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var current: ChannelEntity? = null

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

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()
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
    }

    init {
        Log.setLogLevel(if (BuildConfig.DEBUG) Log.LOG_LEVEL_ERROR else Log.LOG_LEVEL_OFF)
        exoPlayer.addListener(listener)
        scope.launch {
            settings.distinctUntilChanged()
                .onCompletion { settingsLive = false }
                // A read error ends the live updates; stream starts then use their own read.
                .catch { }
                .collect(::onSettings)
        }
    }

    override fun play(channel: ChannelEntity) {
        val state = _state.value
        val active = (state is PlaybackState.Buffering && state.channelId == channel.id) ||
            (state is PlaybackState.Playing && state.channelId == channel.id)
        if (active) {
            // Same channel: resume if something paused it (focus loss, noisy, a UI), never reconnect.
            exoPlayer.playWhenReady = true
            return
        }

        // Release the old stream first (one connection per account), then resolve the new one.
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        cancelJobs()
        val generation = ++playGeneration
        current = channel
        resolved = null
        hlsFallbackTried = false
        remintable = false
        remintTried = false
        audioFallbackActive = false
        liveWindowRecoveries.clear()
        _tracks.value = PlayerTracks.NONE
        _state.value = PlaybackState.Buffering(channel.id)
        exoPlayer.playWhenReady = true
        startResolve(channel, generation)
        watchChannelPrefs(channel)
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
     * [newChannel] is false for a re-mint of the same play, which keeps the current track choices.
     */
    private fun startResolve(channel: ChannelEntity, generation: Long, forceHls: Boolean = false, newChannel: Boolean = true) {
        val keepChoices = restartChoices
        restartChoices = null
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
            load(channel, stream, asHls = forceHls || isHlsUrl(Uri.parse(stream.url)))
            startKeepAlive(channel)
        }
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
        val channel = current ?: return
        val state = _state.value
        val active = state is PlaybackState.Buffering || state is PlaybackState.Playing
        if (active && appliedGeneration == playGeneration && engineConfig(s, appliedPrefs) != appliedEngine) {
            restart(channel)
        }
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
        stop()
        // Same channel: the track choices made on it survive the reconnect.
        if (sameChannel) restartChoices = choices
        play(channel)
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
        // Closing pooled sockets may touch the network, so it stays off the main thread.
        withContext(Dispatchers.IO) { evictConnections() }
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
        withContext(Dispatchers.IO) { evictConnections() }
        if (generation != playGeneration) return null
        return Handoff(stream, hls = isHlsUrl(Uri.parse(stream.url)))
    }

    private fun refreshTracks() {
        // Nothing playing, or the new channel is still resolving: late events from the previous
        // stream must not publish its tracks or frame rate for the new one.
        if (current == null || resolved == null) {
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
    }

    private fun load(channel: ChannelEntity, stream: ResolvedStream, asHls: Boolean) {
        currentIsHls = asHls
        val source = mediaSourceFactory(stream).createMediaSource(mediaItem(channel, stream, asHls))
        exoPlayer.setMediaSource(AudioDelayMediaSource(source, engine.audioDelay))
        exoPlayer.prepare()
        _state.value = PlaybackState.Buffering(channel.id)
    }

    override fun stop() {
        prefsJob?.cancel()
        prefsJob = null
        cancelJobs()
        playGeneration++
        current = null
        resolved = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _tracks.value = PlayerTracks.NONE
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
        resolved = null
        _state.value = PlaybackState.Idle
        scope.cancel()
    }

    private fun refreshState() {
        val channel = current ?: return
        // Still resolving (or re-minting): the player holds nothing of this play yet.
        val stream = resolved ?: return
        val error = exoPlayer.playerError
        if (error != null && recover(channel, stream, error)) return
        _state.value = when {
            error != null -> PlaybackState.Error(channel.id, errorMessage(channel.displayName, error))
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering(channel.id)
            exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY ->
                PlaybackState.Playing(channel.id, streamTags(exoPlayer.videoFormat, exoPlayer.audioFormat))
            // A live stream that ends has dropped; surface it so play(sameChannel) can retry.
            exoPlayer.playbackState == Player.STATE_ENDED ->
                PlaybackState.Error(channel.id, "${channel.displayName}: can't play this stream")
            else -> _state.value
        }
    }

    /** Handles errors that have an automatic fix; returns true when playback was restarted. */
    private fun recover(channel: ChannelEntity, stream: ResolvedStream, error: PlaybackException): Boolean = when {
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
            load(channel, stream, asHls = currentIsHls)
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
        else -> false
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
        val builder = MediaItem.Builder().setUri(Uri.parse(stream.url)).setMediaId(channel.id.toString())
        if (asHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private companion object {
        const val MAX_LIVE_WINDOW_RECOVERIES = 3
        const val LIVE_WINDOW_SPAN_MS = 30_000L
        val REMINT_HTTP_CODES = setOf(403, 404, 410)

        fun versionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        } catch (_: Exception) {
            "dev"
        }
    }
}

private val HLS_QUERY_HINTS = mapOf("output" to setOf("m3u8", "hls"), "extension" to setOf("m3u8"))

/**
 * HLS by URL shape: the path ends in `.m3u8`, has a `/hls/` segment, or the query says
 * `output=m3u8|hls` / `extension=m3u8` (Xtream style). All case-insensitive.
 */
internal fun isHlsUrl(uri: Uri): Boolean {
    val path = uri.path.orEmpty().lowercase()
    if (path.endsWith(".m3u8") || "/hls/" in path) return true
    if (!uri.isHierarchical) return false
    return uri.queryParameterNames.any { name ->
        val accepted = HLS_QUERY_HINTS[name.lowercase()] ?: return@any false
        uri.getQueryParameters(name).any { it.lowercase() in accepted }
    }
}

private fun PlaybackException.httpCode(): Int? =
    generateSequence<Throwable>(this) { it.cause }.take(16)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

@OptIn(UnstableApi::class)
private fun PlaybackException.isAudioRendererError(): Boolean =
    this is ExoPlaybackException && type == ExoPlaybackException.TYPE_RENDERER &&
        MimeTypes.isAudio(rendererFormat?.sampleMimeType)

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is T }
