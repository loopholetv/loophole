package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.xtream.XtreamClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** One M3U entry; the stream URL (the channel key) depends only on [name], so it survives regrouping and renumbering. */
internal data class Entry(val name: String, val group: String = "G", val chno: Int? = null, val logo: String? = null)

internal fun entries(vararg names: String, group: String = "G") = names.map { Entry(it, group) }

/**
 * A multi-group, `tvg-chno`-capable M3U playlist served by MockWebServer, imported and refreshed
 * through the real [PlaylistRepository] (so the overlay runs inside the import transaction).
 */
internal class OverlayPlaylist(private val db: AppDatabase, private val name: String = "P") : AutoCloseable {
    private val server = MockWebServer().also { it.start() }
    private val http = OkHttpClient()
    val repo = PlaylistRepository(db, http, XtreamClient(http))
    var id = 0L
        private set

    private fun body(list: List<Entry>) = buildString {
        append("#EXTM3U\n")
        list.forEach { e ->
            append("#EXTINF:-1")
            e.chno?.let { append(" tvg-chno=\"$it\"") }
            e.logo?.let { append(" tvg-logo=\"$it\"") }
            append(" group-title=\"${e.group}\",${e.name}\n")
            append("http://stream.example.test/${e.name.replace(' ', '_')}.ts\n")
        }
    }

    suspend fun add(list: List<Entry>): Long {
        server.enqueue(MockResponse().setBody(body(list)))
        id = success(repo.addM3u(name, server.url("/list.m3u").toString()) {}).playlistId
        return id
    }

    suspend fun refresh(list: List<Entry>) {
        server.enqueue(MockResponse().setBody(body(list)))
        success(repo.refresh(id) {})
    }

    private fun success(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("import failed: ${result.error} ${result.message}")
        ImportResult.Stopped -> throw AssertionError("import stopped")
    }

    suspend fun all(): List<ChannelEntity> = db.channelDao().channelsOf(id)

    suspend fun channel(name: String): ChannelEntity = all().single { it.name == name }

    suspend fun channelOrNull(name: String): ChannelEntity? = all().singleOrNull { it.name == name }

    suspend fun ref(name: String): ChannelRef = channel(name).let { ChannelRef(it.playlistId, it.key) }

    /** Names of [group]'s channels in guide order (by groupRank). */
    suspend fun order(group: String = "G"): List<String> =
        all().filter { it.groupTitle == group }.sortedWith(compareBy({ it.groupRank }, { it.number })).map { it.name }

    override fun close() = server.shutdown()
}
