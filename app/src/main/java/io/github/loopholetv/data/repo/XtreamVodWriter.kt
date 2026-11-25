package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.ShowPrefsEntity
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
 *   completed. A vanished title whose name and group come back exactly once under a new id (a new
 *   address for the account) hands its edits to it first: a film its prefs row (lock and hide), a show
 *   its lock. The hand-over and the old title's removal commit together, so a lock is never lost.
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
    /** Keys this list wrote that weren't stored before it. */
    private val freshFilms = HashSet<String>()
    private val freshShows = HashSet<String>()
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
        val stale = dao.xtreamFilmKeys(playlistId).filter { it !in filmKeys }
        val carried = carryFilmPrefs(stale)
        stale.filter { it !in carried }.chunked(BATCH_SIZE).forEach { dao.deleteFilms(playlistId, it) }
    }

    /** Deletes stored Xtream shows this list didn't name, with their episodes. */
    suspend fun pruneShows() {
        flushShows()
        val stale = dao.xtreamShowKeys(playlistId).filter { it !in showKeys }
        val carried = carryShowLocks(stale)
        stale.filter { it !in carried }.chunked(BATCH_SIZE).forEach { dao.deleteShows(playlistId, it) }
    }

    /** Moves the prefs of [stale] films to their new ids, each with the old film's removal. Returns the films removed. */
    private suspend fun carryFilmPrefs(stale: List<String>): Set<String> {
        if (stale.isEmpty() || freshFilms.isEmpty()) return emptySet()
        val withPrefs = db.channelPrefsDao().forPlaylist(playlistId).mapTo(HashSet()) { it.channelKey }
        if (stale.none { it in withPrefs }) return emptySet()
        val moved = HashSet<String>()
        for ((from, to) in plan(dao.xtreamFilmNameGroups(playlistId), freshFilms, filmKeys)) {
            if (from !in withPrefs) continue
            db.withTransaction {
                db.maintenanceDao().rekeyFilmPrefs(playlistId, from, to)
                dao.deleteFilms(playlistId, listOf(from))
            }
            moved += from
        }
        return moved
    }

    /** Moves the locks of [stale] shows to their new ids, each with the old show's removal. Returns the shows removed. */
    private suspend fun carryShowLocks(stale: List<String>): Set<String> {
        if (stale.isEmpty() || freshShows.isEmpty()) return emptySet()
        val locks = db.lockDao()
        val locked = locks.lockedShowKeys(playlistId).toHashSet()
        if (stale.none { it in locked }) return emptySet()
        val moved = HashSet<String>()
        for ((from, to) in plan(dao.xtreamShowNameGroups(playlistId), freshShows, showKeys)) {
            if (from !in locked) continue
            db.withTransaction {
                locks.upsertShowPrefs(ShowPrefsEntity(playlistId, to, locked = true))
                locks.deleteShowPrefs(playlistId, from)
                dao.deleteShows(playlistId, listOf(from))
            }
            moved += from
        }
        return moved
    }

    /** Old key to new key by name and group: [rows] stored before this list (all but [fresh]) against this list's [listed]. */
    private fun plan(rows: List<KeyNameGroup>, fresh: Set<String>, listed: Set<String>): Map<String, String> =
        carryOverPlan(rows.filter { it.key !in fresh }, rows.filter { it.key in listed })

    private fun newFirstSeen(initial: Boolean?): Instant = if (initial == true) Instant.EPOCH else now

    private suspend fun flushFilms() {
        if (films.isEmpty()) return
        val batch = films.toList()
        films.clear()
        db.withTransaction {
            val stored = dao.filmsSeen(playlistId, batch.map { it.key }).associate { it.key to it.firstSeenAt }
            batch.mapNotNullTo(freshFilms) { row -> row.key.takeIf { it !in stored } }
            dao.upsertFilms(batch.map { it.copy(firstSeenAt = stored[it.key] ?: newFirstSeen(filmsInitial)) })
        }
    }

    private suspend fun flushShows() {
        if (shows.isEmpty()) return
        val batch = shows.toList()
        shows.clear()
        db.withTransaction {
            val stored = dao.showStates(playlistId, batch.map { it.key }).associateBy { it.key }
            batch.mapNotNullTo(freshShows) { row -> row.key.takeIf { it !in stored } }
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
