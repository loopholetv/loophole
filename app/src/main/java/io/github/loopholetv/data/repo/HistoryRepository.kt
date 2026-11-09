package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ref
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** How many recently watched channels are kept. */
const val HISTORY_SIZE = 30

/** Recently watched channels, newest first, kept by (playlistId, key) across refreshes. */
class HistoryRepository(db: AppDatabase, private val clock: () -> Instant = Instant::now) {
    private val dao = db.historyDao()

    suspend fun record(c: ChannelEntity) = dao.record(c.ref, clock(), HISTORY_SIZE)

    fun recent(limit: Int = HISTORY_SIZE): Flow<List<ChannelEntity>> = dao.recent(limit)

    /** The most recently watched channel still present (null when none). */
    suspend fun latest(): ChannelEntity? = dao.latest()
}
