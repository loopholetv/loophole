package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.loopholetv.data.repo.KeyNameGroup
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A stored Xtream film's key and when it was first listed. */
data class KeySeen(val key: String, val firstSeenAt: Instant)

/** What the Xtream VOD writer keeps from a stored show. */
data class ShowState(val key: String, val firstSeenAt: Instant, val lastModified: String?, val episodesFetchedAt: Instant?)

/**
 * Xtream films (`xm:` keys), shows ([ShowSource.XTREAM]), episodes (`xe:` keys) and provider details.
 * Removals keep watch progress and My list, as other removed titles do.
 */
@Dao
abstract class XtreamVodDao {
    @Upsert abstract suspend fun upsertProviderDetails(row: ProviderDetailsEntity)

    @Query("SELECT * FROM vod_provider_details WHERE playlistId = :p AND `key` = :key")
    abstract suspend fun providerDetailsNow(p: Long, key: String): ProviderDetailsEntity?

    @Query("SELECT * FROM vod_provider_details WHERE playlistId = :p AND `key` = :key")
    abstract fun providerDetails(p: Long, key: String): Flow<ProviderDetailsEntity?>

    @Query("SELECT `key`, firstSeenAt FROM vod_films WHERE playlistId = :p AND `key` IN (:keys)")
    abstract suspend fun filmsSeen(p: Long, keys: List<String>): List<KeySeen>

    @Query(
        "SELECT `key`, firstSeenAt, lastModified, episodesFetchedAt FROM vod_shows " +
            "WHERE playlistId = :p AND source = 'XTREAM' AND `key` IN (:keys)",
    )
    abstract suspend fun showStates(p: Long, keys: List<String>): List<ShowState>

    @Query("SELECT COUNT(*) FROM vod_films WHERE playlistId = :p AND `key` LIKE 'xm:%'")
    abstract suspend fun xtreamFilmCount(p: Long): Int

    @Query("SELECT COUNT(*) FROM vod_shows WHERE playlistId = :p AND source = 'XTREAM'")
    abstract suspend fun xtreamShowCount(p: Long): Int

    @Query("SELECT `key` FROM vod_films WHERE playlistId = :p AND `key` LIKE 'xm:%'")
    abstract suspend fun xtreamFilmKeys(p: Long): List<String>

    @Query("SELECT `key` FROM vod_shows WHERE playlistId = :p AND source = 'XTREAM'")
    abstract suspend fun xtreamShowKeys(p: Long): List<String>

    /** Xtream films with their name and category, for carrying edits to a title the provider gave a new id. */
    @Query("SELECT `key`, name, category AS `group` FROM vod_films WHERE playlistId = :p AND `key` LIKE 'xm:%'")
    abstract suspend fun xtreamFilmNameGroups(p: Long): List<KeyNameGroup>

    /** Xtream shows with their name and group, as [xtreamFilmNameGroups]. */
    @Query("SELECT `key`, name, groupTitle AS `group` FROM vod_shows WHERE playlistId = :p AND source = 'XTREAM'")
    abstract suspend fun xtreamShowNameGroups(p: Long): List<KeyNameGroup>

    @Upsert abstract suspend fun upsertFilms(rows: List<FilmEntity>)

    @Upsert abstract suspend fun upsertShows(rows: List<ShowEntity>)

    @Query("SELECT * FROM vod_shows WHERE playlistId = :p AND `key` = :key AND source = 'XTREAM'")
    abstract suspend fun showOnce(p: Long, key: String): ShowEntity?

    @Query("SELECT `key`, firstSeenAt FROM vod_episodes WHERE playlistId = :p AND showKey = :showKey AND `key` LIKE 'xe:%'")
    abstract suspend fun episodeKeysOf(p: Long, showKey: String): List<KeySeen>

    /**
     * Stores a fetch of [showKey]'s episodes made at [fetchedAt]: its stored episodes missing from
     * [rows] are removed, [rows] are upserted, and the show is marked fetched; [bumpShow] also makes it
     * "recently added" again. An empty [rows] removes nothing (a provider glitch must not wipe a show).
     */
    @Transaction
    open suspend fun replaceEpisodes(p: Long, showKey: String, rows: List<EpisodeEntity>, fetchedAt: Instant, bumpShow: Boolean) {
        if (rows.isNotEmpty()) {
            val keep = rows.mapTo(HashSet()) { it.key }
            episodeKeysOf(p, showKey).map { it.key }.filterNot { it in keep }
                .chunked(KEY_CHUNK).forEach { deleteXtreamEpisodesByKey(p, it) }
            upsertEpisodes(rows)
        }
        setEpisodesFetchedAt(p, showKey, fetchedAt)
        if (bumpShow) setShowFirstSeen(p, showKey, fetchedAt)
    }

    @Upsert protected abstract suspend fun upsertEpisodes(rows: List<EpisodeEntity>)

    @Query("DELETE FROM vod_episodes WHERE playlistId = :p AND `key` LIKE 'xe:%' AND `key` IN (:keys)")
    protected abstract suspend fun deleteXtreamEpisodesByKey(p: Long, keys: List<String>)

    @Query("UPDATE vod_shows SET episodesFetchedAt = :at WHERE playlistId = :p AND `key` = :key AND source = 'XTREAM'")
    protected abstract suspend fun setEpisodesFetchedAt(p: Long, key: String, at: Instant)

    @Query("UPDATE vod_shows SET firstSeenAt = :at WHERE playlistId = :p AND `key` = :key AND source = 'XTREAM'")
    protected abstract suspend fun setShowFirstSeen(p: Long, key: String, at: Instant)

    /** Removes the Xtream films [keys] and their provider details. */
    @Transaction
    open suspend fun deleteFilms(p: Long, keys: List<String>) {
        deleteXtreamFilmsByKey(p, keys)
        deleteDetailsByKey(p, keys)
    }

    /** Removes the Xtream shows [keys] and their episodes. */
    @Transaction
    open suspend fun deleteShows(p: Long, keys: List<String>) {
        deleteXtreamShowsByKey(p, keys)
        deleteEpisodesOfShows(p, keys)
    }

    /** Removes the playlist's Xtream films, shows, episodes and provider details. */
    @Transaction
    open suspend fun removeAll(p: Long) {
        deleteXtreamFilms(p)
        deleteXtreamShows(p)
        deleteXtreamEpisodes(p)
        deleteAllDetails(p)
    }

    /** Removes the playlist's M3U films, episodes and shows (an Xtream playlist imported them before). */
    @Transaction
    open suspend fun removeM3uVod(p: Long) {
        deleteM3uFilms(p)
        deleteM3uEpisodes(p)
        deleteM3uShows(p)
    }

    /** Removes provider details whose film is gone. */
    @Transaction
    open suspend fun deleteDetailsWithoutFilm(p: Long) = deleteOrphanDetails(p)

    @Query("DELETE FROM vod_films WHERE playlistId = :p AND `key` LIKE 'xm:%'")
    protected abstract suspend fun deleteXtreamFilms(p: Long)

    @Query("DELETE FROM vod_shows WHERE playlistId = :p AND source = 'XTREAM'")
    protected abstract suspend fun deleteXtreamShows(p: Long)

    @Query("DELETE FROM vod_episodes WHERE playlistId = :p AND `key` LIKE 'xe:%'")
    protected abstract suspend fun deleteXtreamEpisodes(p: Long)

    @Query("DELETE FROM vod_provider_details WHERE playlistId = :p")
    protected abstract suspend fun deleteAllDetails(p: Long)

    @Query("DELETE FROM vod_films WHERE playlistId = :p AND `key` LIKE 'xm:%' AND `key` IN (:keys)")
    protected abstract suspend fun deleteXtreamFilmsByKey(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_provider_details WHERE playlistId = :p AND `key` IN (:keys)")
    protected abstract suspend fun deleteDetailsByKey(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_shows WHERE playlistId = :p AND source = 'XTREAM' AND `key` IN (:keys)")
    protected abstract suspend fun deleteXtreamShowsByKey(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_episodes WHERE playlistId = :p AND `key` LIKE 'xe:%' AND showKey IN (:keys)")
    protected abstract suspend fun deleteEpisodesOfShows(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_films WHERE playlistId = :p AND `key` NOT LIKE 'xm:%'")
    protected abstract suspend fun deleteM3uFilms(p: Long)

    @Query("DELETE FROM vod_episodes WHERE playlistId = :p AND `key` NOT LIKE 'xe:%'")
    protected abstract suspend fun deleteM3uEpisodes(p: Long)

    @Query("DELETE FROM vod_shows WHERE playlistId = :p AND source = 'M3U'")
    protected abstract suspend fun deleteM3uShows(p: Long)

    @Query(
        "DELETE FROM vod_provider_details WHERE playlistId = :p AND NOT EXISTS " +
            "(SELECT 1 FROM vod_films f WHERE f.playlistId = vod_provider_details.playlistId AND f.`key` = vod_provider_details.`key`)",
    )
    protected abstract suspend fun deleteOrphanDetails(p: Long)
}

/** Keys per `IN (...)` list, well under SQLite's 999-variable limit on old devices. */
private const val KEY_CHUNK = 500
