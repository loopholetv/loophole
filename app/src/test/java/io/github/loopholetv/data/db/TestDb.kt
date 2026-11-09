package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.PlaylistRepository
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Shared helpers for the Room DAO tests (in-memory database, rows inserted directly). */
internal fun inMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

internal suspend fun AppDatabase.addPlaylist(name: String = "P"): Long = playlistDao().insert(
    PlaylistEntity(
        name = name, type = PlaylistType.M3U, url = null, xtreamServer = null, xtreamUser = null,
        xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
    ),
)

internal fun channel(
    playlistId: Long,
    key: String,
    number: Int,
    name: String = key,
    epgChannelId: String? = null,
    logo: String? = null,
    // Derived columns (normally written by ChannelOverlay), defaulted like ChannelEntity's own defaults.
    visible: Boolean = true,
    guideNumber: Int = number,
    groupRank: Int = number,
    groupTitle: String = "G",
    customName: String? = null,
    guideEpgId: String? = epgChannelId,
    shift: Int = 0,
) = ChannelEntity(
    playlistId = playlistId, key = key, number = number, name = name, streamUrl = "http://s.example.test/$key",
    logo = logo, groupTitle = groupTitle, groupOrder = 0, sortOrder = number, tvgId = null, tvgName = null,
    userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
    epgChannelId = epgChannelId, visible = visible, guideNumber = guideNumber, groupRank = groupRank,
    customName = customName, guideEpgId = guideEpgId, epgShiftMinutes = shift,
)

/** Inserts [channels] and returns every channel of their playlists as stored (with ids), by key. */
internal suspend fun AppDatabase.insertChannels(vararg channels: ChannelEntity): Map<String, ChannelEntity> {
    channelDao().insertAll(channels.toList())
    return channels.map { it.playlistId }.distinct()
        .flatMap { channelDao().channelsOf(it) }
        .associateBy { it.key }
}

/** Raw row count of [table] (also rows the joined DAO reads hide). */
internal suspend fun AppDatabase.rowCount(table: String): Int = withContext(Dispatchers.IO) {
    query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
}

internal suspend fun <T : Any> PagingSource<Int, T>.loadAll(): List<T> {
    val page = load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1000, placeholdersEnabled = false))
    return (page as PagingSource.LoadResult.Page).data
}

/** An M3U playlist served by MockWebServer, imported and refreshed through the real [PlaylistRepository]. */
internal class M3uPlaylist(private val db: AppDatabase) : AutoCloseable {
    private val server = MockWebServer().also { it.start() }
    private val http = OkHttpClient()
    val repo = PlaylistRepository(db, http, XtreamClient(http))
    var id = 0L
        private set

    private fun body(names: List<String>) = buildString {
        append("#EXTM3U\n")
        names.forEach { append("#EXTINF:-1 group-title=\"G\",$it\nhttp://stream.example.test/$it.ts\n") }
    }

    suspend fun add(vararg names: String): Long {
        server.enqueue(MockResponse().setBody(body(names.toList())))
        id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        return id
    }

    /** Fails with the import error instead of an opaque ClassCastException when [result] isn't Success. */
    private fun successOf(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("import failed: ${result.error} ${result.message}")
    }

    suspend fun refresh(vararg names: String) {
        server.enqueue(MockResponse().setBody(body(names.toList())))
        successOf(repo.refresh(id) {})
    }

    suspend fun channel(name: String): ChannelEntity = db.channelDao().channelsOf(id).single { it.name == name }

    override fun close() = server.shutdown()
}
