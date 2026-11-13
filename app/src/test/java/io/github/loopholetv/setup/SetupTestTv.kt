package io.github.loopholetv.setup

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastAnnounce
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.PeerTable
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.repo.LocalM3uOpener
import io.github.loopholetv.data.settings.SettingsStore
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/** One TV for setup tests: its own database, settings, files, server, peer list and controller. */
internal class SetupTestTv(
    val id: String,
    val name: String,
    root: File,
    scope: CoroutineScope,
    http: OkHttpClient,
    clock: () -> Long,
    lifetimeMs: Long = SetupRequest.LIFETIME_MS,
    noticeMs: Long = 300,
) {
    val db: AppDatabase = inMemoryDb()
    val settings = SettingsStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { File(root, "$id/settings.preferences_pb") },
    )
    val sources = File(root, "$id/sources")
    val files = LocalM3uOpener(ApplicationProvider.getApplicationContext<Context>().contentResolver, sources)
    val server = LocalHttpServer(scope)
    val table = PeerTable(id, clock, expiryMs = Long.MAX_VALUE / 4)
    val importCalls = AtomicInteger()
    val imported = CopyOnWriteArrayList<SetupImportResult.Ok>()

    /** Replaces the real import when set. */
    @Volatile var importOverride: (suspend (SetupBundle) -> SetupImportResult)? = null

    /** Replaces the real export when set. */
    @Volatile var exportOverride: (suspend (String) -> SetupExportResult)? = null

    /** Replaces the real temp-file staging when set. */
    @Volatile var stageOverride: (() -> Pair<File, java.io.OutputStream>?)? = null

    /** The heap the host believes it has. */
    @Volatile var availableMemory: Long = Long.MAX_VALUE / 4

    /** The heap the host sees after it asked for a garbage collection; null: unchanged. */
    @Volatile var availableAfterGc: Long? = null
    val gcCalls = AtomicInteger()

    /** Replaces this TV's cast id lookup when set. */
    @Volatile var selfIdOverride: (suspend () -> String)? = null

    /** Replaces the buffer a sealed setup is read into when set. */
    @Volatile var bufferOverride: ((Int) -> ByteArray)? = null

    val controller = SetupController(
        scope = scope,
        server = server,
        table = table,
        http = http,
        export = { name -> exportOverride?.invoke(name) ?: SetupExporter(db, settings, files).export(name) },
        import = { bundle ->
            importCalls.incrementAndGet()
            importOverride?.invoke(bundle) ?: SetupImporter(db, settings, files).import(bundle)
        },
        stageFile = { stageOverride?.invoke() ?: files.newTemp() },
        clock = clock,
        selfId = { selfIdOverride?.invoke() ?: id },
        selfName = { name },
        onImported = { imported += it },
        hasPlaylists = { db.setupDao().hasPlaylists() },
        lifetimeMs = lifetimeMs,
        availableMemory = { availableMemory },
        newBuffer = { size -> bufferOverride?.invoke(size) ?: ByteArray(size) },
        collectGarbage = {
            gcCalls.incrementAndGet()
            availableAfterGc?.let { availableMemory = it }
        },
        noticeMs = noticeMs,
    )
    val port: Int

    init {
        controller.register()
        controller.register() // a second call is harmless
        port = server.start(listOf(LOOPBACK))
    }

    val state: SetupState get() = controller.state.value

    /** Lists [other] as a peer seen at [host]. */
    fun sees(other: SetupTestTv, setup: Int = 1, host: String = "127.0.0.1") = seesPeer(other.id, other.name, other.port, setup, host)

    fun seesPeer(id: String, name: String, port: Int, setup: Int = 1, host: String = "127.0.0.1") =
        table.seen(CastAnnounce(id, name, port, CastStatus.IDLE, true, null, null, null, seq = 1, setup = setup), host)

    fun await(timeoutMs: Long = 30_000, what: (SetupState) -> Boolean): SetupState = runBlocking {
        withTimeout(timeoutMs) { controller.state.first(what) }
    }

    fun awaitDone(): SetupState.Done = await { it is SetupState.Done } as SetupState.Done

    fun code(): String = (await { it is SetupState.ShowingCode } as SetupState.ShowingCode).code

    /** Temp files left in this TV's playlist file folder. */
    fun temps(): List<File> = sources.listFiles().orEmpty().filter { it.name.startsWith(".tmp-") }

    fun playlistNames(): List<String> = runBlocking { db.setupDao().playlists().map { it.name } }

    fun addPlaylist(name: String, type: PlaylistType = PlaylistType.M3U, url: String? = "http://$name.example.test/list.m3u") =
        runBlocking {
            db.playlistDao().insert(
                PlaylistEntity(
                    name = name, type = type, url = url, xtreamServer = null, xtreamUser = null, xtreamPass = null,
                    epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
                ),
            )
        }

    fun close() {
        server.stop()
        db.close()
    }

    companion object {
        /** Polls [check] until it holds, for conditions outside a TV's state. */
        fun eventually(timeoutMs: Long = 30_000, check: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!check()) {
                if (System.nanoTime() > deadline) throw AssertionError("timed out")
                Thread.sleep(20)
            }
        }

        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
