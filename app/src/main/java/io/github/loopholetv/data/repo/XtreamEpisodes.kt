package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.xtream.XTREAM_EPISODE_PREFIX
import io.github.loopholetv.data.xtream.XTREAM_SHOW_PREFIX
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.data.xtream.XtreamEpisode
import io.github.loopholetv.data.xtream.XtreamVodKind
import io.github.loopholetv.data.xtream.xtreamEpisodeKey
import io.github.loopholetv.data.xtream.xtreamIdOf
import io.github.loopholetv.data.xtream.xtreamVodUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** What asking for a show's episodes did. */
enum class EpisodeFetch { NOT_NEEDED, FETCHED, FAILED }

/** Episodes are fetched again once they are this old. */
internal val EPISODES_FRESH_FOR: Duration = Duration.ofHours(24)

/** The player's fetch for a next episode skips a show fetched this recently. */
internal val NEXT_REFETCH_GAP: Duration = Duration.ofMinutes(10)

/**
 * Fetches an Xtream show's episodes (`get_series_info`) when they are needed: when its page opens
 * and they are missing or stale, and when one of its episodes plays and no later one is stored.
 * One fetch per show runs at a time. The download runs outside any transaction and doesn't take
 * the playlist update's lock; the write re-checks the show, so a show removed meanwhile gets nothing.
 */
class XtreamEpisodes(
    private val db: AppDatabase,
    private val client: XtreamClient,
    private val userAgent: suspend (PlaylistEntity) -> String,
    private val clock: () -> Instant,
    /** Runs after a fetch has looked at the show and before it waits for the show's lock (a test hook). */
    private val beforeWait: suspend () -> Unit = {},
) {
    private val dao = db.xtreamVodDao()
    private val locks = ConcurrentHashMap<Pair<Long, String>, Mutex>()

    /** Fetches [showKey]'s episodes if they were never fetched or are stale; [force] (Try again) fetches anyway. */
    suspend fun refreshIfStale(playlistId: Long, showKey: String, force: Boolean = false): EpisodeFetch =
        fetch(playlistId, showKey) { fetchedAt, now ->
            force || fetchedAt == null || !now.isBefore(fetchedAt.plus(EPISODES_FRESH_FOR))
        }

    /**
     * Fetches the episodes of the show [ref] (an Xtream episode) belongs to, so a newly added next
     * episode is found, unless they were fetched under [NEXT_REFETCH_GAP] ago. Never throws.
     */
    suspend fun fetchForNext(ref: VodRef) {
        if (ref.kind != VodKind.EPISODE || !ref.key.startsWith(XTREAM_EPISODE_PREFIX)) return
        val showKey = try {
            db.vodBrowseDao().episodeRow(ref.playlistId, ref.key)?.showKey
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        fetch(ref.playlistId, showKey) { fetchedAt, now ->
            fetchedAt == null || !now.isBefore(fetchedAt.plus(NEXT_REFETCH_GAP))
        }
    }

    private suspend fun fetch(playlistId: Long, showKey: String, due: (fetchedAt: Instant?, now: Instant) -> Boolean): EpisodeFetch {
        val seriesId = xtreamIdOf(showKey, XTREAM_SHOW_PREFIX) ?: return EpisodeFetch.NOT_NEEDED
        return try {
            // What the caller saw before waiting: a fetch that finished meanwhile answers it.
            val seen = dao.showOnce(playlistId, showKey)?.episodesFetchedAt
            beforeWait()
            locks.getOrPut(playlistId to showKey) { Mutex() }.withLock {
                val playlist = db.playlistDao().byId(playlistId)
                    ?.takeIf { it.type == PlaylistType.XTREAM && it.xtreamVod } ?: return EpisodeFetch.NOT_NEEDED
                val show = dao.showOnce(playlistId, showKey) ?: return EpisodeFetch.NOT_NEEDED
                // Fetched by another caller while this one waited; a fetch time cleared meanwhile doesn't count.
                val fetchedMeanwhile = show.episodesFetchedAt != null && show.episodesFetchedAt != seen
                if (fetchedMeanwhile || !due(show.episodesFetchedAt, clock())) return EpisodeFetch.NOT_NEEDED
                val creds = playlistCredentials(playlist)
                val episodes = client.seriesInfo(creds, seriesId, userAgent(playlist))
                if (store(playlistId, showKey, creds, episodes)) EpisodeFetch.FETCHED else EpisodeFetch.NOT_NEEDED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            EpisodeFetch.FAILED
        }
    }

    /** Writes a completed fetch; false when the show was removed while it downloaded. */
    private suspend fun store(p: Long, showKey: String, creds: XtreamCredentials, episodes: List<XtreamEpisode>): Boolean =
        db.withTransaction {
            val show = dao.showOnce(p, showKey) ?: return@withTransaction false
            val stored = dao.episodeKeysOf(p, showKey).associate { it.key to it.firstSeenAt }
            val now = clock()
            val firstEver = stored.isEmpty() && show.episodesFetchedAt == null
            val rows = rowsOf(show, creds, episodes) { key -> stored[key] ?: if (firstEver) Instant.EPOCH else now }
            val bump = !firstEver && rows.any { it.key !in stored }
            dao.replaceEpisodes(p, showKey, rows, now, bump)
            true
        }

    private fun rowsOf(
        show: ShowEntity,
        creds: XtreamCredentials,
        episodes: List<XtreamEpisode>,
        firstSeen: (String) -> Instant,
    ): List<EpisodeEntity> {
        val seen = HashSet<Long>()
        return episodes.filter { seen.add(it.id) }.mapIndexed { i, e ->
            val key = xtreamEpisodeKey(e.id)
            EpisodeEntity(
                playlistId = show.playlistId,
                key = key,
                showKey = show.key,
                groupTitle = show.groupTitle,
                name = e.title ?: "Episode ${e.episode ?: (i + 1)}",
                season = e.season,
                episode = e.episode,
                logo = e.cover,
                // Informational: the player builds the URL from the current login.
                streamUrl = xtreamVodUrl(creds, XtreamVodKind.SERIES, e.id, e.container),
                userAgent = null,
                referrer = null,
                tvgId = null,
                position = i + 1,
                firstSeenAt = firstSeen(key),
                container = e.container,
            )
        }
    }

    companion object {
        fun isXtreamShow(key: String): Boolean = key.startsWith(XTREAM_SHOW_PREFIX)
    }
}
