package io.github.loopholetv.data.vod

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.filmGrid
import io.github.loopholetv.data.db.filterQuery
import io.github.loopholetv.data.db.showGrid
import io.github.loopholetv.data.tmdb.Genre
import io.github.loopholetv.data.tmdb.TmdbKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

private const val PAGE_SIZE = 30

/**
 * The Sort & filter grid reads a page with a pass over the whole library, so it reads few, larger
 * pages: four lines of six cards a page, the next one asked for two lines ahead, three to start.
 */
private val GRID_PAGING = PagingConfig(pageSize = 24, prefetchDistance = 12, enablePlaceholders = false, initialLoadSize = 72)

/** Browse, search and play-source reads for films and shows from playlists. */
class VodRepository(private val db: AppDatabase, private val clock: () -> Instant) {
    private val dao = db.vodBrowseDao()
    private val tmdb = db.tmdbDao()
    private val filter = db.vodFilterDao()

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

    /** The Sort & filter grid of Movies: one card per title, in [f]'s order. */
    fun filteredFilms(f: VodFilter): Flow<PagingData<FilmCard>> =
        Pager(GRID_PAGING) { filmGridSource(f) }.flow

    internal fun filmGridSource(f: VodFilter) = db.filmGrid(f)

    /** The Sort & filter grid of Series, like [filteredFilms]. */
    fun filteredShows(f: VodFilter): Flow<PagingData<ShowCard>> =
        Pager(GRID_PAGING) { db.showGrid(f) }.flow

    /** How many cards the grid for [kind] and [f] holds. */
    fun filteredCount(kind: TitleKind, f: VodFilter): Flow<Int> = filter.count(filterQuery(kind, f, countOnly = true))

    /** Whether any title of [kind] has details, which is when Sort & filter is offered without a key. */
    fun hasDetails(kind: TitleKind): Flow<Boolean> = filter.hasDetails(kind).distinctUntilChanged()

    /** The genres the grid for [kind] can offer: those of matched, visible titles, by name. */
    fun genresPresent(kind: TitleKind): Flow<List<Genre>> = filter.genresPresent(kind)

    fun film(playlistId: Long, key: String): Flow<FilmDetail?> =
        dao.film(playlistId, key).map { row -> row?.let { FilmDetail(it.film, it.playlistName) } }

    fun show(playlistId: Long, key: String): Flow<ShowDetail?> =
        combine(dao.show(playlistId, key), dao.episodesOf(playlistId, key)) { show, episodes ->
            show?.let { ShowDetail(it, seasonsOf(episodes)) }
        }

    /** TMDB's details for a film, or null while it is not matched (never looked up, not found or rejected). */
    fun filmDetails(playlistId: Long, key: String): Flow<TmdbDetail?> = details(playlistId, TitleKind.FILM, key)

    /** Like [filmDetails], with the episodes found for the show. */
    fun showDetails(playlistId: Long, key: String): Flow<TmdbDetail?> = details(playlistId, TitleKind.SHOW, key)

    /** Drops a title's match as wrong. Its page and cards go back to the playlist's name and poster. */
    suspend fun rejectDetails(kind: TitleKind, playlistId: Long, key: String) =
        tmdb.reject(playlistId, kind, key, clock())

    /** The TMDB title a match points at, with its badge. */
    private data class MatchTarget(val kind: TmdbKind, val id: Long, val badge: String?)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun details(playlistId: Long, kind: TitleKind, key: String): Flow<TmdbDetail?> =
        tmdb.observeMatch(playlistId, kind, key)
            .map { m ->
                if (m?.state == MatchState.MATCHED && m.tmdbKind != null && m.tmdbId != null) {
                    MatchTarget(m.tmdbKind, m.tmdbId, m.badge)
                } else {
                    null
                }
            }
            // Every write to vod_matches wakes the query; only a change to this title's match matters.
            .distinctUntilChanged()
            .flatMapLatest { target ->
                if (target == null) return@flatMapLatest flowOf(null)
                val episodes = if (target.kind == TmdbKind.TV) tmdb.episodes(target.id) else flowOf(emptyList())
                combine(tmdb.title(target.kind, target.id), tmdb.genresOf(target.kind, target.id), episodes) { t, genres, eps ->
                    t?.let { TmdbDetail(it, genres, target.badge, eps) }
                }
            }

    suspend fun searchFilms(pattern: String, limit: Int): List<FilmCard> = dao.searchFilms(pattern, limit)

    suspend fun searchShows(pattern: String, limit: Int): List<ShowCard> = dao.searchShows(pattern, limit)

    /** Hidden group titles that hold films or shows, with the playlists each is hidden in. */
    fun hiddenCategories(): Flow<List<HiddenCategory>> = dao.hiddenGroups().map { rows ->
        rows.groupBy { it.groupTitle }.map { (title, hidden) -> HiddenCategory(title, hidden.map { it.playlistId }) }
    }

    suspend fun playlistsWithCategory(title: String): List<Long> = dao.playlistsWithCategory(title)

    /** The stream and request headers of [ref]'s row, with its [displayTitle], or null when it no longer exists. */
    suspend fun playable(ref: VodRef): VodPlayable? = when (ref.kind) {
        VodKind.MOVIE -> dao.filmRow(ref.playlistId, ref.key)?.let {
            VodPlayable(ref, displayTitle(ref) ?: it.name, it.streamUrl, it.userAgent, it.referrer)
        }
        VodKind.EPISODE -> dao.episodeRow(ref.playlistId, ref.key)?.let {
            VodPlayable(ref, displayTitle(ref) ?: it.name, it.streamUrl, it.userAgent, it.referrer)
        }
        VodKind.RECORDING -> null
    }

    /**
     * The name the player shows for [ref], or null when it no longer exists. A film uses TMDB's
     * title when matched. An episode uses its playlist name unless TMDB has a title for its number;
     * it then reads "S1 E3 · Episode title".
     */
    suspend fun displayTitle(ref: VodRef): String? = when (ref.kind) {
        VodKind.MOVIE -> dao.filmTitle(ref.playlistId, ref.key)
        VodKind.EPISODE -> dao.episodeTitle(ref.playlistId, ref.key)?.let { e ->
            val title = e.episodeTitle?.takeIf { it.isNotBlank() }
            if (title != null && e.season != null && e.episode != null) {
                "S${e.season} E${e.episode} · $title"
            } else {
                e.name
            }
        }
        VodKind.RECORDING -> null
    }

    /**
     * [ref]'s episode row (its season, number and name), or null for a film or a missing episode.
     * The name is TMDB's episode title where it has one.
     */
    suspend fun episodeInfo(ref: VodRef): EpisodeEntity? {
        if (ref.kind != VodKind.EPISODE) return null
        val row = dao.episodeRow(ref.playlistId, ref.key) ?: return null
        val title = dao.episodeTitle(ref.playlistId, ref.key)?.episodeTitle?.takeIf { it.isNotBlank() }
        return if (title != null) row.copy(name = title) else row
    }

    /** A show's visible episodes in show-page order, read once. */
    suspend fun episodesOnce(playlistId: Long, showKey: String): List<EpisodeEntity> =
        sortEpisodes(dao.episodesOnce(playlistId, showKey), { it.season }, { it.episode }, { it.position })

    /** The episode that follows [ref] on its show's page, or null (see [io.github.loopholetv.data.vod.nextEpisode]). */
    suspend fun nextEpisode(ref: VodRef): VodRef? {
        if (ref.kind != VodKind.EPISODE) return null
        val current = dao.episodeRow(ref.playlistId, ref.key) ?: return null
        val views = episodesOnce(ref.playlistId, current.showKey)
            .map { EpisodeView(it.key, it.season, it.episode, it.name, null) }
        val next = io.github.loopholetv.data.vod.nextEpisode(views, ref.key) ?: return null
        return VodRef(VodKind.EPISODE, ref.playlistId, next.key)
    }

    private fun recentSince(): Instant = clock().minus(Duration.ofDays(RECENT_DAYS.toLong()))

    private fun seasonsOf(episodes: List<EpisodeEntity>): List<Season> =
        sortEpisodes(episodes, { it.season }, { it.episode }, { it.position })
            .groupBy { it.season }
            .map { (number, items) -> Season(number, items) }
}
