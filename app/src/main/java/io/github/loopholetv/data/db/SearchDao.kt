package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import java.time.Duration
import java.time.Instant

/** Shorter queries are not searched (they would match nearly everything). */
const val MIN_QUERY_LENGTH = 2

/**
 * The `LIKE` pattern (with `ESCAPE '\'`) matching [query] literally anywhere in a value, or null
 * when the trimmed query is shorter than [MIN_QUERY_LENGTH].
 */
fun searchPattern(query: String): String? {
    val q = query.trim()
    if (q.length < MIN_QUERY_LENGTH) return null
    val escaped = buildString(q.length + 8) {
        for (ch in q) {
            if (ch == '\\' || ch == '%' || ch == '_') append('\\')
            append(ch)
        }
    }
    return "%$escaped%"
}

/**
 * The largest guide time shift (±12 h). [SearchDao.programmes] prefilters on the stop index
 * with `now - MAX_SHIFT`, then tests each channel's own shift exactly.
 */
internal val MAX_SHIFT: Duration = Duration.ofHours(12)

/**
 * [SearchDao.programmes]'s SQL (a constant so tests can check its query plan). Without INDEXED BY
 * SQLite (no ANALYZE stats) scans the whole table in rowid order to serve `GROUP BY p.id`; the
 * index lets it range on `stop > :earliestStop` and test title / generation before reading a row.
 * A programme is still on when its shifted stop (`stop + shift`) is after [now]; it is listed
 * once, with the lowest guide number among the visible channels showing it (SQLite takes the bare
 * columns from the `MIN` row). Channel columns are display values.
 */
internal const val PROGRAMME_SEARCH_SQL =
    """SELECT p.*, c.id AS channelId, COALESCE(c.customName, c.name) AS channelName,
              MIN(c.guideNumber) AS channelNumber, COALESCE(c.customLogo, c.logo) AS channelLogo,
              c.epgShiftMinutes AS shiftMinutes
       FROM programmes p INDEXED BY index_programmes_stop_title_epgChannelId_generation
       JOIN channels c ON c.guideEpgId = p.epgChannelId AND c.visible = 1
       WHERE p.title LIKE :pattern ESCAPE '\' AND p.stop > :earliestStop
             AND p.stop + c.epgShiftMinutes * 60000 > :now AND p.$COMMITTED
       GROUP BY p.id ORDER BY p.start + c.epgShiftMinutes * 60000, channelNumber LIMIT :limit"""

/**
 * Substring search over channel names and guide titles. Patterns come from [searchPattern];
 * SQLite's `LIKE` ignores case for ASCII letters only. Both queries are LIMITed.
 */
@Dao
abstract class SearchDao {
    /**
     * Visible channels whose original or custom name matches, in the All-channels order;
     * [limit] must be > 0.
     */
    open suspend fun channels(pattern: String, limit: Int): List<ChannelEntity> {
        require(limit > 0) { "limit must be > 0, got $limit" } // LIMIT -1 would mean unlimited
        return channelsQuery(pattern, limit)
    }

    /**
     * Committed programmes airing at [now] or later on a visible channel's shifted clock whose
     * title matches, one row per programme with the lowest-numbered visible channel carrying it.
     * Programmes no visible channel is mapped to are skipped. [limit] must be > 0.
     */
    open suspend fun programmes(pattern: String, now: Instant, limit: Int): List<ProgrammeHit> {
        require(limit > 0) { "limit must be > 0, got $limit" }
        return programmesQuery(pattern, now, now.minus(MAX_SHIFT), limit)
    }

    @Query(
        """SELECT * FROM channels
           WHERE visible = 1 AND (name LIKE :pattern ESCAPE '\' OR customName LIKE :pattern ESCAPE '\')
           ORDER BY guideNumber, playlistId, sortOrder, id LIMIT :limit""",
    )
    protected abstract suspend fun channelsQuery(pattern: String, limit: Int): List<ChannelEntity>

    @Query(PROGRAMME_SEARCH_SQL)
    protected abstract suspend fun programmesQuery(
        pattern: String,
        now: Instant,
        earliestStop: Instant,
        limit: Int,
    ): List<ProgrammeHit>
}
