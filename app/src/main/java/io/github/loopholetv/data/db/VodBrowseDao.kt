package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import io.github.loopholetv.data.tmdb.TMDB_POSTER_BASE
import io.github.loopholetv.data.vod.CategoryRow
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.ShowCard
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A film and the name of its playlist. */
data class FilmWithPlaylist(@Embedded val film: FilmEntity, val playlistName: String)

/** A hidden group title of one playlist that holds films or shows. */
data class HiddenGroupRow(val playlistId: Long, val groupTitle: String)

/** The playlist is enabled, and neither the film's category nor the film itself is hidden. */
internal const val FILM_VISIBLE =
    """EXISTS (SELECT 1 FROM playlists p WHERE p.id = f.playlistId AND p.enabled = 1)
       AND NOT EXISTS (SELECT 1 FROM group_prefs g WHERE g.playlistId = f.playlistId AND g.groupTitle = f.category AND g.hidden = 1)
       AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = f.playlistId AND c.channelKey = f.`key` AND c.hidden = 1)"""

/**
 * The playlist is enabled and the show's group is not hidden. Hidden single episodes (a channel
 * hidden before it became an episode) are left out of the show's episode list instead.
 */
internal const val SHOW_VISIBLE =
    """EXISTS (SELECT 1 FROM playlists p WHERE p.id = s.playlistId AND p.enabled = 1)
       AND NOT EXISTS (SELECT 1 FROM group_prefs g WHERE g.playlistId = s.playlistId AND g.groupTitle = s.groupTitle AND g.hidden = 1)"""

/**
 * Joins a film's match (`m`) and its TMDB title (`t`). Only a MATCHED row joins, so an unmatched,
 * rejected or not-found film keeps the playlist's name and poster.
 */
internal const val FILM_MATCH_JOIN =
    """LEFT JOIN vod_matches m ON m.playlistId = f.playlistId AND m.kind = 'FILM' AND m.`key` = f.`key` AND m.state = 'MATCHED'
       LEFT JOIN tmdb_titles t ON t.tmdbKind = m.tmdbKind AND t.tmdbId = m.tmdbId"""

/** [FILM_MATCH_JOIN] for a show (`s`). */
internal const val SHOW_MATCH_JOIN =
    """LEFT JOIN vod_matches m ON m.playlistId = s.playlistId AND m.kind = 'SHOW' AND m.`key` = s.`key` AND m.state = 'MATCHED'
       LEFT JOIN tmdb_titles t ON t.tmdbKind = m.tmdbKind AND t.tmdbId = m.tmdbId"""

/**
 * The columns of a [ShowCard], read with [SHOW_MATCH_JOIN]: TMDB's title and poster when matched.
 * The playlist name appears only when a show of another enabled playlist is shown under the same
 * name: an unmatched one whose playlist name is it, or a matched one whose TMDB title is it. The
 * two checks are separate so each reaches its rows through an index; CROSS JOIN keeps SQLite
 * starting the second from the title index rather than from every show match.
 */
internal const val SHOW_CARD =
    """s.playlistId AS playlistId, s.`key` AS `key`, COALESCE(t.title, s.name) AS name,
       COALESCE('$TMDB_POSTER_BASE' || t.posterPath, s.logo) AS logo,
       CASE WHEN EXISTS (SELECT 1 FROM vod_shows o JOIN playlists op ON op.id = o.playlistId
                         LEFT JOIN vod_matches m2 ON m2.playlistId = o.playlistId AND m2.kind = 'SHOW' AND m2.`key` = o.`key` AND m2.state = 'MATCHED'
                         LEFT JOIN tmdb_titles t2 ON t2.tmdbKind = m2.tmdbKind AND t2.tmdbId = m2.tmdbId
                         WHERE o.name = COALESCE(t.title, s.name) AND t2.tmdbId IS NULL
                         AND o.playlistId <> s.playlistId AND op.enabled = 1)
              OR EXISTS (SELECT 1 FROM tmdb_titles t2
                         CROSS JOIN vod_matches m2 ON m2.tmdbKind = t2.tmdbKind AND m2.tmdbId = t2.tmdbId AND m2.kind = 'SHOW' AND m2.state = 'MATCHED'
                         JOIN vod_shows o ON o.playlistId = m2.playlistId AND o.`key` = m2.`key`
                         JOIN playlists op ON op.id = o.playlistId
                         WHERE t2.tmdbKind = 'TV' AND t2.title = COALESCE(t.title, s.name)
                         AND o.playlistId <> s.playlistId AND op.enabled = 1)
            THEN (SELECT np.name FROM playlists np WHERE np.id = s.playlistId) END AS playlistName,
       CASE WHEN t.tmdbId IS NOT NULL THEN m.badge END AS badge"""

/** The columns of a [FilmCard], read with [FILM_MATCH_JOIN]: TMDB's title, poster and the quality badge when matched. */
internal const val FILM_CARD =
    """f.playlistId AS playlistId, f.`key` AS `key`, COALESCE(t.title, f.name) AS name,
       COALESCE('$TMDB_POSTER_BASE' || t.posterPath, f.logo) AS logo,
       CASE WHEN t.tmdbId IS NOT NULL THEN m.badge END AS badge"""

/** What the player shows for an episode: its playlist name, numbers, and TMDB's episode title when known. */
data class EpisodeTitleRow(val name: String, val season: Int?, val episode: Int?, val episodeTitle: String?)

/** Browse and search reads over films and shows; the browse and search queries apply the visibility rules above. */
@Dao
interface VodBrowseDao {
    /** Films per playlist, hidden ones included (playlists without films are absent). */
    @Query("SELECT playlistId, COUNT(*) AS count FROM vod_films GROUP BY playlistId")
    fun filmCountsByPlaylist(): Flow<List<PlaylistChannelCount>>

    /** Shows per playlist, hidden ones included (playlists without shows are absent). */
    @Query("SELECT playlistId, COUNT(*) AS count FROM vod_shows GROUP BY playlistId")
    fun showCountsByPlaylist(): Flow<List<PlaylistChannelCount>>

    /**
     * Whether Movies has anything to offer: a film of an enabled playlist, counting films whose
     * category is hidden (the screen then offers to show the hidden categories again). Films
     * hidden one by one do not count.
     */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM vod_films f
           WHERE EXISTS (SELECT 1 FROM playlists p WHERE p.id = f.playlistId AND p.enabled = 1)
           AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = f.playlistId AND c.channelKey = f.`key` AND c.hidden = 1))""",
    )
    fun hasFilms(): Flow<Boolean>

    /** Whether Series has anything to offer: a show of an enabled playlist, hidden categories included. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM vod_shows s
           WHERE EXISTS (SELECT 1 FROM playlists p WHERE p.id = s.playlistId AND p.enabled = 1))""",
    )
    fun hasShows(): Flow<Boolean>

    @Query(
        """SELECT f.category AS title, COUNT(*) AS count FROM vod_films f WHERE $FILM_VISIBLE
           GROUP BY f.category ORDER BY MIN(f.playlistId), MIN(f.position)""",
    )
    fun filmCategories(): Flow<List<CategoryRow>>

    @Query(
        """SELECT $FILM_CARD FROM vod_films f $FILM_MATCH_JOIN WHERE f.category = :category AND $FILM_VISIBLE
           ORDER BY f.playlistId, f.position""",
    )
    fun films(category: String): PagingSource<Int, FilmCard>

    @Query(
        """SELECT $FILM_CARD FROM vod_films f $FILM_MATCH_JOIN WHERE f.firstSeenAt > :since AND $FILM_VISIBLE
           ORDER BY f.firstSeenAt DESC, f.playlistId, f.position LIMIT :limit""",
    )
    fun recentFilms(since: Instant, limit: Int): Flow<List<FilmCard>>

    @Query(
        """SELECT s.category AS title, COUNT(*) AS count FROM vod_shows s
           WHERE s.category IS NOT NULL AND $SHOW_VISIBLE
           GROUP BY s.category ORDER BY MIN(s.playlistId), MIN(s.position)""",
    )
    fun showCategories(): Flow<List<CategoryRow>>

    @Query(
        """SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN
           WHERE ((:category IS NULL AND s.category IS NULL) OR s.category = :category) AND $SHOW_VISIBLE
           ORDER BY COALESCE(t.title, s.name) COLLATE NOCASE, s.playlistId, s.`key`""",
    )
    fun shows(category: String?): PagingSource<Int, ShowCard>

    @Query(
        """SELECT COUNT(*) FROM vod_shows s
           WHERE ((:category IS NULL AND s.category IS NULL) OR s.category = :category) AND $SHOW_VISIBLE""",
    )
    fun showCount(category: String?): Flow<Int>

    @Query(
        """SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN WHERE s.firstSeenAt > :since AND $SHOW_VISIBLE
           ORDER BY s.firstSeenAt DESC, s.playlistId, s.position LIMIT :limit""",
    )
    fun recentShows(since: Instant, limit: Int): Flow<List<ShowCard>>

    @Query(
        """SELECT f.*, (SELECT name FROM playlists WHERE id = f.playlistId) AS playlistName FROM vod_films f
           WHERE f.playlistId = :playlistId AND f.`key` = :key AND $FILM_VISIBLE""",
    )
    fun film(playlistId: Long, key: String): Flow<FilmWithPlaylist?>

    @Query("SELECT s.* FROM vod_shows s WHERE s.playlistId = :playlistId AND s.`key` = :key AND $SHOW_VISIBLE")
    fun show(playlistId: Long, key: String): Flow<ShowEntity?>

    /** A visible show as its card shows it. */
    @Query("SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN WHERE s.playlistId = :playlistId AND s.`key` = :key AND $SHOW_VISIBLE")
    suspend fun showCard(playlistId: Long, key: String): ShowCard?

    @Query(
        """SELECT e.* FROM vod_episodes e WHERE e.playlistId = :playlistId AND e.showKey = :showKey
           AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = e.playlistId AND c.channelKey = e.`key` AND c.hidden = 1)""",
    )
    fun episodesOf(playlistId: Long, showKey: String): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.* FROM vod_episodes e WHERE e.playlistId = :playlistId AND e.showKey = :showKey
           AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = e.playlistId AND c.channelKey = e.`key` AND c.hidden = 1)""",
    )
    suspend fun episodesOnce(playlistId: Long, showKey: String): List<EpisodeEntity>

    @Query(
        """SELECT $FILM_CARD FROM vod_films f $FILM_MATCH_JOIN
           WHERE (f.name LIKE :pattern ESCAPE '\' OR t.title LIKE :pattern ESCAPE '\') AND $FILM_VISIBLE
           ORDER BY COALESCE(t.title, f.name), f.playlistId, f.position LIMIT :limit""",
    )
    suspend fun searchFilms(pattern: String, limit: Int): List<FilmCard>

    @Query(
        """SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN
           WHERE (s.name LIKE :pattern ESCAPE '\' OR t.title LIKE :pattern ESCAPE '\') AND $SHOW_VISIBLE
           ORDER BY COALESCE(t.title, s.name), s.playlistId, s.`key` LIMIT :limit""",
    )
    suspend fun searchShows(pattern: String, limit: Int): List<ShowCard>

    @Query(
        """SELECT g.playlistId AS playlistId, g.groupTitle AS groupTitle FROM group_prefs g
           JOIN playlists p ON p.id = g.playlistId AND p.enabled = 1
           WHERE g.hidden = 1 AND (
               EXISTS (SELECT 1 FROM vod_films f WHERE f.playlistId = g.playlistId AND f.category = g.groupTitle)
               OR EXISTS (SELECT 1 FROM vod_shows s WHERE s.playlistId = g.playlistId AND s.groupTitle = g.groupTitle))
           ORDER BY g.playlistId, g.groupTitle""",
    )
    fun hiddenGroups(): Flow<List<HiddenGroupRow>>

    @Query(
        """SELECT f.playlistId FROM vod_films f WHERE f.category = :title
           UNION SELECT s.playlistId FROM vod_shows s WHERE s.groupTitle = :title
           ORDER BY 1""",
    )
    suspend fun playlistsWithCategory(title: String): List<Long>

    @Query("SELECT * FROM vod_films WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun filmRow(playlistId: Long, key: String): FilmEntity?

    @Query("SELECT * FROM vod_episodes WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun episodeRow(playlistId: Long, key: String): EpisodeEntity?

    /** A film's name as shown: TMDB's title when matched. */
    @Query("SELECT COALESCE(t.title, f.name) FROM vod_films f $FILM_MATCH_JOIN WHERE f.playlistId = :playlistId AND f.`key` = :key")
    suspend fun filmTitle(playlistId: Long, key: String): String?

    /** An episode with, where its show is matched and the season lookup found its number, TMDB's episode title. */
    @Query(
        """SELECT e.name AS name, e.season AS season, e.episode AS episode, te.title AS episodeTitle
           FROM vod_episodes e
           LEFT JOIN vod_matches m ON m.playlistId = e.playlistId AND m.kind = 'SHOW' AND m.`key` = e.showKey AND m.state = 'MATCHED'
           LEFT JOIN tmdb_titles t ON t.tmdbKind = m.tmdbKind AND t.tmdbId = m.tmdbId
           LEFT JOIN tmdb_episodes te ON t.tmdbKind = 'TV' AND te.tvId = t.tmdbId AND te.season = e.season AND te.episode = e.episode
           WHERE e.playlistId = :playlistId AND e.`key` = :key""",
    )
    suspend fun episodeTitle(playlistId: Long, key: String): EpisodeTitleRow?
}
