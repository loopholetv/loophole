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
import io.github.loopholetv.data.repo.epgStoreOptions
import io.github.loopholetv.data.repo.FavouritesRepository
import io.github.loopholetv.data.repo.HistoryRepository
import io.github.loopholetv.data.repo.PlaylistRepository
import io.github.loopholetv.data.repo.ScopedChannels
import io.github.loopholetv.data.repo.RefreshCoordinator
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.async
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
            stops = refreshStops,
        )
    }

    /** Lets a copied setup stop the playlist and guide updates in flight. */
    private val refreshStops = io.github.loopholetv.data.repo.RefreshStops()

    val epgRepository: EpgRepository by lazy {
        EpgRepository(
            db, http, xtream,
            options = { epgStoreOptions(settingsStore.current()) { db.channelDao().maxCatchupDays() } },
            userAgentFor = downloadUserAgent,
            portalEpg = { p, emit ->
                io.github.loopholetv.data.repo.readPortalGuide(p, db.maintenanceDao(), stalkerClient, stalkerSessions, emit)
            },
            stops = refreshStops,
        )
    }

    /** The only DataStore instance for the "settings" file (settings and update-check state). */
    private val settingsDataStore by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { appContext.preferencesDataStoreFile("settings") }
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(settingsDataStore) }

    /** Release check, download and install of app updates; lives for the process. */
    val updater: io.github.loopholetv.update.Updater by lazy {
        val ua = io.github.loopholetv.data.net.UserAgents.default(BuildConfig.VERSION_NAME)
        // Release builds fetch only https GitHub URLs; debug builds also allow a local test server.
        val policy = if (BuildConfig.DEBUG) {
            io.github.loopholetv.update.UpdateUrlPolicy.ANY_HTTP
        } else {
            io.github.loopholetv.update.UpdateUrlPolicy.GITHUB_ONLY
        }
        val updateHttp = io.github.loopholetv.update.updateHttpClient(http, policy)
        val client = io.github.loopholetv.update.ReleaseClient(
            updateHttp, { io.github.loopholetv.update.releaseUrl(appContext) }, ua, policy,
        )
        io.github.loopholetv.update.Updater(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            checker = io.github.loopholetv.update.UpdateChecker(
                client::latest, io.github.loopholetv.update.UpdatePrefs(settingsDataStore, policy), BuildConfig.VERSION_NAME,
            ),
            downloader = io.github.loopholetv.update.UpdateDownloader(
                updateHttp, java.io.File(appContext.cacheDir, "updates"), ua, policy,
                freeSpace = { io.github.loopholetv.update.freeBytesFor(appContext, it) },
            ),
            platform = io.github.loopholetv.update.AndroidInstallPlatform(appContext),
            enabled = { settingsStore.current().checkForUpdates },
            gate = startGate::await,
        )
    }

    /** Holds back the first playback while the start-up splash shows. */
    val startGate = io.github.loopholetv.ui.splash.StartGate()

    /** Stream addresses for the player: through this TV's relay while it casts, else straight. */
    internal val playbackResolver: io.github.loopholetv.player.StreamResolver by lazy {
        io.github.loopholetv.cast.CastingResolver(
            io.github.loopholetv.player.PlaylistStreamResolver(
                db.playlistDao()::byId, { settingsSnapshot.await() },
                io.github.loopholetv.data.net.UserAgents.default(BuildConfig.VERSION_NAME), stalkerSessions, stalkerClient,
                xtream = xtream,
                storeArchiveInfo = db.playlistDao()::setArchiveInfo,
            ),
            castHost,
        )
    }

    /** The one player for the app lifetime (never released). */
    val playback: Playback by lazy {
        // Start collecting settings now, not at the first play.
        val settings = settingsSnapshot
        PlaybackManager(
            appContext, http,
            exoPlayer = io.github.loopholetv.player.engine.buildEnginePlayer(appContext, engineParts),
            resolver = playbackResolver,
            engine = engineParts,
            // A read error ends the live updates and keeps what is applied; it never resets to defaults.
            settings = settingsStore.settings.map { it.playback }.catch { },
            awaitSettings = { settings.await().playback },
            channelPrefs = { channelPlayback.get(io.github.loopholetv.data.db.ChannelRef(it.playlistId, it.key)) },
            observeChannelPrefs = { channelPlayback.observe(io.github.loopholetv.data.db.ChannelRef(it.playlistId, it.key)) },
            // The relay's provider connections are released with the player's own.
            evictConnections = {
                http.connectionPool.evictAll()
                castUpstreamHttp.connectionPool.evictAll()
            },
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

    /** This device's app heap in MB; buffers and caches are sized to it. */
    private val memoryClass: Int by lazy {
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
    }

    /** The player's switchable engine parts; the large buffer is capped on devices with a small heap. */
    val engineParts: io.github.loopholetv.player.engine.EngineParts by lazy {
        io.github.loopholetv.player.engine.EngineParts(
            loadControl = io.github.loopholetv.player.engine.SwitchableLoadControl(
                io.github.loopholetv.data.settings.BufferPreset.NORMAL, memoryClass,
            ),
        )
    }

    // --- Casting between TVs -------------------------------------------------------------------
    // Built on first use; MainActivity starts and stops it with the app's visibility.

    /** Service discovery for casting; tests replace it before first use, so no real one starts. */
    internal var castNsd: () -> io.github.loopholetv.cast.NsdPort = { io.github.loopholetv.cast.AndroidNsd(appContext) }

    /** The beacon's UDP port; tests set 0 (a free port, no beacons sent) before first use. */
    internal var castBeaconPort: Int = io.github.loopholetv.cast.CastDiscovery.DEFAULT_BEACON_PORT

    /**
     * Relay jobs, the local server, discovery and setup copies; lives for the process. A failure
     * that escapes is swallowed, logging its class name only.
     */
    private val castScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + backgroundErrorHandler(),
        )
    }

    /** Holds joining a cast on its own while the first-start screens show; released by the navigation. Main thread. */
    internal val autoJoinHold: io.github.loopholetv.cast.AutoJoinHold by lazy {
        io.github.loopholetv.cast.AutoJoinHold { castController.recheckAutoJoin() }
    }

    /** This TV's casting id once loaded; null until then. */
    @Volatile private var castSelfIdLoaded: String? = null

    /** This TV's casting id: made once and stored, loaded off the main thread on first use. */
    private val castSelfId: kotlinx.coroutines.Deferred<String> by lazy {
        castScope.async(kotlinx.coroutines.Dispatchers.IO) { settingsStore.castSelfId().also { castSelfIdLoaded = it } }
    }

    /**
     * The relay's client for provider requests, with a pool of its own so the relay can drop its
     * idle provider connections without touching anything else; the player evicts it with [http]'s.
     */
    private val castUpstreamHttp: OkHttpClient by lazy {
        http.newBuilder().connectionPool(okhttp3.ConnectionPool()).build()
    }

    /** For other TVs' event streams only: a silent casting TV counts as lost after 8 s. */
    private val castHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    val castServer: io.github.loopholetv.cast.net.LocalHttpServer by lazy {
        io.github.loopholetv.cast.net.LocalHttpServer(castScope)
    }

    val peerTable: io.github.loopholetv.cast.PeerTable by lazy {
        io.github.loopholetv.cast.PeerTable({ castSelfIdLoaded }, android.os.SystemClock::elapsedRealtime)
    }

    val castHost: io.github.loopholetv.cast.CastHost by lazy {
        io.github.loopholetv.cast.CastHost(
            castScope, castServer, castUpstreamHttp, android.os.SystemClock::elapsedRealtime,
            // Called on the main thread, where the player may be read.
            positionMs = {
                val archive = playback.mode.value as? io.github.loopholetv.player.catchup.PlayMode.Archive
                if (archive?.seekable == true) playback.positionMs() else null
            },
            hlsCacheBytes = io.github.loopholetv.cast.relay.hlsCacheBytes(memoryClass),
        )
    }

    val castDiscovery: io.github.loopholetv.cast.CastDiscovery by lazy {
        io.github.loopholetv.cast.CastDiscovery.forDevice(
            appContext, castScope, peerTable, android.os.SystemClock::elapsedRealtime,
            nsd = castNsd(), beaconPort = castBeaconPort,
        )
    }

    val castClient: io.github.loopholetv.cast.CastClient by lazy { io.github.loopholetv.cast.CastClient(castHttp) }

    /** Casting and following on this TV; call it on the main thread. */
    val castController: io.github.loopholetv.cast.CastController by lazy {
        io.github.loopholetv.cast.CastController(
            scope = mainScope,
            selfId = { castSelfId.await() },
            playback = playback,
            host = castHost,
            discovery = castDiscovery,
            table = peerTable,
            client = castClient,
            server = castServer,
            // The home network for other TVs, and loopback for this TV's own player.
            binds = {
                listOfNotNull(io.github.loopholetv.cast.net.lanAddress(appContext), java.net.InetAddress.getByName("127.0.0.1"))
            },
            settings = settingsStore.settings.catch { },
            tvName = { io.github.loopholetv.data.settings.defaultTvName(appContext) },
            clock = System::currentTimeMillis,
            canAutoJoin = { autoJoinHold.allows() },
            holdWifi = io.github.loopholetv.cast.castWifiHold(appContext),
            watchNetwork = io.github.loopholetv.cast.defaultNetworkChanges(appContext),
        )
    }
    // --- End of casting between TVs ------------------------------------------------------------

    // --- Copying a setup between TVs -----------------------------------------------------------

    /**
     * Copies this TV's setup to or from another TV over the cast server, which runs only while
     * Loophole is on screen. Its routes are added once, here, and stay across server restarts.
     */
    internal val setupController: io.github.loopholetv.setup.SetupController by lazy {
        val opener = localM3u
        io.github.loopholetv.setup.SetupController(
            scope = castScope,
            server = castServer,
            table = peerTable,
            http = http,
            export = io.github.loopholetv.setup.SetupExporter(db, settingsStore, opener)::export,
            import = io.github.loopholetv.setup.SetupImporter(
                db, settingsStore, opener,
                // No playlist add, update or delete runs while the playlists are replaced.
                serialize = playlistRepository::exclusive,
            )::import,
            stageFile = opener::newTemp,
            clock = android.os.SystemClock::elapsedRealtime,
            selfId = { castSelfId.await() },
            // The name this TV announces to the others.
            selfName = { castController.selfAnnounce().name },
            onImported = { result ->
                io.github.loopholetv.setup.afterSetupImported(
                    result,
                    invalidateSession = stalkerSessions::invalidate,
                    refreshAll = { refreshCoordinator.refreshAll() },
                )
            },
            hasPlaylists = { db.setupDao().hasPlaylists() },
        ).also { it.register() }
    }

    /** Copying this TV's setup to or from another TV, for the setup screens. */
    val setup: io.github.loopholetv.setup.SetupView get() = setupController
}
