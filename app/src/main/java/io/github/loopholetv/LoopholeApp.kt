package io.github.loopholetv

import android.app.Application
import android.util.Log
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.repo.StartupRefresher
import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.work.RefreshRunnerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Swallows uncaught background failures so they can't crash the app. Messages may contain playlist
 * URLs with credentials, so nothing but the exception class name is ever logged (debug builds only).
 */
internal fun backgroundErrorHandler(log: (String) -> Unit = { Log.w("Loophole", it) }) =
    CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException && BuildConfig.DEBUG) log("Background task failed: ${e.javaClass.name}")
    }

/** Open only for the test subclass that initialises a test WorkManager under Robolectric. */
open class LoopholeApp : Application(), RefreshRunnerProvider {
    val container: AppContainer by lazy { AppContainer(this) }

    /** Process-lifetime scope for background work that must outlive any Activity. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + backgroundErrorHandler())

    override val refreshCoordinator get() = container.refreshCoordinator

    /** Started from MainActivity once it has confirmed a playlist exists. */
    val startupRefresher: StartupRefresher by lazy {
        StartupRefresher(
            container.refreshCoordinator,
            applicationScope,
            container.vodFill,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // WorkManager is initialised by its default App Startup provider, which runs before onCreate.
        container.refreshScheduler.observe(
            container.settingsStore.settings,
            container.playlistRepository.playlists(),
            applicationScope,
        )
        // Watch history: channels played for 10 s or more, wherever playback started.
        container.historyRecorder.start(applicationScope)
        // Turning off or deleting the playing channel's playlist stops playback.
        stopPlaybackWhenPlaylistGoes(
            state = container.playback.state,
            playlists = container.playlistRepository.playlists(),
            playlistOf = { id -> container.db.channelDao().byId(id)?.playlistId },
            stop = { container.playback.stop() },
            scope = applicationScope,
        )
        // Hiding the playing channel (or its group) anywhere, e.g. in Settings over the guide preview.
        stopPlaybackWhenChannelHidden(
            state = container.playback.state,
            visibleOf = { id -> container.db.channelDao().observeVisible(id) },
            stop = { container.playback.stop() },
            scope = applicationScope,
        )
    }
}

/**
 * Stops playback once the playing channel's playlist is turned off or deleted. The channel's
 * playlist is looked up when it starts playing, so a channel that merely vanished in a refresh
 * doesn't stop here. [stop] runs on the main thread.
 *
 * Never ends on an error (a failing lookup counts as unknown; anything else restarts the watch
 * after [retryDelayMs]); nothing is logged, since messages may carry stream URLs.
 */
internal fun stopPlaybackWhenPlaylistGoes(
    state: Flow<PlaybackState>,
    playlists: Flow<List<PlaylistEntity>>,
    playlistOf: suspend (channelId: Long) -> Long?,
    stop: () -> Unit,
    scope: CoroutineScope,
    main: CoroutineDispatcher = Dispatchers.Main,
    retryDelayMs: Long = 1_000,
): Job {
    val playing: Flow<Long?> = state.map { it.channelId() }.distinctUntilChanged()
        .map { id -> id?.let { lookUp(playlistOf, it) } }
    return combine(playing, playlists.map { list -> list.filter { it.enabled }.mapTo(HashSet()) { it.id } }) { pid, on ->
        pid != null && pid !in on
    }
        .distinctUntilChanged()
        .onEach { gone -> if (gone) withContext(main) { stop() } }
        .retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            delay(retryDelayMs)
            true
        }
        .launchIn(scope)
}

/**
 * Stops playback once the playing channel turns hidden (itself, its group, or its playlist
 * turned off), e.g. from the Settings panel while the guide preview plays underneath. A row that
 * vanishes (null, e.g. mid-refresh) does not count. Never ends on an error: the watch restarts after
 * [retryDelayMs], logging nothing. [stop] runs on [main], and only while the hidden channel is
 * still the one playing ([currentId]): hiding the channel in the full-screen player zaps to the
 * next one, and a late signal for the old id must not stop the new stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun stopPlaybackWhenChannelHidden(
    state: StateFlow<PlaybackState>,
    visibleOf: (channelId: Long) -> Flow<Boolean?>,
    stop: () -> Unit,
    scope: CoroutineScope,
    main: CoroutineDispatcher = Dispatchers.Main,
    retryDelayMs: Long = 1_000,
    currentId: () -> Long? = { state.value.channelId() },
): Job = state.map { it.channelId() }.distinctUntilChanged()
    .flatMapLatest { id ->
        if (id == null) flowOf(null) else visibleOf(id).distinctUntilChanged().map { visible -> id.takeIf { visible == false } }
    }
    .distinctUntilChanged()
    .onEach { hiddenId -> if (hiddenId != null) withContext(main) { if (currentId() == hiddenId) stop() } }
    .retryWhen { cause, _ ->
        if (cause is CancellationException) return@retryWhen false
        delay(retryDelayMs)
        true
    }
    .launchIn(scope)

private suspend fun lookUp(playlistOf: suspend (Long) -> Long?, channelId: Long): Long? = try {
    playlistOf(channelId)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** The playing channel of this TV's own playlists; null for another TV's cast, which has none. */
private fun PlaybackState.channelId(): Long? = when (this) {
    PlaybackState.Idle -> null
    is PlaybackState.Buffering -> channelId
    is PlaybackState.Playing -> channelId
    is PlaybackState.Error -> channelId
}?.takeIf { it != CAST_CHANNEL_ID }
