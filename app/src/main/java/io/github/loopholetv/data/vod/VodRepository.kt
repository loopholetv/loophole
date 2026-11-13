package io.github.loopholetv.data.vod

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

private const val PAGE_SIZE = 30

/** Browse, search and play-source reads for films and shows from playlists. */
class VodRepository(db: AppDatabase, private val clock: () -> Instant) {
    private val dao = db.vodBrowseDao()

    val hasFilms: Flow<Boolean> = dao.hasFilms()
    val hasShows: Flow<Boolean> = dao.hasShows()

    /** Merged by title across enabled playlists, in the order of the first playlist that has each. */
    fun filmCategories(): Flow<List<CategoryRow>> = dao.filmCategories()

    fun films(category: String): Flow<PagingData<FilmCard>> =
        Pager(PagingConfig(PAGE_SIZE)) { dao.films(category) }.flow

    fun recentFilms(): Flow<List<FilmCard>> = flow { emitAll(dao.recentFilms(recentSince(), RECENT_LIMIT)) }

    fun showCategories(): Flow<List<CategoryRow>> = dao.showCategories()

    /** A null [category] lists the shows without one ("All shows"). */
    fun shows(category: String?): Flow<PagingData<ShowCard>> =
        Pager(PagingConfig(PAGE_SIZE)) { dao.shows(category) }.flow

    fun showCount(category: String?): Flow<Int> = dao.showCount(category)

    fun recentShows(): Flow<List<ShowCard>> = flow { emitAll(dao.recentShows(recentSince(), RECENT_LIMIT)) }

    fun film(playlistId: Long, key: String): Flow<FilmDetail?> =
        dao.film(playlistId, key).map { row -> row?.let { FilmDetail(it.film, it.playlistName) } }

    fun show(playlistId: Long, key: String): Flow<ShowDetail?> =
        combine(dao.show(playlistId, key), dao.episodesOf(playlistId, key)) { show, episodes ->
            show?.let { ShowDetail(it, seasonsOf(episodes)) }
        }

    suspend fun searchFilms(pattern: String, limit: Int): List<FilmCard> = dao.searchFilms(pattern, limit)

    suspend fun searchShows(pattern: String, limit: Int): List<ShowCard> = dao.searchShows(pattern, limit)

    /** Hidden group titles that hold films or shows, with the playlists each is hidden in. */
    fun hiddenCategories(): Flow<List<HiddenCategory>> = dao.hiddenGroups().map { rows ->
        rows.groupBy { it.groupTitle }.map { (title, hidden) -> HiddenCategory(title, hidden.map { it.playlistId }) }
    }

    suspend fun playlistsWithCategory(title: String): List<Long> = dao.playlistsWithCategory(title)

    /** The stream and request headers of [ref]'s row, or null when it no longer exists. */
    suspend fun playable(ref: VodRef): VodPlayable? = when (ref.kind) {
        VodKind.MOVIE -> dao.filmRow(ref.playlistId, ref.key)?.let {
            VodPlayable(ref, it.name, it.streamUrl, it.userAgent, it.referrer)
        }
        VodKind.EPISODE -> dao.episodeRow(ref.playlistId, ref.key)?.let {
            VodPlayable(ref, it.name, it.streamUrl, it.userAgent, it.referrer)
        }
    }

    private fun recentSince(): Instant = clock().minus(Duration.ofDays(RECENT_DAYS.toLong()))

    private fun seasonsOf(episodes: List<EpisodeEntity>): List<Season> =
        sortEpisodes(episodes, { it.season }, { it.episode }, { it.position })
            .groupBy { it.season }
            .map { (number, items) -> Season(number, items) }
}
