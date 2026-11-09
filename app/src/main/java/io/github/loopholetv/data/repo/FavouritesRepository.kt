package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Favourite channels, kept by (playlistId, key) so they survive playlist refreshes. */
class FavouritesRepository(private val db: AppDatabase, private val clock: () -> Instant = Instant::now) {
    private val dao = db.favouriteDao()

    /** Every stored favourite (also those whose channel is currently absent). */
    val refs: Flow<Set<ChannelRef>> = dao.refs().map { it.toSet() }

    /** Favourites whose channel is present. */
    val count: Flow<Int> = dao.count()

    suspend fun isFavourite(c: ChannelEntity): Boolean = dao.contains(c.playlistId, c.key)

    /** Adds [c] (at the end) or removes it; returns whether it is a favourite now. */
    suspend fun toggle(c: ChannelEntity): Boolean = db.withTransaction {
        if (dao.contains(c.playlistId, c.key)) {
            dao.remove(c.playlistId, c.key)
            false
        } else {
            dao.add(c.ref, clock())
            true
        }
    }

    /** Moves [c] one place earlier (direction < 0) or later; false at either end. */
    suspend fun move(c: ChannelEntity, direction: Int): Boolean = dao.move(c.ref, direction)
}
