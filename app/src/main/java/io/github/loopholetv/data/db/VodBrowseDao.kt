package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
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

/** The columns of a [ShowCard]; the playlist name appears only when an enabled playlist elsewhere has the same show name. */
internal const val SHOW_CARD =
    """s.playlistId AS playlistId, s.`key` AS `key`, s.name AS name, s.logo AS logo,
       CASE WHEN EXISTS (SELECT 1 FROM vod_shows o JOIN playlists op ON op.id = o.playlistId
                         WHERE o.name = s.name AND o.playlistId <> s.playlistId AND op.enabled = 1)
            THEN (SELECT np.name FROM playlists np WHERE np.id = s.playlistId) END AS playlistName"""

internal const val FILM_CARD = "f.playlistId AS playlistId, f.`key` AS `key`, f.name AS name, f.logo AS logo"

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
        """SELECT $FILM_CARD FROM vod_films f WHERE f.category = :category AND $FILM_VISIBLE
           ORDER BY f.playlistId, f.position""",
    )
    fun films(category: String): PagingSource<Int, FilmCard>

    @Query(
        """SELECT $FILM_CARD FROM vod_films f WHERE f.firstSeenAt > :since AND $FILM_VISIBLE
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
        """SELECT $SHOW_CARD FROM vod_shows s
           WHERE ((:category IS NULL AND s.category IS NULL) OR s.category = :category) AND $SHOW_VISIBLE
           ORDER BY s.name COLLATE NOCASE, s.playlistId, s.`key`""",
    )
    fun shows(category: String?): PagingSource<Int, ShowCard>

    @Query(
        """SELECT COUNT(*) FROM vod_shows s
           WHERE ((:category IS NULL AND s.category IS NULL) OR s.category = :category) AND $SHOW_VISIBLE""",
    )
    fun showCount(category: String?): Flow<Int>

    @Query(
        """SELECT $SHOW_CARD FROM vod_shows s WHERE s.firstSeenAt > :since AND $SHOW_VISIBLE
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

    @Query(
        """SELECT e.* FROM vod_episodes e WHERE e.playlistId = :playlistId AND e.showKey = :showKey
           AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = e.playlistId AND c.channelKey = e.`key` AND c.hidden = 1)""",
    )
    fun episodesOf(playlistId: Long, showKey: String): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT $FILM_CARD FROM vod_films f WHERE f.name LIKE :pattern ESCAPE '\' AND $FILM_VISIBLE
           ORDER BY f.name, f.playlistId, f.position LIMIT :limit""",
    )
    suspend fun searchFilms(pattern: String, limit: Int): List<FilmCard>

    @Query(
        """SELECT $SHOW_CARD FROM vod_shows s WHERE s.name LIKE :pattern ESCAPE '\' AND $SHOW_VISIBLE
           ORDER BY s.name, s.playlistId, s.`key` LIMIT :limit""",
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
}
