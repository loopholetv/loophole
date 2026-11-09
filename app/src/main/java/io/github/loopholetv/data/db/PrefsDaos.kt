package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Rows per insert in [EpgChannelDao.replaceForPlaylist] (keeps bound variables under SQLite's limit). */
internal const val EPG_CHANNEL_CHUNK = 500

@Dao
interface ChannelPrefsDao {
    @Query("SELECT * FROM channel_prefs WHERE playlistId = :playlistId AND channelKey = :key")
    suspend fun get(playlistId: Long, key: String): ChannelPrefsEntity?

    @Query("SELECT * FROM channel_prefs WHERE playlistId = :playlistId AND channelKey = :key")
    fun observe(playlistId: Long, key: String): Flow<ChannelPrefsEntity?>

    @Upsert
    suspend fun upsert(prefs: ChannelPrefsEntity)

    @Query("DELETE FROM channel_prefs WHERE playlistId = :playlistId AND channelKey = :key")
    suspend fun delete(playlistId: Long, key: String)

    @Query("SELECT * FROM channel_prefs WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<ChannelPrefsEntity>

    /** Rows of [playlistId] with a manual guide id. */
    @Query("SELECT * FROM channel_prefs WHERE playlistId = :playlistId AND epgOverride IS NOT NULL")
    suspend fun epgOverrides(playlistId: Long): List<ChannelPrefsEntity>

    /** Largest positive guide shift of any channel (null when none is positive); widens EPG pruning. */
    @Query("SELECT MAX(epgShiftMinutes) FROM channel_prefs WHERE epgShiftMinutes > 0")
    suspend fun maxPositiveShift(): Int?
}

@Dao
interface GroupPrefsDao {
    @Query("SELECT * FROM group_prefs WHERE playlistId = :playlistId AND groupTitle = :groupTitle")
    suspend fun get(playlistId: Long, groupTitle: String): GroupPrefsEntity?

    @Upsert
    suspend fun upsert(prefs: GroupPrefsEntity)

    @Query("DELETE FROM group_prefs WHERE playlistId = :playlistId AND groupTitle = :groupTitle")
    suspend fun delete(playlistId: Long, groupTitle: String)

    @Query("SELECT * FROM group_prefs WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<GroupPrefsEntity>
}

@Dao
abstract class EpgSourceDao {
    @Query("SELECT * FROM epg_sources WHERE playlistId = :playlistId ORDER BY position, id")
    abstract suspend fun forPlaylist(playlistId: Long): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE playlistId = :playlistId ORDER BY position, id")
    abstract fun observe(playlistId: Long): Flow<List<EpgSourceEntity>>

    /** Appends [url] after [playlistId]'s last source; returns the new row id. */
    @Transaction
    open suspend fun add(playlistId: Long, url: String): Long =
        insert(EpgSourceEntity(playlistId = playlistId, url = url, position = (maxPosition(playlistId) ?: -1) + 1))

    @Query("DELETE FROM epg_sources WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT MAX(position) FROM epg_sources WHERE playlistId = :playlistId")
    protected abstract suspend fun maxPosition(playlistId: Long): Int?

    @Insert
    protected abstract suspend fun insert(source: EpgSourceEntity): Long
}

@Dao
abstract class EpgChannelDao {
    /** Replaces [playlistId]'s guide channels with [rows] (all of which must belong to [playlistId]). */
    @Transaction
    open suspend fun replaceForPlaylist(playlistId: Long, rows: List<EpgChannelEntity>) {
        require(rows.all { it.playlistId == playlistId }) { "Rows must belong to playlist $playlistId" }
        deleteForPlaylist(playlistId)
        rows.chunked(EPG_CHANNEL_CHUNK).forEach { upsertAll(it) }
    }

    /** A repeated (playlistId, epgId) keeps the last display name. */
    @Upsert
    abstract suspend fun upsertAll(rows: List<EpgChannelEntity>)

    /** [pattern] comes from [searchPattern]; matches the name or the id, ordered by name ignoring case. */
    @Query(
        """SELECT * FROM epg_channels WHERE playlistId = :playlistId
           AND (displayName LIKE :pattern ESCAPE '\' OR epgId LIKE :pattern ESCAPE '\')
           ORDER BY displayName COLLATE NOCASE, epgId LIMIT :limit""",
    )
    abstract suspend fun search(playlistId: Long, pattern: String, limit: Int): List<EpgChannelEntity>

    @Query("SELECT COUNT(*) FROM epg_channels WHERE playlistId = :playlistId")
    abstract suspend fun count(playlistId: Long): Int

    @Query("DELETE FROM epg_channels WHERE playlistId = :playlistId")
    protected abstract suspend fun deleteForPlaylist(playlistId: Long)
}
