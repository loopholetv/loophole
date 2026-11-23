package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.TitleKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant

private const val TOUCH_CHUNK = 500

/** What a lookup needs from a playlist film or show. A show's [tvgId] is the one most of its episodes share. */
data class VodTitleSource(val key: String, val name: String, val tvgId: String?)

/** TMDB details and the matches linking them to playlist films and shows. */
@Dao
abstract class TmdbDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun insertTitle(t: TmdbTitleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun insertTitleGenres(rows: List<TmdbTitleGenreEntity>)

    @Query("DELETE FROM tmdb_title_genres WHERE tmdbKind = :kind AND tmdbId = :id")
    protected abstract suspend fun deleteTitleGenres(kind: TmdbKind, id: Long)

    /** Stores [t] and replaces its genre links with [genres] in one transaction. */
    @Transaction
    open suspend fun upsertTitle(t: TmdbTitleEntity, genres: List<Int>) {
        insertTitle(t)
        deleteTitleGenres(t.tmdbKind, t.tmdbId)
        insertTitleGenres(genres.distinct().map { TmdbTitleGenreEntity(t.tmdbKind, t.tmdbId, it) })
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun insertEpisodes(rows: List<TmdbEpisodeEntity>)

    @Query("DELETE FROM tmdb_episodes WHERE tvId = :tvId")
    protected abstract suspend fun deleteEpisodes(tvId: Long)

    /** Replaces every stored episode of show [tvId] with [list]. */
    @Transaction
    open suspend fun upsertEpisodes(tvId: Long, list: List<TmdbEpisodeEntity>) {
        deleteEpisodes(tvId)
        insertEpisodes(list)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertGenres(list: List<TmdbGenreEntity>)

    @Query("SELECT DISTINCT language FROM tmdb_genres WHERE tmdbKind = :kind")
    abstract suspend fun genreLanguages(kind: TmdbKind): List<String>

    @Query("DELETE FROM tmdb_genres WHERE tmdbKind = :kind")
    protected abstract suspend fun deleteGenres(kind: TmdbKind)

    /** Replaces every cached genre name of [kind] with [list]. */
    @Transaction
    open suspend fun replaceGenres(kind: TmdbKind, list: List<TmdbGenreEntity>) {
        deleteGenres(kind)
        upsertGenres(list)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertMatch(m: VodMatchEntity)

    @Query("SELECT * FROM vod_matches WHERE playlistId = :p AND kind = :kind")
    abstract suspend fun matchesOf(p: Long, kind: TitleKind): List<VodMatchEntity>

    @Query("SELECT * FROM tmdb_titles WHERE tmdbKind = :kind AND tmdbId = :id")
    abstract suspend fun titleNow(kind: TmdbKind, id: Long): TmdbTitleEntity?

    @Query("SELECT `key`, name, tvgId FROM vod_films WHERE playlistId = :p")
    abstract suspend fun filmSources(p: Long): List<VodTitleSource>

    @Query(
        """SELECT s.`key`, s.name, (SELECT e.tvgId FROM vod_episodes e
             WHERE e.playlistId = s.playlistId AND e.showKey = s.`key` AND TRIM(COALESCE(e.tvgId, '')) != ''
             GROUP BY e.tvgId ORDER BY COUNT(*) DESC, e.tvgId LIMIT 1) AS tvgId
           FROM vod_shows s WHERE s.playlistId = :p""",
    )
    abstract suspend fun showSources(p: Long): List<VodTitleSource>

    @Query("SELECT * FROM vod_matches WHERE playlistId = :p AND kind = :kind AND `key` = :key")
    abstract fun observeMatch(p: Long, kind: TitleKind, key: String): Flow<VodMatchEntity?>

    @Query("SELECT * FROM vod_matches WHERE playlistId = :p AND kind = :kind AND `key` = :key")
    abstract suspend fun match(p: Long, kind: TitleKind, key: String): VodMatchEntity?

    /** The most recently looked-up match for [fp], whichever playlist it came from. */
    @Query("SELECT * FROM vod_matches WHERE fingerprint = :fp ORDER BY lookedUpAt DESC LIMIT 1")
    abstract suspend fun matchByFingerprint(fp: String): VodMatchEntity?

    /** Every match for [fp], the most recently looked up first. */
    @Query("SELECT * FROM vod_matches WHERE fingerprint = :fp ORDER BY lookedUpAt DESC")
    abstract suspend fun matchesByFingerprint(fp: String): List<VodMatchEntity>

    /**
     * Marks a match as rejected by the viewer and drops its TMDB link. Every other copy of the title (same
     * fingerprint) matched to the same TMDB title is rejected with it, so the grid's one card per title
     * doesn't simply show another copy's identical wrong match.
     */
    @Transaction
    open suspend fun reject(p: Long, kind: TitleKind, key: String, now: Instant) {
        val m = match(p, kind, key)
        rejectOne(p, kind, key, now)
        val tmdbKind = m?.tmdbKind
        val tmdbId = m?.tmdbId
        if (m?.state == MatchState.MATCHED && tmdbKind != null && tmdbId != null) {
            rejectCopies(kind, m.fingerprint, tmdbKind, tmdbId, now)
        }
    }

    @Query(
        """UPDATE vod_matches SET state = 'REJECTED', tmdbKind = NULL, tmdbId = NULL, lookedUpAt = :now
           WHERE playlistId = :p AND kind = :kind AND `key` = :key""",
    )
    protected abstract suspend fun rejectOne(p: Long, kind: TitleKind, key: String, now: Instant)

    @Query(
        """UPDATE vod_matches SET state = 'REJECTED', tmdbKind = NULL, tmdbId = NULL, lookedUpAt = :now
           WHERE kind = :kind AND fingerprint = :fp AND state = 'MATCHED' AND tmdbKind = :tmdbKind AND tmdbId = :tmdbId""",
    )
    protected abstract suspend fun rejectCopies(kind: TitleKind, fp: String, tmdbKind: TmdbKind, tmdbId: Long, now: Instant)

    /**
     * Marks [keys] as present in their playlist at [now], except rows already marked since [staleBefore]:
     * an unchanged library rewrites nothing, so the screens reading these rows are not woken for nothing.
     * Chunked to stay under SQLite's variable limit.
     */
    @Transaction
    open suspend fun touchSeen(p: Long, kind: TitleKind, keys: List<String>, now: Instant, staleBefore: Instant) {
        keys.chunked(TOUCH_CHUNK).forEach { touchChunk(p, kind, it, now, staleBefore) }
    }

    @Query(
        """UPDATE vod_matches SET lastSeenAt = :now
           WHERE playlistId = :p AND kind = :kind AND `key` IN (:keys) AND lastSeenAt < :staleBefore""",
    )
    protected abstract suspend fun touchChunk(p: Long, kind: TitleKind, keys: List<String>, now: Instant, staleBefore: Instant)

    /** Drops matches whose title has not been seen since [before]. */
    @Query("DELETE FROM vod_matches WHERE lastSeenAt < :before")
    abstract suspend fun pruneUnseen(before: Instant)

    /** Drops titles, genre links and episodes that no MATCHED row references. */
    @Transaction
    open suspend fun deleteOrphanTitles() {
        deleteOrphanEpisodes()
        deleteOrphanGenreLinks()
        deleteOrphanTitleRows()
    }

    @Query(
        """DELETE FROM tmdb_episodes WHERE NOT EXISTS (SELECT 1 FROM vod_matches m WHERE m.state = 'MATCHED'
           AND m.tmdbKind = 'TV' AND m.tmdbId = tmdb_episodes.tvId)""",
    )
    protected abstract suspend fun deleteOrphanEpisodes()

    @Query(
        """DELETE FROM tmdb_title_genres WHERE NOT EXISTS (SELECT 1 FROM vod_matches m WHERE m.state = 'MATCHED'
           AND m.tmdbKind = tmdb_title_genres.tmdbKind AND m.tmdbId = tmdb_title_genres.tmdbId)""",
    )
    protected abstract suspend fun deleteOrphanGenreLinks()

    @Query(
        """DELETE FROM tmdb_titles WHERE NOT EXISTS (SELECT 1 FROM vod_matches m WHERE m.state = 'MATCHED'
           AND m.tmdbKind = tmdb_titles.tmdbKind AND m.tmdbId = tmdb_titles.tmdbId)""",
    )
    protected abstract suspend fun deleteOrphanTitleRows()

    @Query("SELECT * FROM tmdb_titles WHERE tmdbKind = :kind AND tmdbId = :id")
    abstract fun title(kind: TmdbKind, id: Long): Flow<TmdbTitleEntity?>

    @Query(
        """SELECT g.name FROM tmdb_title_genres l JOIN tmdb_genres g ON g.tmdbKind = l.tmdbKind AND g.genreId = l.genreId
           WHERE l.tmdbKind = :kind AND l.tmdbId = :id ORDER BY g.name""",
    )
    abstract fun genresOf(kind: TmdbKind, id: Long): Flow<List<String>>

    @Query("SELECT * FROM tmdb_episodes WHERE tvId = :tvId ORDER BY season, episode")
    abstract fun episodes(tvId: Long): Flow<List<TmdbEpisodeEntity>>

    @Query(
        """SELECT t.* FROM vod_matches m JOIN tmdb_titles t ON t.tmdbKind = m.tmdbKind AND t.tmdbId = m.tmdbId
           WHERE m.playlistId = :p AND m.kind = :kind AND m.`key` = :key AND m.state = 'MATCHED'""",
    )
    abstract fun matchedTitle(p: Long, kind: TitleKind, key: String): Flow<TmdbTitleEntity?>
}
