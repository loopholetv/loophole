package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import java.time.Instant

/**
 * Writes one Xtream account's films and shows, upserting by key in batches of [BATCH_SIZE], each
 * batch in its own short transaction so other writers (an episode fetch) can interleave. Unlike
 * [VodWriter] it never rebuilds shows and never touches episodes, except when a show is pruned.
 *
 * - A key repeated within one list is skipped (the first wins).
 * - `firstSeenAt` keeps the stored value. A new key gets [Instant.EPOCH] when the playlist had no
 *   Xtream films (or shows) before this list started, so a first import isn't "recently added";
 *   otherwise it gets [now].
 * - A show keeps its `episodesFetchedAt` unless its `lastModified` changed, which clears it so the
 *   episodes are fetched again.
 * - [pruneFilms] and [pruneShows] delete what the list didn't name; call them only after the list
 *   completed.
 */
internal class XtreamVodWriter(
    private val db: AppDatabase,
    private val playlistId: Long,
    private val now: Instant,
) {
    private val dao = db.xtreamVodDao()
    private val filmKeys = HashSet<String>()
    private val showKeys = HashSet<String>()
    private val films = ArrayList<FilmEntity>(BATCH_SIZE)
    private val shows = ArrayList<ShowEntity>(BATCH_SIZE)
    private var filmsInitial: Boolean? = null
    private var showsInitial: Boolean? = null

    val filmsSeen: Int get() = filmKeys.size
    val showsSeen: Int get() = showKeys.size

    suspend fun film(row: FilmEntity) {
        if (!filmKeys.add(row.key)) return
        if (filmsInitial == null) filmsInitial = dao.xtreamFilmCount(playlistId) == 0
        films += row
        if (films.size >= BATCH_SIZE) flushFilms()
    }

    suspend fun show(row: ShowEntity) {
        if (!showKeys.add(row.key)) return
        if (showsInitial == null) showsInitial = dao.xtreamShowCount(playlistId) == 0
        shows += row
        if (shows.size >= BATCH_SIZE) flushShows()
    }

    suspend fun flush() {
        flushFilms()
        flushShows()
    }

    /** Deletes stored Xtream films this list didn't name, with their provider details. */
    suspend fun pruneFilms() {
        flushFilms()
        dao.xtreamFilmKeys(playlistId).filter { it !in filmKeys }.chunked(BATCH_SIZE)
            .forEach { dao.deleteFilms(playlistId, it) }
    }

    /** Deletes stored Xtream shows this list didn't name, with their episodes. */
    suspend fun pruneShows() {
        flushShows()
        dao.xtreamShowKeys(playlistId).filter { it !in showKeys }.chunked(BATCH_SIZE)
            .forEach { dao.deleteShows(playlistId, it) }
    }

    private fun newFirstSeen(initial: Boolean?): Instant = if (initial == true) Instant.EPOCH else now

    private suspend fun flushFilms() {
        if (films.isEmpty()) return
        val batch = films.toList()
        films.clear()
        db.withTransaction {
            val stored = dao.filmsSeen(playlistId, batch.map { it.key }).associate { it.key to it.firstSeenAt }
            dao.upsertFilms(batch.map { it.copy(firstSeenAt = stored[it.key] ?: newFirstSeen(filmsInitial)) })
        }
    }

    private suspend fun flushShows() {
        if (shows.isEmpty()) return
        val batch = shows.toList()
        shows.clear()
        db.withTransaction {
            val stored = dao.showStates(playlistId, batch.map { it.key }).associateBy { it.key }
            dao.upsertShows(
                batch.map { row ->
                    val old = stored[row.key]
                    row.copy(
                        firstSeenAt = old?.firstSeenAt ?: newFirstSeen(showsInitial),
                        episodesFetchedAt = old?.episodesFetchedAt?.takeIf { old.lastModified == row.lastModified },
                    )
                },
            )
        }
    }
}
