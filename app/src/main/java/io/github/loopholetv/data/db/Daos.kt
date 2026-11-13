package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun all(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET lastRefreshedAt = :at, lastError = NULL, epgUrlsFromSource = :epgUrls WHERE id = :id")
    suspend fun markRefreshed(id: Long, at: Instant, epgUrls: String)

    @Query("UPDATE playlists SET xtreamTimeZone = :zone, xtreamArchiveExt = :ext WHERE id = :id")
    suspend fun setArchiveInfo(id: Long, zone: String?, ext: String?)

    @Query("UPDATE playlists SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlists SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE playlists SET userAgent = :ua WHERE id = :id")
    suspend fun setUserAgent(id: Long, ua: String?)

    /** null = "Same as default" for either option. */
    @Query("UPDATE playlists SET updateHours = :hours, updateOnStart = :onStart WHERE id = :id")
    suspend fun setUpdateOptions(id: Long, hours: Int?, onStart: Boolean?)

    @Query("UPDATE playlists SET useSourceEpg = :use WHERE id = :id")
    suspend fun setUseSourceEpg(id: Long, use: Boolean)

    @Query("UPDATE playlists SET rekeyPending = :pending WHERE id = :id")
    suspend fun setRekeyPending(id: Long, pending: Boolean)

    /** The MAC portal's working endpoint and session token (null clears them). */
    @Query("UPDATE playlists SET portalEndpoint = :endpoint, portalToken = :token WHERE id = :id")
    suspend fun setPortalSession(id: Long, endpoint: String?, token: String?)

    /** Channels, prefs, EPG sources and guide channels go with it (ON DELETE CASCADE). */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
abstract class ChannelDao {
    /** The longest archive window among visible catch-up channels; a channel without a stated length counts as 1 day. */
    @Query("SELECT MAX(COALESCE(catchupDays, 1)) FROM channels WHERE visible = 1 AND catchupType IS NOT NULL")
    abstract suspend fun maxCatchupDays(): Int?

    // Every guide/player read filters `visible = 1` (hidden channel, hidden group or turned-off
    // playlist); only the `editor…` queries include hidden rows.
    //
    // Two zapping orders, each a total order matched exactly by its keyset queries below:
    //  - All channels: (guideNumber, playlistId, sortOrder, id)
    //  - one group of one playlist: (groupRank, number, id) — groupRank is written by ChannelOverlay
    //    from the group's sort mode.
    // Groups within a playlist: positioned ones first (group_prefs.position), then MIN(groupOrder).

    /**
     * The guide's groups: one row per (playlist, original title) with visible channels, the
     * custom name as label, ordered by playlist, then the custom position or the source order.
     */
    @Query(
        """SELECT c.playlistId AS playlistId, c.groupTitle AS title,
                  COALESCE(gp.customName, c.groupTitle) AS label,
                  COALESCE(gp.position, MIN(c.groupOrder)) AS `order`, COUNT(*) AS count,
                  pl.name AS playlistName, COALESCE(gp.sortMode, 'PLAYLIST') AS sortMode
           FROM channels c JOIN playlists pl ON pl.id = c.playlistId
           LEFT JOIN group_prefs gp ON gp.playlistId = c.playlistId AND gp.groupTitle = c.groupTitle
           WHERE c.visible = 1
           GROUP BY c.playlistId, c.groupTitle
           ORDER BY c.playlistId, gp.position IS NULL, gp.position, MIN(c.groupOrder), title""",
    )
    abstract fun groups(): Flow<List<GroupRow>>

    /** Every visible channel in the All-channels order. */
    @Query("SELECT * FROM channels WHERE visible = 1 ORDER BY guideNumber, playlistId, sortOrder, id")
    abstract fun allChannels(): PagingSource<Int, ChannelEntity>

    /** The visible channels of one playlist group (original [title]) in the group order. */
    @Query(
        """SELECT * FROM channels WHERE visible = 1 AND playlistId = :playlistId AND groupTitle = :title
           ORDER BY groupRank, number, id""",
    )
    abstract fun groupChannels(playlistId: Long, title: String): PagingSource<Int, ChannelEntity>

    /** The visible channel with guide number [n] (unique across enabled playlists; ties by playlist). */
    @Query("SELECT * FROM channels WHERE visible = 1 AND guideNumber = :n ORDER BY playlistId, sortOrder, id LIMIT 1")
    abstract suspend fun byNumber(n: Int): ChannelEntity?

    /** Any channel by id, hidden ones too (the channel the player was asked to open). */
    @Query("SELECT * FROM channels WHERE id = :id")
    abstract suspend fun byId(id: Long): ChannelEntity?

    /** Whether channel [id] is shown (false: hidden, in a hidden group or a turned-off playlist); null once it is gone. */
    @Query("SELECT visible FROM channels WHERE id = :id")
    abstract fun observeVisible(id: Long): Flow<Boolean?>

    /**
     * The visible channel after (direction >= 0) or before (direction < 0) [current] within [group]
     * (null = all channels), wrapping around at either end, in that scope's order. [current] need
     * not belong to [group] or be visible; null only when the scope is empty. [group] must be
     * resolved (a `Group(0, …)` matches nothing).
     */
    open suspend fun neighbour(current: ChannelEntity, group: ChannelScope.Group?, direction: Int): ChannelEntity? =
        with(current) {
            when {
                group == null && direction >= 0 ->
                    allAfter(guideNumber, playlistId, sortOrder, id, 1).firstOrNull() ?: allFirst()
                group == null ->
                    allBefore(guideNumber, playlistId, sortOrder, id, 1).firstOrNull() ?: allLast()
                direction >= 0 ->
                    groupAfter(group.playlistId, group.title, groupRank, number, id, 1).firstOrNull()
                        ?: groupFirst(group.playlistId, group.title)
                else ->
                    groupBefore(group.playlistId, group.title, groupRank, number, id, 1).firstOrNull()
                        ?: groupLast(group.playlistId, group.title)
            }
        }

    /**
     * Up to [each] visible channels before and after [current] within [group] (null = all), in the
     * scope's order and without wrapping, with [current] between them (also when it is not in
     * [group]). The player's channel row uses this so it never loads a whole group.
     */
    open suspend fun around(current: ChannelEntity, group: ChannelScope.Group?, each: Int): List<ChannelEntity> =
        with(current) {
            if (group == null) {
                allBefore(guideNumber, playlistId, sortOrder, id, each).reversed() + current +
                    allAfter(guideNumber, playlistId, sortOrder, id, each)
            } else {
                groupBefore(group.playlistId, group.title, groupRank, number, id, each).reversed() + current +
                    groupAfter(group.playlistId, group.title, groupRank, number, id, each)
            }
        }

    // Row-value comparison `(a, b) > (x, y)` needs SQLite 3.15 (API 26+); minSdk is 25, so it is spelled out.
    @Query(
        """SELECT * FROM channels WHERE visible = 1
           AND (guideNumber > :n OR (guideNumber = :n AND (playlistId > :p OR (playlistId = :p
                AND (sortOrder > :s OR (sortOrder = :s AND id > :id))))))
           ORDER BY guideNumber, playlistId, sortOrder, id LIMIT :limit""",
    )
    protected abstract suspend fun allAfter(n: Int, p: Long, s: Int, id: Long, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels WHERE visible = 1
           AND (guideNumber < :n OR (guideNumber = :n AND (playlistId < :p OR (playlistId = :p
                AND (sortOrder < :s OR (sortOrder = :s AND id < :id))))))
           ORDER BY guideNumber DESC, playlistId DESC, sortOrder DESC, id DESC LIMIT :limit""",
    )
    protected abstract suspend fun allBefore(n: Int, p: Long, s: Int, id: Long, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE visible = 1 ORDER BY guideNumber, playlistId, sortOrder, id LIMIT 1")
    protected abstract suspend fun allFirst(): ChannelEntity?

    @Query(
        """SELECT * FROM channels WHERE visible = 1
           ORDER BY guideNumber DESC, playlistId DESC, sortOrder DESC, id DESC LIMIT 1""",
    )
    protected abstract suspend fun allLast(): ChannelEntity?

    @Query(
        """SELECT * FROM channels WHERE visible = 1 AND playlistId = :playlistId AND groupTitle = :title
           AND (groupRank > :rank OR (groupRank = :rank AND (number > :n OR (number = :n AND id > :id))))
           ORDER BY groupRank, number, id LIMIT :limit""",
    )
    protected abstract suspend fun groupAfter(playlistId: Long, title: String, rank: Int, n: Int, id: Long, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels WHERE visible = 1 AND playlistId = :playlistId AND groupTitle = :title
           AND (groupRank < :rank OR (groupRank = :rank AND (number < :n OR (number = :n AND id < :id))))
           ORDER BY groupRank DESC, number DESC, id DESC LIMIT :limit""",
    )
    protected abstract suspend fun groupBefore(playlistId: Long, title: String, rank: Int, n: Int, id: Long, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT * FROM channels WHERE visible = 1 AND playlistId = :playlistId AND groupTitle = :title
           ORDER BY groupRank, number, id LIMIT 1""",
    )
    protected abstract suspend fun groupFirst(playlistId: Long, title: String): ChannelEntity?

    @Query(
        """SELECT * FROM channels WHERE visible = 1 AND playlistId = :playlistId AND groupTitle = :title
           ORDER BY groupRank DESC, number DESC, id DESC LIMIT 1""",
    )
    protected abstract suspend fun groupLast(playlistId: Long, title: String): ChannelEntity?

    // --- scope support (ScopedChannels) ---

    /** Whether the playlist group has at least one visible channel. */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM channels WHERE visible = 1 AND playlistId = :playlistId
           AND groupTitle = :title)""",
    )
    abstract suspend fun hasVisibleGroup(playlistId: Long, title: String): Boolean

    /** The first playlist (in the order added) with a visible group titled [title]; null when none. */
    @Query("SELECT playlistId FROM channels WHERE visible = 1 AND groupTitle = :title ORDER BY playlistId LIMIT 1")
    abstract suspend fun firstPlaylistWithGroup(title: String): Long?

    /** The group's custom name; null when it isn't renamed. */
    @Query("SELECT customName FROM group_prefs WHERE playlistId = :playlistId AND groupTitle = :title")
    abstract suspend fun groupCustomName(playlistId: Long, title: String): String?

    /** Whether channel row [id] exists and is visible. */
    @Query("SELECT EXISTS(SELECT 1 FROM channels WHERE id = :id AND visible = 1)")
    abstract suspend fun isVisible(id: Long): Boolean

    // --- Settings editor (hidden channels and groups included) ---

    /** Every group of [playlistId], hidden ones too, in the guide's group order; [EditorGroupRow.count] counts all. */
    @Query(
        """SELECT c.groupTitle AS title, COALESCE(gp.customName, c.groupTitle) AS label,
                  COALESCE(gp.hidden, 0) AS hidden, (gp.customName IS NOT NULL) AS renamed,
                  COUNT(*) AS count, COALESCE(gp.position, MIN(c.groupOrder)) AS `order`,
                  COALESCE(gp.sortMode, 'PLAYLIST') AS sortMode
           FROM channels c
           LEFT JOIN group_prefs gp ON gp.playlistId = c.playlistId AND gp.groupTitle = c.groupTitle
           WHERE c.playlistId = :playlistId
           GROUP BY c.groupTitle
           ORDER BY gp.position IS NULL, gp.position, MIN(c.groupOrder), title""",
    )
    abstract fun editorGroups(playlistId: Long): Flow<List<EditorGroupRow>>

    /** Every channel of one group of [playlistId], hidden ones too, in the group order. */
    @Query(
        """SELECT c.*, COALESCE(cp.hidden, 0) AS hidden
           FROM channels c
           LEFT JOIN channel_prefs cp ON cp.playlistId = c.playlistId AND cp.channelKey = c.`key`
           WHERE c.playlistId = :playlistId AND c.groupTitle = :title
           ORDER BY c.groupRank, c.number, c.id""",
    )
    abstract fun editorChannels(playlistId: Long, title: String): PagingSource<Int, EditorChannelRow>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortOrder")
    abstract suspend fun channelsOf(playlistId: Long): List<ChannelEntity>

    /**
     * Stores the matcher's guide id and re-derives `guideEpgId`: a manual override in
     * `channel_prefs` always wins, so an EPG commit never loses a manual mapping.
     */
    @Query(
        """UPDATE channels SET epgChannelId = :epgChannelId,
           guideEpgId = COALESCE((SELECT epgOverride FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`), :epgChannelId)
           WHERE id = :id""",
    )
    abstract suspend fun setEpgChannelId(id: Long, epgChannelId: String?)

    @Query("SELECT id, tvgId, tvgName, name, epgChannelId FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun epgRows(playlistId: Long): List<ChannelEpgRow>

    /** Channel count per playlist (playlists without channels are absent). */
    @Query("SELECT playlistId, COUNT(*) AS count FROM channels GROUP BY playlistId")
    abstract fun countsByPlaylist(): Flow<List<PlaylistChannelCount>>

    /** Channels of [playlistId] with a guide id in use (matched or manual), hidden ones included. */
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND guideEpgId IS NOT NULL")
    abstract suspend fun countWithEpg(playlistId: Long): Int

    // --- import support (used inside the import transaction) ---

    @Query("SELECT id, `key`, epgChannelId FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun keyIds(playlistId: Long): List<ChannelKeyId>

    @Insert
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    abstract suspend fun updateAll(channels: List<ChannelEntity>)

    // The two renumbering writes below keep `guideNumber` and `groupRank` mirroring `number` (their
    // unedited defaults, as when the row was built), so rows stay consistent until ChannelOverlay
    // re-derives them in the same import transaction. SET expressions read the old row values.

    /** Marks channels holding a duplicated preferred number as fallback: number = -(1-based position). */
    @Query(
        """UPDATE channels SET number = -(sortOrder + 1), guideNumber = -(sortOrder + 1), groupRank = -(sortOrder + 1)
           WHERE playlistId = :playlistId AND number IN (:numbers)""",
    )
    abstract suspend fun demoteToFallback(playlistId: Long, numbers: List<Int>)

    /**
     * Turns provisional fallback numbers in [lowest]..[highest] (each -(position)) into
     * `offset + position`.
     */
    @Query(
        """UPDATE channels SET number = :offset - number, guideNumber = :offset - number, groupRank = :offset - number
           WHERE playlistId = :playlistId AND number BETWEEN :lowest AND :highest""",
    )
    abstract suspend fun assignFallbackRun(playlistId: Long, lowest: Int, highest: Int, offset: Int)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)
}

/** Max epg ids per [ProgrammeDao.inWindow] call (SQLite allows 999 bound variables on API 25–30). */
const val MAX_WINDOW_IDS = 900

/**
 * Hides rows of a programme generation still being written (see [EpgPendingEntity]).
 * Must start with the bare column `generation`: joins qualify it by prefixing (`p.$COMMITTED`).
 */
internal const val COMMITTED = "generation NOT IN (SELECT generation FROM epg_pending)"

@Dao
abstract class ProgrammeDao {
    /**
     * Committed programmes overlapping [from, to) (`stop > from AND start < to`), ordered by channel
     * and start. [epgIds] must hold at most [MAX_WINDOW_IDS] ids (pass only the visible rows).
     * Rows of an in-progress refresh are never returned, and unchanged results are not re-emitted.
     */
    open fun inWindow(epgIds: List<String>, from: Instant, to: Instant): Flow<List<ProgrammeEntity>> {
        require(epgIds.size <= MAX_WINDOW_IDS) { "At most $MAX_WINDOW_IDS epg ids per query, got ${epgIds.size}" }
        return inWindowQuery(epgIds, from, to).distinctUntilChanged()
    }

    @Query(
        """SELECT * FROM programmes WHERE epgChannelId IN (:epgIds) AND stop > :from AND start < :to
           AND $COMMITTED ORDER BY epgChannelId, start""",
    )
    protected abstract fun inWindowQuery(epgIds: List<String>, from: Instant, to: Instant): Flow<List<ProgrammeEntity>>

    /** End of the last committed programme (null when there is no guide data). */
    @Query("SELECT MAX(stop) FROM programmes WHERE $COMMITTED")
    abstract suspend fun latestStop(): Instant?

    /** The committed programme airing at [at] (if any) and the one after it; at most 2. */
    @Query("SELECT * FROM programmes WHERE epgChannelId = :epgId AND stop > :at AND $COMMITTED ORDER BY start LIMIT 2")
    abstract suspend fun nowAndNext(epgId: String, at: Instant): List<ProgrammeEntity>

    // --- refresh support ---

    @Insert
    abstract suspend fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("SELECT MAX(generation) FROM programmes")
    abstract suspend fun maxGeneration(): Long?

    @Query("DELETE FROM programmes WHERE generation = :generation")
    abstract suspend fun deleteGeneration(generation: Long)

    @Query("DELETE FROM programmes WHERE generation < :generation AND epgChannelId IN (:epgIds)")
    abstract suspend fun deleteOlderGenerations(epgIds: List<String>, generation: Long)

    /** Drops [generation]'s rows for [epgIds] (a failed source's); returns how many were deleted. */
    @Query("DELETE FROM programmes WHERE generation = :generation AND epgChannelId IN (:epgIds)")
    abstract suspend fun deleteGenerationFor(generation: Long, epgIds: List<String>): Int

    @Query("DELETE FROM programmes WHERE stop < :cutoff")
    abstract suspend fun deleteEndedBefore(cutoff: Instant)

    @Query("INSERT INTO epg_pending (generation) VALUES (:generation)")
    abstract suspend fun insertPending(generation: Long)

    @Query("DELETE FROM epg_pending WHERE generation = :generation")
    abstract suspend fun deletePending(generation: Long)

    @Query("SELECT generation FROM epg_pending")
    abstract suspend fun pendingGenerations(): List<Long>

    @Query("DELETE FROM programmes")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM epg_pending")
    abstract suspend fun deleteAllPending()

    /** Removes [generation]'s rows and its pending marker. Call inside a transaction. */
    open suspend fun discard(generation: Long) {
        deleteGeneration(generation)
        deletePending(generation)
    }
}

@Dao
interface EpgStatusDao {
    @Query("SELECT * FROM epg_status WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<EpgStatusEntity?>

    @Query("SELECT * FROM epg_status WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): EpgStatusEntity?

    @Upsert
    suspend fun upsert(status: EpgStatusEntity)

    @Query("DELETE FROM epg_status")
    suspend fun deleteAll()
}

/**
 * Whole-table reads and the replace step for copying a setup to another TV. The writes are meant
 * to run inside one transaction (the caller's), so a failure anywhere leaves the old rows.
 */
@Dao
abstract class SetupDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    abstract suspend fun playlists(): List<PlaylistEntity>

    /** Whether there is any playlist, without reading one. */
    @Query("SELECT EXISTS(SELECT 1 FROM playlists)")
    abstract suspend fun hasPlaylists(): Boolean

    @Query("SELECT * FROM epg_sources ORDER BY playlistId, position, id")
    abstract suspend fun epgSources(): List<EpgSourceEntity>

    @Query("SELECT * FROM channel_prefs ORDER BY playlistId, channelKey")
    abstract suspend fun channelPrefs(): List<ChannelPrefsEntity>

    @Query("SELECT * FROM group_prefs ORDER BY playlistId, groupTitle")
    abstract suspend fun groupPrefs(): List<GroupPrefsEntity>

    /** In the user's order. */
    @Query("SELECT * FROM favourites ORDER BY position")
    abstract suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM history ORDER BY watchedAt DESC")
    abstract suspend fun history(): List<HistoryEntity>

    /** Everything of every playlist goes with it (ON DELETE CASCADE). */
    @Query("DELETE FROM playlists")
    abstract suspend fun deleteAllPlaylists()

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET url = :url WHERE id = :id")
    abstract suspend fun setUrl(id: Long, url: String)

    @Insert
    abstract suspend fun insertEpgSources(rows: List<EpgSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChannelPrefs(rows: List<ChannelPrefsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertGroupPrefs(rows: List<GroupPrefsEntity>)

    /** A repeated channel keeps its first place. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFavourites(rows: List<FavouriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHistory(rows: List<HistoryEntity>)
}
