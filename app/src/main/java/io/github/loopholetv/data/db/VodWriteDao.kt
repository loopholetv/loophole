package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import io.github.loopholetv.data.repo.KeyNameGroup
import java.time.Instant

data class KeyFirstSeen(val key: String, val firstSeenAt: Instant)

data class KeyShowKey(val key: String, val showKey: String)

/** What the show grouping reads from an episode row. */
data class EpisodeForShows(
    val key: String,
    val groupTitle: String,
    val name: String,
    val showKey: String,
    val logo: String?,
    val position: Int,
    val firstSeenAt: Instant,
)

@Dao
interface VodWriteDao {
    @Query("SELECT `key`, firstSeenAt FROM vod_films WHERE playlistId = :p")
    suspend fun filmKeys(p: Long): List<KeyFirstSeen>

    @Query("SELECT `key`, firstSeenAt FROM vod_episodes WHERE playlistId = :p")
    suspend fun episodeKeys(p: Long): List<KeyFirstSeen>

    @Query("SELECT `key`, showKey FROM vod_episodes WHERE playlistId = :p")
    suspend fun episodeShowKeys(p: Long): List<KeyShowKey>

    @Upsert suspend fun upsertFilms(rows: List<FilmEntity>)

    @Upsert suspend fun upsertEpisodes(rows: List<EpisodeEntity>)

    @Query("DELETE FROM vod_films WHERE playlistId = :p AND `key` IN (:keys)")
    suspend fun deleteFilms(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_episodes WHERE playlistId = :p AND `key` IN (:keys)")
    suspend fun deleteEpisodes(p: Long, keys: List<String>)

    @Query("DELETE FROM vod_shows WHERE playlistId = :p")
    suspend fun deleteShows(p: Long)

    @Insert suspend fun insertShows(rows: List<ShowEntity>)

    @Query("SELECT `key`, name, category AS `group` FROM vod_films WHERE playlistId = :p")
    suspend fun filmNameGroups(p: Long): List<KeyNameGroup>

    @Query("SELECT `key`, name, groupTitle AS `group` FROM vod_episodes WHERE playlistId = :p")
    suspend fun episodeNameGroups(p: Long): List<KeyNameGroup>

    @Query("SELECT `key`, groupTitle, name, showKey, logo, position, firstSeenAt FROM vod_episodes WHERE playlistId = :p")
    suspend fun episodesForShows(p: Long): List<EpisodeForShows>

    @Query("UPDATE vod_episodes SET showKey = :showKey WHERE playlistId = :p AND `key` = :key")
    suspend fun setShowKey(p: Long, key: String, showKey: String)
}
