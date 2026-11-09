package io.github.loopholetv.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerLinkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@OptIn(UnstableApi::class)
class PlaybackManager internal constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val exoPlayer: ExoPlayer,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val resolver: StreamResolver = DirectResolver("Loophole/${versionName(context)}"),
    private val scope: CoroutineScope = MainScope(),
) : Playback {

    constructor(
        context: Context,
        http: OkHttpClient,
        resolver: StreamResolver = DirectResolver("Loophole/${versionName(context)}"),
    ) : this(context, http, buildPlayer(context), resolver = resolver)

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

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()
        override fun onTracksChanged(tracks: Tracks) = refreshState()
        override fun onVideoSizeChanged(videoSize: VideoSize) = refreshState()
        override fun onPlayerError(error: PlaybackException) = refreshState()
    }

    init {
        Log.setLogLevel(if (BuildConfig.DEBUG) Log.LOG_LEVEL_ERROR else Log.LOG_LEVEL_OFF)
        exoPlayer.addListener(listener)
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
        liveWindowRecoveries.clear()
        _state.value = PlaybackState.Buffering(channel.id)
        exoPlayer.playWhenReady = true
        startResolve(channel, generation)
    }

    /**
     * Resolves and loads [channel]. Undispatched, so it may load within the calling [play] when the
     * resolver doesn't suspend (DirectResolver); otherwise (a Room lookup, a portal) it continues on
     * [scope]. [forceHls] keeps a re-minted link on HLS when the previous one needed the fallback.
     */
    private fun startResolve(channel: ChannelEntity, generation: Long, forceHls: Boolean = false) {
        resolveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val (stream, canRemint) = try {
                resolver.resolve(channel) to resolver.canRemint(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == playGeneration) _state.value = PlaybackState.Error(channel.id, resolveErrorMessage(channel, e))
                return@launch
            }
            if (generation != playGeneration) return@launch
            remintable = canRemint
            resolved = stream
            load(channel, stream, asHls = forceHls || isHlsUrl(Uri.parse(stream.url)))
            startKeepAlive(channel)
        }
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
        exoPlayer.setMediaSource(mediaSourceFactory(stream).createMediaSource(mediaItem(channel, stream, asHls)))
        exoPlayer.prepare()
        _state.value = PlaybackState.Buffering(channel.id)
    }

    override fun stop() {
        cancelJobs()
        playGeneration++
        current = null
        resolved = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState.Idle
    }

    /** For tests only: the app keeps the player for its whole lifetime. */
    @VisibleForTesting
    internal fun release() {
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
        startResolve(channel, playGeneration, forceHls = wasHls)
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

        fun buildPlayer(context: Context): ExoPlayer {
            val renderers = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val audio = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            return ExoPlayer.Builder(context.applicationContext, renderers)
                .setLooper(Looper.getMainLooper())
                .setAudioAttributes(audio, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        }

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

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is T }
