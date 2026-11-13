package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface VodProgressDao {
    @Query("SELECT * FROM vod_progress WHERE kind = :kind AND playlistId = :playlistId AND `key` = :key")
    fun observe(kind: VodKind, playlistId: Long, key: String): Flow<VodProgressEntity?>

    /** Progress rows of every episode of one show, resolved in SQL so the episode count is not bound as variables. */
    @Query(
        "SELECT * FROM vod_progress WHERE kind = :kind AND playlistId = :playlistId AND `key` IN " +
            "(SELECT `key` FROM vod_episodes WHERE playlistId = :playlistId AND showKey = :showKey)",
    )
    fun observeForShow(kind: VodKind, playlistId: Long, showKey: String): Flow<List<VodProgressEntity>>

    @Query("SELECT * FROM vod_progress WHERE kind = :kind AND playlistId = :playlistId AND `key` = :key")
    suspend fun get(kind: VodKind, playlistId: Long, key: String): VodProgressEntity?

    @Upsert suspend fun upsert(row: VodProgressEntity)

    /** Moves one item's progress to a new key. A row already present under [to] wins and the old one stays. */
    @Query("UPDATE OR IGNORE vod_progress SET `key` = :to WHERE kind = :kind AND playlistId = :p AND `key` = :from")
    suspend fun rekey(kind: VodKind, p: Long, from: String, to: String)

    /** Drops rows last watched before [before] whose item is in neither the films nor the episodes of its playlist. */
    @Query(
        "DELETE FROM vod_progress WHERE lastWatchedAt < :before " +
            "AND NOT EXISTS (SELECT 1 FROM vod_films f WHERE f.playlistId = vod_progress.playlistId AND f.`key` = vod_progress.`key`) " +
            "AND NOT EXISTS (SELECT 1 FROM vod_episodes e WHERE e.playlistId = vod_progress.playlistId AND e.`key` = vod_progress.`key`)",
    )
    suspend fun deleteOrphans(before: Instant)

    @Query("DELETE FROM vod_progress WHERE playlistId = :p")
    suspend fun deleteForPlaylist(p: Long)
}
