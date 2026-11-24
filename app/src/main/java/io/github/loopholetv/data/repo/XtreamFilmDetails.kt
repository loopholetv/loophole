package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.xtream.XTREAM_FILM_PREFIX
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.xtreamIdOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** A film's provider details are fetched again once they are this old. */
internal val FILM_INFO_FRESH_FOR: Duration = Duration.ofDays(7)

/**
 * Fetches an Xtream film's details (`get_vod_info`) when its page opens and they are missing or
 * stale. A reply with no details is stored too, so the film isn't asked again until it is stale; a
 * failed fetch stores nothing. One fetch per film runs at a time. The download runs outside any
 * transaction; the write re-checks the film, so a film removed meanwhile gets nothing.
 */
class XtreamFilmDetails(
    private val db: AppDatabase,
    private val client: XtreamClient,
    private val userAgent: suspend (PlaylistEntity) -> String,
    private val clock: () -> Instant,
) {
    private val dao = db.xtreamVodDao()
    private val locks = ConcurrentHashMap<Pair<Long, String>, Mutex>()

    /** Fetches film [key]'s details if it is an Xtream film whose details are missing or stale. Never throws. */
    suspend fun refreshIfStale(playlistId: Long, key: String) {
        val vodId = xtreamIdOf(key, XTREAM_FILM_PREFIX) ?: return
        try {
            locks.getOrPut(playlistId to key) { Mutex() }.withLock {
                val playlist = db.playlistDao().byId(playlistId)
                    ?.takeIf { it.type == PlaylistType.XTREAM && it.xtreamVod } ?: return
                val fetchedAt = dao.providerDetailsNow(playlistId, key)?.fetchedAt
                if (fetchedAt != null && clock().isBefore(fetchedAt.plus(FILM_INFO_FRESH_FOR))) return
                if (!filmExists(playlistId, key)) return
                val info = client.vodInfo(playlistCredentials(playlist), vodId, userAgent(playlist))
                db.withTransaction {
                    if (filmExists(playlistId, key)) {
                        dao.upsertProviderDetails(
                            ProviderDetailsEntity(
                                playlistId = playlistId,
                                key = key,
                                plot = info.plot,
                                cast = info.cast,
                                genre = info.genre,
                                rating = info.rating,
                                year = info.year,
                                runtimeMinutes = info.runtimeMinutes,
                                backdrop = info.backdrop,
                                fetchedAt = clock(),
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing stored: the next open asks again.
        }
    }

    private suspend fun filmExists(playlistId: Long, key: String): Boolean =
        dao.filmsSeen(playlistId, listOf(key)).isNotEmpty()

    companion object {
        fun isXtreamFilm(key: String): Boolean = key.startsWith(XTREAM_FILM_PREFIX)
    }
}
