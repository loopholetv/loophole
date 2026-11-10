package io.github.loopholetv

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.WorkManager
import io.github.loopholetv.data.db.ALL_MIGRATIONS
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.repo.EpgRepository
import io.github.loopholetv.data.repo.EpgStoreOptions
import io.github.loopholetv.data.repo.FavouritesRepository
import io.github.loopholetv.data.repo.HistoryRepository
import io.github.loopholetv.data.repo.PlaylistRepository
import io.github.loopholetv.data.repo.ScopedChannels
import io.github.loopholetv.data.repo.RefreshCoordinator
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.player.HistoryRecorder
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackManager
import io.github.loopholetv.work.RefreshScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph; one per process, created by [LoopholeApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val db: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "loophole.db")
            // AUTOMATIC falls back to TRUNCATE on low-RAM devices (Fire TV sticks), where a long
            // playlist refresh transaction would then block every read; WAL keeps readers going.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val xtream: XtreamClient by lazy { XtreamClient(http) }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            db, http, xtream,
            overlay = channelOverlay,
            local = localM3u,
            stalker = stalkerClient,
            sessions = stalkerSessions,
            downloadUa = downloadUserAgent,
        )
    }

    val epgRepository: EpgRepository by lazy {
        EpgRepository(
            db, http, xtream,
            options = { settingsStore.current().let { EpgStoreOptions(it.epgPastDays, it.epgStoreDescriptions) } },
            userAgentFor = downloadUserAgent,
            portalEpg = { p, emit ->
                io.github.loopholetv.data.repo.readPortalGuide(p, db.maintenanceDao(), stalkerClient, stalkerSessions, emit)
            },
        )
    }

    /** The only DataStore instance for the "settings" file. */
    val settingsStore: SettingsStore by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { appContext.preferencesDataStoreFile("settings") },
        )
    }

    /** Holds back the first playback while the start-up splash shows. */
    val startGate = io.github.loopholetv.ui.splash.StartGate()

    /** The one player for the app lifetime (never released). */
    val playback: Playback by lazy {
        // Start collecting settings now, not at the first play.
        val settings = settingsSnapshot
        PlaybackManager(
            appContext, http,
            exoPlayer = io.github.loopholetv.player.engine.buildEnginePlayer(appContext, engineParts),
            resolver = io.github.loopholetv.player.PlaylistStreamResolver(
                db.playlistDao()::byId, { settings.await() },
                io.github.loopholetv.data.net.UserAgents.default(BuildConfig.VERSION_NAME), stalkerSessions, stalkerClient,
            ),
            engine = engineParts,
            // A read error ends the live updates and keeps what is applied; it never resets to defaults.
            settings = settingsStore.settings.map { it.playback }.catch { },
            awaitSettings = { settings.await().playback },
            channelPrefs = { channelPlayback.get(io.github.loopholetv.data.db.ChannelRef(it.playlistId, it.key)) },
            observeChannelPrefs = { channelPlayback.observe(io.github.loopholetv.data.db.ChannelRef(it.playlistId, it.key)) },
        )
    }

    val favourites: FavouritesRepository by lazy { FavouritesRepository(db) }

    val history: HistoryRepository by lazy { HistoryRepository(db) }

    val scopedChannels: ScopedChannels by lazy { ScopedChannels(db.channelDao(), db.favouriteDao()) }

    /** Background/startup updates through the shared repositories (same Mutexes as Settings). */
    val refreshCoordinator: RefreshCoordinator by lazy {
        RefreshCoordinator(playlistRepository, epgRepository, settingsStore)
    }

    /** WorkManager comes from its default App Startup initializer. */
    val refreshScheduler: RefreshScheduler by lazy { RefreshScheduler(WorkManager.getInstance(appContext)) }

    /** Adds channels watched for 10 s to History; started once at app level. */
    val historyRecorder: HistoryRecorder by lazy { HistoryRecorder(playback.state, db.channelDao()::byId, history::record) }

    /** Writes channel/group/playlist edits into the channel table's derived columns. */
    val channelOverlay: io.github.loopholetv.data.repo.ChannelOverlay by lazy { io.github.loopholetv.data.repo.ChannelOverlay(db) }

    /** The user's channel, group and playlist edits (each one transaction). */
    val channelEdits: io.github.loopholetv.data.repo.ChannelEdits by lazy { io.github.loopholetv.data.repo.ChannelEdits(db, channelOverlay) }

    /** Local M3U files; last-good copies in filesDir/sources/<playlistId>.m3u. */
    internal val localM3u: io.github.loopholetv.data.repo.LocalM3uOpener by lazy { io.github.loopholetv.data.repo.LocalM3uOpener(appContext.contentResolver, java.io.File(appContext.filesDir, "sources")) }

    /** Programme reads with each channel's guide time shift applied (guide, info bar, search). */
    val shiftedProgrammes: io.github.loopholetv.data.repo.ShiftedProgrammes by lazy {
        io.github.loopholetv.data.repo.ShiftedProgrammes(db.programmeDao())
    }

    /** MAC portal API client; portal calls always use the MAG user agent. */
    val stalkerClient: io.github.loopholetv.data.stalker.StalkerClient by lazy { io.github.loopholetv.data.stalker.StalkerClient(http) }

    /** One portal session per playlist; the token is saved only from session acquisition. */
    val stalkerSessions: io.github.loopholetv.data.stalker.StalkerSessions by lazy {
        io.github.loopholetv.data.stalker.StalkerSessions(stalkerClient, db.playlistDao()::byId) { id, e, t ->
            db.playlistDao().setPortalSession(id, e, t)
        }
    }

    /** The latest settings without suspending (play-time resolution); container-owned, lives for the process. */
    val settingsSnapshot: io.github.loopholetv.data.settings.SettingsSnapshot by lazy {
        io.github.loopholetv.data.settings.SettingsSnapshot(
            settingsStore.settings,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
    }

    /** User agent for downloads (M3U, Xtream, EPG): playlist > general > Loophole/<version>; null while adding. */
    val downloadUserAgent: suspend (io.github.loopholetv.data.db.PlaylistEntity?) -> String = { p ->
        io.github.loopholetv.data.net.UserAgents.forDownload(
            p?.userAgent,
            settingsStore.current().userAgent,
            io.github.loopholetv.data.net.UserAgents.default(BuildConfig.VERSION_NAME),
        )
    }

    /** Per-channel playback overrides (decoders, external player, aspect), read at play time. */
    val channelPlayback: io.github.loopholetv.data.repo.ChannelPlayback by lazy {
        io.github.loopholetv.data.repo.ChannelPlayback(db.channelPrefsDao())
    }

    /** Whether the full-screen player is showing, for the picture-in-picture decision on leaving. */
    val pipGate: io.github.loopholetv.player.PipGate by lazy { io.github.loopholetv.player.PipGate() }

    /** Whether this device supports picture-in-picture; fixed for the process. */
    val pipSupported: Boolean by lazy { io.github.loopholetv.player.pipSupported(appContext) }

    /** Main-thread scope for work that must touch the player. */
    val mainScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    }

    /** Stops playback after a chosen duration; ticks and expiry run on the main thread. */
    val sleepTimer: io.github.loopholetv.player.SleepTimer by lazy {
        io.github.loopholetv.player.SleepTimer(mainScope, onExpire = { playback.stop() })
    }

    /** Lets the guide keep its current preview after the player closes. */
    val previewHold: io.github.loopholetv.player.PreviewHold by lazy { io.github.loopholetv.player.PreviewHold() }

    /** The player's switchable engine parts; the large buffer is capped on devices with a small heap. */
    val engineParts: io.github.loopholetv.player.engine.EngineParts by lazy {
        val memoryClass = (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        io.github.loopholetv.player.engine.EngineParts(
            loadControl = io.github.loopholetv.player.engine.SwitchableLoadControl(
                io.github.loopholetv.data.settings.BufferPreset.NORMAL, memoryClass,
            ),
        )
    }
}
