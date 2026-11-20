package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.data.vod.TitleKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A film or show key within its playlist. */
data class KeyRef(val playlistId: Long, val key: String)

/** A film with unfinished progress, for the Continue watching row. */
data class ContinueFilmRow(@Embedded val card: FilmCard, val positionMs: Long, val durationMs: Long?)

/** The latest watch time of one episode, with the show it belongs to. */
data class EpisodeProgressRow(val playlistId: Long, val showKey: String, val lastWatchedAt: Instant)

/** My list and Continue watching dismissals. Reads apply the same visibility rules as browse. */
@Dao
abstract class VodLibraryDao {
    @Query("SELECT EXISTS (SELECT 1 FROM vod_my_list WHERE kind = :kind AND playlistId = :playlistId AND `key` = :key)")
    abstract fun observeInList(kind: TitleKind, playlistId: Long, key: String): Flow<Boolean>

    @Query("SELECT playlistId, `key` FROM vod_my_list WHERE kind = :kind")
    abstract fun observeListKeys(kind: TitleKind): Flow<List<KeyRef>>

    @Upsert abstract suspend fun add(row: MyListEntity)

    @Query("DELETE FROM vod_my_list WHERE kind = :kind AND playlistId = :playlistId AND `key` = :key")
    abstract suspend fun remove(kind: TitleKind, playlistId: Long, key: String)

    @Query(
        """SELECT $FILM_CARD FROM vod_my_list m JOIN vod_films f ON f.playlistId = m.playlistId AND f.`key` = m.`key`
           WHERE m.kind = 'FILM' AND $FILM_VISIBLE ORDER BY m.addedAt DESC""",
    )
    abstract fun myListFilms(): Flow<List<FilmCard>>

    @Query(
        """SELECT $SHOW_CARD FROM vod_my_list m JOIN vod_shows s ON s.playlistId = m.playlistId AND s.`key` = m.`key`
           WHERE m.kind = 'SHOW' AND $SHOW_VISIBLE ORDER BY m.addedAt DESC""",
    )
    abstract fun myListShows(): Flow<List<ShowCard>>

    @Upsert abstract suspend fun dismiss(row: ContinueDismissalEntity)

    /**
     * Films watched for at least [minMs], most recent first, without those dismissed since they
     * were last watched. Position rather than `watched` decides, so a rewatch counts.
     */
    @Query(
        """SELECT $FILM_CARD, p.positionMs AS positionMs, p.durationMs AS durationMs
           FROM vod_progress p JOIN vod_films f ON f.playlistId = p.playlistId AND f.`key` = p.`key`
           WHERE p.kind = 'MOVIE' AND p.positionMs >= :minMs AND $FILM_VISIBLE
           AND NOT EXISTS (SELECT 1 FROM vod_continue_dismissed d WHERE d.kind = 'FILM'
                           AND d.playlistId = p.playlistId AND d.`key` = p.`key` AND d.dismissedAt >= p.lastWatchedAt)
           ORDER BY p.lastWatchedAt DESC LIMIT :limit""",
    )
    abstract fun continueFilms(minMs: Long, limit: Int): Flow<List<ContinueFilmRow>>

    /** Episode progress of visible shows, most recent first. */
    @Query(
        """SELECT p.playlistId AS playlistId, e.showKey AS showKey, p.lastWatchedAt AS lastWatchedAt
           FROM vod_progress p
           JOIN vod_episodes e ON e.playlistId = p.playlistId AND e.`key` = p.`key`
           JOIN vod_shows s ON s.playlistId = e.playlistId AND s.`key` = e.showKey
           WHERE p.kind = 'EPISODE' AND $SHOW_VISIBLE
           AND NOT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = e.playlistId AND c.channelKey = e.`key` AND c.hidden = 1)
           ORDER BY p.lastWatchedAt DESC LIMIT :limit""",
    )
    abstract fun recentEpisodeProgress(limit: Int): Flow<List<EpisodeProgressRow>>

    @Query("SELECT dismissedAt FROM vod_continue_dismissed WHERE kind = :kind AND playlistId = :playlistId AND `key` = :key")
    abstract suspend fun dismissedAt(kind: TitleKind, playlistId: Long, key: String): Instant?

    @Query("SELECT * FROM vod_continue_dismissed WHERE kind = :kind")
    abstract fun observeDismissals(kind: TitleKind): Flow<List<ContinueDismissalEntity>>

    /** Moves a film's list entry and dismissal to a new key. A row already present under [to] wins and the old one stays. */
    @Transaction
    open suspend fun rekeyFilm(p: Long, from: String, to: String) {
        rekeyListFilm(p, from, to)
        rekeyDismissedFilm(p, from, to)
    }

    @Query("UPDATE OR IGNORE vod_my_list SET `key` = :to WHERE kind = 'FILM' AND playlistId = :p AND `key` = :from")
    protected abstract suspend fun rekeyListFilm(p: Long, from: String, to: String)

    @Query("UPDATE OR IGNORE vod_continue_dismissed SET `key` = :to WHERE kind = 'FILM' AND playlistId = :p AND `key` = :from")
    protected abstract suspend fun rekeyDismissedFilm(p: Long, from: String, to: String)

    @Transaction
    open suspend fun deleteForPlaylist(p: Long) {
        deleteListForPlaylist(p)
        deleteDismissedForPlaylist(p)
    }

    @Query("DELETE FROM vod_my_list WHERE playlistId = :p")
    protected abstract suspend fun deleteListForPlaylist(p: Long)

    @Query("DELETE FROM vod_continue_dismissed WHERE playlistId = :p")
    protected abstract suspend fun deleteDismissedForPlaylist(p: Long)

    /** Drops My list rows added, and dismissals made, before [before] whose film or show is gone from its playlist. */
    @Transaction
    open suspend fun deleteOrphans(before: Instant) {
        deleteOrphanList(before)
        deleteOrphanDismissals(before)
    }

    @Query(
        """DELETE FROM vod_my_list WHERE addedAt < :before AND NOT (
           (kind = 'FILM' AND EXISTS (SELECT 1 FROM vod_films f WHERE f.playlistId = vod_my_list.playlistId AND f.`key` = vod_my_list.`key`))
           OR (kind = 'SHOW' AND EXISTS (SELECT 1 FROM vod_shows s WHERE s.playlistId = vod_my_list.playlistId AND s.`key` = vod_my_list.`key`)))""",
    )
    protected abstract suspend fun deleteOrphanList(before: Instant)

    @Query(
        """DELETE FROM vod_continue_dismissed WHERE dismissedAt < :before AND NOT (
           (kind = 'FILM' AND EXISTS (SELECT 1 FROM vod_films f WHERE f.playlistId = vod_continue_dismissed.playlistId AND f.`key` = vod_continue_dismissed.`key`))
           OR (kind = 'SHOW' AND EXISTS (SELECT 1 FROM vod_shows s WHERE s.playlistId = vod_continue_dismissed.playlistId AND s.`key` = vod_continue_dismissed.`key`)))""",
    )
    protected abstract suspend fun deleteOrphanDismissals(before: Instant)
}
