package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.VodProgressDao
import io.github.loopholetv.data.db.VodProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Saves and observes watch progress for films and episodes. */
class VodProgressRepository(
    private val dao: VodProgressDao,
    private val clock: () -> Instant,
) {
    suspend fun record(ref: VodRef, positionMs: Long, durationMs: Long?) {
        if (ref.kind == VodKind.RECORDING) return
        val old = dao.get(ref.kind, ref.playlistId, ref.key)
        progressAfter(old, ref, positionMs, durationMs, clock())?.let { dao.upsert(it) }
    }

    /** Stores the end of a stream that played out; an unknown-length one starts from the beginning next time. */
    suspend fun recordEnd(ref: VodRef, positionMs: Long, durationMs: Long?) {
        if (ref.kind == VodKind.RECORDING) return
        val old = dao.get(ref.kind, ref.playlistId, ref.key)
        progressAfterNaturalEnd(old, ref, positionMs, durationMs, clock())?.let { dao.upsert(it) }
    }

    suspend fun get(ref: VodRef): VodProgressEntity? = dao.get(ref.kind, ref.playlistId, ref.key)

    fun observe(ref: VodRef): Flow<VodProgressEntity?> = dao.observe(ref.kind, ref.playlistId, ref.key)

    fun observeEpisodes(playlistId: Long, showKey: String): Flow<Map<String, VodProgressEntity>> =
        dao.observeForShow(VodKind.EPISODE, playlistId, showKey).map { rows -> rows.associateBy { it.key } }
}
