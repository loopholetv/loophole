package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Favourites joined to the visible channels currently present (skipped while their key is absent,
 * or while the channel, its group or its playlist is hidden).
 */
private const val VISIBLE =
    "favourites f JOIN channels c ON c.playlistId = f.playlistId AND c.`key` = f.channelKey AND c.visible = 1"

/**
 * Favourite channels in the user's order (`position`, then channel id). Every read goes through the
 * join, so a favourite whose channel vanished on refresh is skipped until the key returns.
 */
@Dao
abstract class FavouriteDao {
    /** Every stored favourite in order, including hidden ones. */
    @Query("SELECT playlistId, channelKey AS `key` FROM favourites ORDER BY position")
    abstract fun refs(): Flow<List<ChannelRef>>

    /** Favourites whose channel exists. */
    @Query("SELECT COUNT(*) FROM $VISIBLE")
    abstract fun count(): Flow<Int>

    @Query("SELECT c.* FROM $VISIBLE ORDER BY f.position, c.id")
    abstract fun channels(): PagingSource<Int, ChannelEntity>

    /** Appends [ref] after the last favourite; does nothing when it already is one. */
    open suspend fun add(ref: ChannelRef, at: Instant) = insertLast(ref.playlistId, ref.key, at)

    @Query("DELETE FROM favourites WHERE playlistId = :playlistId AND channelKey = :channelKey")
    abstract suspend fun remove(playlistId: Long, channelKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE playlistId = :playlistId AND channelKey = :channelKey)")
    abstract suspend fun contains(playlistId: Long, channelKey: String): Boolean

    /**
     * The favourite after (direction >= 0) or before [current], wrapping around. When [current] is
     * not a favourite: the first (forward) or last (backward) one. Null when none is visible.
     */
    open suspend fun neighbour(current: ChannelEntity, direction: Int): ChannelEntity? {
        val position = positionOf(current.playlistId, current.key)
            ?: return if (direction >= 0) firstN(1).firstOrNull() else last()
        return if (direction >= 0) {
            afterN(position, current.id, 1).firstOrNull() ?: firstN(1).firstOrNull()
        } else {
            beforeN(position, current.id, 1).firstOrNull() ?: last()
        }
    }

    /**
     * Same contract as [ChannelDao.around]: up to [each] favourites before and after [current],
     * without wrapping, with [current] between them. When [current] is not a favourite:
     * `[current] + the first [each]`.
     */
    open suspend fun around(current: ChannelEntity, each: Int): List<ChannelEntity> {
        require(each >= 0) { "each must be >= 0, got $each" } // LIMIT -1 would load every favourite
        val position = positionOf(current.playlistId, current.key) ?: return listOf(current) + firstN(each)
        return beforeN(position, current.id, each).reversed() + current + afterN(position, current.id, each)
    }

    /**
     * Swaps [ref]'s position with the adjacent visible favourite in [direction] (< 0 = earlier).
     * False when [ref] is not a favourite or already first / last (no wrap).
     */
    @Transaction
    open suspend fun move(ref: ChannelRef, direction: Int): Boolean {
        val position = positionOf(ref.playlistId, ref.key) ?: return false
        val other = (if (direction < 0) visibleBefore(position) else visibleAfter(position)) ?: return false
        setPosition(ref.playlistId, ref.key, other.position)
        setPosition(other.playlistId, other.channelKey, position)
        return true
    }

    // --- internals ---

    @Query(
        """INSERT OR IGNORE INTO favourites (playlistId, channelKey, position, addedAt)
           SELECT :playlistId, :channelKey, COALESCE(MAX(position), 0) + 1, :at FROM favourites""",
    )
    protected abstract suspend fun insertLast(playlistId: Long, channelKey: String, at: Instant)

    @Query("SELECT position FROM favourites WHERE playlistId = :playlistId AND channelKey = :channelKey")
    protected abstract suspend fun positionOf(playlistId: Long, channelKey: String): Long?

    @Query("UPDATE favourites SET position = :position WHERE playlistId = :playlistId AND channelKey = :channelKey")
    protected abstract suspend fun setPosition(playlistId: Long, channelKey: String, position: Long)

    @Query("SELECT f.* FROM $VISIBLE WHERE f.position < :position ORDER BY f.position DESC LIMIT 1")
    protected abstract suspend fun visibleBefore(position: Long): FavouriteEntity?

    @Query("SELECT f.* FROM $VISIBLE WHERE f.position > :position ORDER BY f.position LIMIT 1")
    protected abstract suspend fun visibleAfter(position: Long): FavouriteEntity?

    @Query(
        """SELECT c.* FROM $VISIBLE WHERE f.position > :position OR (f.position = :position AND c.id > :id)
           ORDER BY f.position, c.id LIMIT :limit""",
    )
    protected abstract suspend fun afterN(position: Long, id: Long, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT c.* FROM $VISIBLE WHERE f.position < :position OR (f.position = :position AND c.id < :id)
           ORDER BY f.position DESC, c.id DESC LIMIT :limit""",
    )
    protected abstract suspend fun beforeN(position: Long, id: Long, limit: Int): List<ChannelEntity>

    @Query("SELECT c.* FROM $VISIBLE ORDER BY f.position, c.id LIMIT :limit")
    protected abstract suspend fun firstN(limit: Int): List<ChannelEntity>

    @Query("SELECT c.* FROM $VISIBLE ORDER BY f.position DESC, c.id DESC LIMIT 1")
    protected abstract suspend fun last(): ChannelEntity?
}
