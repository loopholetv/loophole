package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Recently watched channels, newest first; channels absent after a refresh or hidden are skipped. */
@Dao
abstract class HistoryDao {
    /** Moves [ref] to the front (watched at [at]) and keeps only the newest [keep] entries. */
    @Transaction
    open suspend fun record(ref: ChannelRef, at: Instant, keep: Int) {
        require(keep > 0) { "keep must be > 0, got $keep" } // LIMIT -1 would keep everything
        upsert(HistoryEntity(ref.playlistId, ref.key, at))
        trim(keep)
    }

    /** Up to [limit] (> 0) present channels, newest first. */
    open fun recent(limit: Int): Flow<List<ChannelEntity>> {
        require(limit > 0) { "limit must be > 0, got $limit" }
        return recentQuery(limit)
    }

    @Query(
        """SELECT c.* FROM history h JOIN channels c ON c.playlistId = h.playlistId AND c.`key` = h.channelKey
           AND c.visible = 1
           ORDER BY h.watchedAt DESC LIMIT :limit""",
    )
    protected abstract fun recentQuery(limit: Int): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM history h JOIN channels c ON c.playlistId = h.playlistId AND c.`key` = h.channelKey
           AND c.visible = 1
           ORDER BY h.watchedAt DESC LIMIT 1""",
    )
    abstract suspend fun latest(): ChannelEntity?

    @Upsert
    protected abstract suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE rowid NOT IN (SELECT rowid FROM history ORDER BY watchedAt DESC LIMIT :keep)")
    protected abstract suspend fun trim(keep: Int)
}
