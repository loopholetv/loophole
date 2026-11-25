package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.loopholetv.data.tmdb.Genre
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodSort
import kotlinx.coroutines.flow.Flow

/**
 * [visible] ([FILM_VISIBLE] or [SHOW_VISIBLE], written for the alias [from]) for the row aliased
 * [to]. Only `from.` at the start of a name is replaced, so other aliases are left alone.
 */
internal fun visibleAs(visible: String, from: String, to: String): String =
    visible.replace(Regex("(?<![A-Za-z0-9_])" + Regex.escape(from) + "\\."), Regex.escapeReplacement("$to."))

/** What the filter SQL needs to know about films or shows. */
private class FilterTable(
    val kind: TitleKind,
    val table: String,
    val alias: String,
    val card: String,
    val join: String,
    val visible: String,
    /** The latest watch progress of the row aliased by the argument (millis), or -1 when none. */
    val progress: (String) -> String,
)

private val FILMS = FilterTable(TitleKind.FILM, "vod_films", "f", FILM_CARD, FILM_MATCH_JOIN, FILM_VISIBLE) { x ->
    """COALESCE((SELECT MAX(pr.lastWatchedAt) FROM vod_progress pr
       WHERE pr.kind = 'MOVIE' AND pr.playlistId = $x.playlistId AND pr.`key` = $x.`key`), -1)"""
}

/** A show's progress is that of its latest watched episode. */
private val SHOWS = FilterTable(TitleKind.SHOW, "vod_shows", "s", SHOW_CARD, SHOW_MATCH_JOIN, SHOW_VISIBLE) { x ->
    """COALESCE((SELECT MAX(pr.lastWatchedAt) FROM vod_episodes pe
       JOIN vod_progress pr ON pr.kind = 'EPISODE' AND pr.playlistId = pe.playlistId AND pr.`key` = pe.`key`
       WHERE pe.playlistId = $x.playlistId AND pe.showKey = $x.`key`), -1)"""
}

/**
 * True when no better visible copy of the same TMDB title exists, so each title shows once. A copy
 * is better when it has later watch progress, else when it comes first by playlist (playlists have
 * no order of their own, so by id), position, then key. The key makes the order strict, so exactly
 * one copy is kept. Only called for a row with details: unmatched rows are never merged.
 */
private fun FilterTable.noBetterCopy(): String {
    val x = alias
    val po = progress("o")
    val px = progress(x)
    return """NOT EXISTS (SELECT 1 FROM vod_matches mo
       JOIN $table o ON o.playlistId = mo.playlistId AND o.`key` = mo.`key`
       WHERE mo.tmdbKind = m.tmdbKind AND mo.tmdbId = m.tmdbId AND mo.kind = '${kind.name}' AND mo.state = 'MATCHED'
       AND ${visibleAs(visible, x, "o")}
       AND ($po > $px OR ($po = $px AND (o.playlistId < $x.playlistId OR (o.playlistId = $x.playlistId
            AND (o.position < $x.position OR (o.position = $x.position AND o.`key` < $x.`key`)))))))"""
}

/** The conditions [f] adds on the TMDB title `t`, each one ANDed. */
private fun FilterTable.filterClauses(f: VodFilter): List<String> = buildList {
    if (f.genres.isNotEmpty()) {
        add(
            "EXISTS (SELECT 1 FROM tmdb_title_genres l WHERE l.tmdbKind = t.tmdbKind AND l.tmdbId = t.tmdbId " +
                "AND l.genreId IN (${f.genres.sorted().joinToString(", ")}))",
        )
    }
    if (f.decades.isNotEmpty()) {
        add(
            f.decades.sorted().joinToString(" OR ", "(", ")") { d ->
                if (d == VodFilter.BEFORE_1970) "t.year < 1970" else "t.year BETWEEN $d AND ${d + 9}"
            },
        )
    }
    f.rating.value?.let { add("t.rating >= $it") }
    // Running time is offered for films only.
    if (kind == TitleKind.FILM) {
        when (f.runtime) {
            RuntimeBand.ANY -> Unit
            RuntimeBand.UNDER_90 -> add("t.runtimeMinutes < 90")
            RuntimeBand.FROM_90_TO_120 -> add("t.runtimeMinutes BETWEEN 90 AND 120")
            RuntimeBand.OVER_120 -> add("t.runtimeMinutes > 120")
        }
    }
}

/** The sort's own keys (unknown values last), then the display title, playlist and key to break ties. */
private fun FilterTable.orderBy(f: VodFilter): String {
    val x = alias
    val title = "COALESCE(t.title, $x.name) COLLATE NOCASE"
    val keys = when (f.sort) {
        VodSort.RECENT -> listOf("$x.firstSeenAt DESC")
        VodSort.RATING -> listOf("t.rating IS NULL", "t.rating DESC")
        VodSort.YEAR -> listOf("t.year IS NULL", "t.year DESC")
        VodSort.TITLE -> emptyList()
        VodSort.RUNTIME -> listOf("t.runtimeMinutes IS NULL", "t.runtimeMinutes")
    }
    // With a sort only, titles without details are included and go after the ones with.
    val matchedFirst = if (f.hasFilter) emptyList() else listOf("t.tmdbId IS NULL")
    return (matchedFirst + keys + listOf(title, "$x.playlistId", "$x.`key`")).joinToString(", ")
}

/**
 * The Sort & filter grid's query for [kind]: visible titles as cards (or their number, with
 * [countOnly]), one per TMDB title. With a filter only titles with details are included; with a
 * sort only, titles without details follow the others.
 */
fun filterQuery(kind: TitleKind, f: VodFilter, countOnly: Boolean = false): SimpleSQLiteQuery {
    val t = if (kind == TitleKind.FILM) FILMS else SHOWS
    val where = buildList {
        add(t.visible)
        if (f.hasFilter) {
            add("t.tmdbId IS NOT NULL")
            addAll(t.filterClauses(f))
            add(t.noBetterCopy())
        } else {
            add("(t.tmdbId IS NULL OR ${t.noBetterCopy()})")
        }
    }.joinToString("\n AND ")
    val from = "FROM ${t.table} ${t.alias} ${t.join} WHERE $where"
    val sql = if (countOnly) "SELECT COUNT(*) $from" else "SELECT ${t.card} $from ORDER BY ${t.orderBy(f)}"
    return SimpleSQLiteQuery(sql)
}

/** The tables a grid of films reads (see [VodFilterDao.count], which observes the same ones). */
internal val FILM_GRID_TABLES = arrayOf(
    "vod_films", "vod_matches", "tmdb_titles", "tmdb_title_genres", "vod_progress", "playlists", "group_prefs", "channel_prefs",
    "lock_state",
)

/** Episodes are observed for their progress, which decides which copy of a show is kept. */
internal val SHOW_GRID_TABLES = arrayOf(
    "vod_shows", "vod_episodes", "vod_matches", "tmdb_titles", "tmdb_title_genres", "vod_progress", "playlists",
    "group_prefs", "channel_prefs", "show_prefs", "lock_state",
)

/** The Sort & filter grid: films and shows read with [filterQuery], and the genres to offer. */
@Dao
interface VodFilterDao {
    /** One page of [filterQuery] (it ends in LIMIT and OFFSET); see [GridPagingSource]. */
    @RawQuery
    suspend fun films(q: SupportSQLiteQuery): List<FilmCard>

    @RawQuery
    suspend fun shows(q: SupportSQLiteQuery): List<ShowCard>

    @RawQuery(
        observedEntities = [
            FilmEntity::class, ShowEntity::class, EpisodeEntity::class, VodMatchEntity::class, TmdbTitleEntity::class,
            TmdbTitleGenreEntity::class, VodProgressEntity::class, PlaylistEntity::class, GroupPrefsEntity::class,
            ChannelPrefsEntity::class, ShowPrefsEntity::class, LockStateEntity::class,
        ],
    )
    fun count(q: SupportSQLiteQuery): Flow<Int>

    @Query(
        """SELECT g.genreId AS id, g.name AS name FROM tmdb_genres g
           WHERE g.tmdbKind = 'MOVIE' AND EXISTS (SELECT 1 FROM tmdb_title_genres l
               JOIN vod_matches m ON m.tmdbKind = l.tmdbKind AND m.tmdbId = l.tmdbId AND m.kind = 'FILM' AND m.state = 'MATCHED'
               JOIN vod_films f ON f.playlistId = m.playlistId AND f.`key` = m.`key`
               WHERE l.tmdbKind = g.tmdbKind AND l.genreId = g.genreId AND $FILM_VISIBLE)
           ORDER BY g.name COLLATE NOCASE""",
    )
    fun filmGenresPresent(): Flow<List<Genre>>

    @Query(
        """SELECT g.genreId AS id, g.name AS name FROM tmdb_genres g
           WHERE g.tmdbKind = 'TV' AND EXISTS (SELECT 1 FROM tmdb_title_genres l
               JOIN vod_matches m ON m.tmdbKind = l.tmdbKind AND m.tmdbId = l.tmdbId AND m.kind = 'SHOW' AND m.state = 'MATCHED'
               JOIN vod_shows s ON s.playlistId = m.playlistId AND s.`key` = m.`key`
               WHERE l.tmdbKind = g.tmdbKind AND l.genreId = g.genreId AND $SHOW_VISIBLE)
           ORDER BY g.name COLLATE NOCASE""",
    )
    fun showGenresPresent(): Flow<List<Genre>>

    /** Whether any film or show of [kind] has details (a MATCHED row), whatever its visibility. */
    @Query("SELECT EXISTS (SELECT 1 FROM vod_matches WHERE kind = :kind AND state = 'MATCHED')")
    fun hasDetails(kind: TitleKind): Flow<Boolean>

    /** Genres of matched, visible titles of [kind], by name. */
    fun genresPresent(kind: TitleKind): Flow<List<Genre>> =
        if (kind == TitleKind.FILM) filmGenresPresent() else showGenresPresent()
}

/** The Sort & filter grid of films for [f], paged without a count. */
internal fun AppDatabase.filmGrid(f: VodFilter): GridPagingSource<FilmCard> {
    val dao = vodFilterDao()
    return GridPagingSource(this, FILM_GRID_TABLES, filterQuery(TitleKind.FILM, f)) { dao.films(it) }
}

/** The Sort & filter grid of shows for [f], paged without a count. */
internal fun AppDatabase.showGrid(f: VodFilter): GridPagingSource<ShowCard> {
    val dao = vodFilterDao()
    return GridPagingSource(this, SHOW_GRID_TABLES, filterQuery(TitleKind.SHOW, f)) { dao.shows(it) }
}
