package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.flow.Flow

/**
 * SQL for "the PIN is currently entered". A missing `lock_state` row counts as locked, so a
 * visibility expression never turns NULL.
 *
 * Room only sees the tables named in a `@Query`. Any `@RawQuery`, paging source or hand-listed set of
 * observed tables that uses [FILM_VISIBLE], [SHOW_VISIBLE] or [CHANNEL_VISIBLE] must also observe
 * `lock_state`, and the prefs tables those read (`group_prefs`, `channel_prefs`, `show_prefs`), or it
 * keeps showing locked rows after a relock.
 */
internal const val LOCK_OPEN = "COALESCE((SELECT unlocked FROM lock_state WHERE id = 1), 0)"

/** How many things are locked, with films counted apart from live channels. */
data class LockCounts(val groups: Int, val channels: Int, val films: Int, val shows: Int)

@Dao
interface LockDao {
    @Query("INSERT OR REPLACE INTO lock_state (id, unlocked) VALUES (1, :unlocked)")
    suspend fun setUnlocked(unlocked: Boolean)

    @Query("SELECT $LOCK_OPEN")
    fun unlocked(): Flow<Boolean>

    @Query("SELECT * FROM show_prefs WHERE playlistId = :playlistId AND showKey = :showKey")
    suspend fun showPrefs(playlistId: Long, showKey: String): ShowPrefsEntity?

    @Upsert
    suspend fun upsertShowPrefs(row: ShowPrefsEntity)

    @Query("DELETE FROM show_prefs WHERE playlistId = :playlistId AND showKey = :showKey")
    suspend fun deleteShowPrefs(playlistId: Long, showKey: String)

    /** The show keys of [playlistId] with their own lock. */
    @Query("SELECT showKey FROM show_prefs WHERE playlistId = :playlistId AND locked = 1")
    suspend fun lockedShowKeys(playlistId: Long): List<String>

    @Query(
        """SELECT
            (SELECT COUNT(*) FROM group_prefs WHERE locked = 1) AS `groups`,
            (SELECT COUNT(*) FROM channel_prefs c WHERE c.locked = 1 AND NOT EXISTS
                (SELECT 1 FROM vod_films f WHERE f.playlistId = c.playlistId AND f.`key` = c.channelKey)) AS channels,
            (SELECT COUNT(*) FROM channel_prefs c WHERE c.locked = 1 AND EXISTS
                (SELECT 1 FROM vod_films f WHERE f.playlistId = c.playlistId AND f.`key` = c.channelKey)) AS films,
            (SELECT COUNT(*) FROM show_prefs WHERE locked = 1) AS shows""",
    )
    fun counts(): Flow<LockCounts>

    /** Channels and films (a film's key is its channel key) with their own lock, as (playlist, key). */
    @Query("SELECT playlistId, channelKey AS `key` FROM channel_prefs WHERE locked = 1")
    fun lockedChannels(): Flow<List<ChannelRef>>

    /** Groups and categories with their own lock, as (playlist, title). */
    @Query("SELECT playlistId, groupTitle AS `key` FROM group_prefs WHERE locked = 1")
    fun lockedGroups(): Flow<List<ChannelRef>>

    /** Shows with their own lock, as (playlist, show key). */
    @Query("SELECT playlistId, showKey AS `key` FROM show_prefs WHERE locked = 1")
    fun lockedShows(): Flow<List<ChannelRef>>

    /** Whether channel [key] has its own lock, or sits in a locked group. Ignores whether the PIN is entered. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = :playlistId AND c.channelKey = :key AND c.locked = 1)
           OR EXISTS (SELECT 1 FROM channels ch JOIN group_prefs g ON g.playlistId = ch.playlistId
                AND g.groupTitle = ch.groupTitle AND g.locked = 1
                WHERE ch.playlistId = :playlistId AND ch.`key` = :key)""",
    )
    suspend fun channelLocked(playlistId: Long, key: String): Boolean

    /** Whether any channel named [name] (as shown, or as the playlist names it) is locked (its own lock or its group's), for items that only store a name. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM channels ch WHERE (COALESCE(ch.customName, ch.name) = :name OR ch.name = :name) AND (
                EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = ch.playlistId AND c.channelKey = ch.`key` AND c.locked = 1)
                OR EXISTS (SELECT 1 FROM group_prefs g WHERE g.playlistId = ch.playlistId AND g.groupTitle = ch.groupTitle AND g.locked = 1)))""",
    )
    suspend fun channelNameLocked(name: String): Boolean

    /** Whether film [key] has its own lock, or its category is locked. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = :playlistId AND c.channelKey = :key AND c.locked = 1)
           OR EXISTS (SELECT 1 FROM vod_films f JOIN group_prefs g ON g.playlistId = f.playlistId
                AND g.groupTitle = f.category AND g.locked = 1
                WHERE f.playlistId = :playlistId AND f.`key` = :key)""",
    )
    suspend fun filmLocked(playlistId: Long, key: String): Boolean

    /** Whether show [showKey] has its own lock, or its group is locked. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM show_prefs sp WHERE sp.playlistId = :playlistId AND sp.showKey = :showKey AND sp.locked = 1)
           OR EXISTS (SELECT 1 FROM vod_shows s JOIN group_prefs g ON g.playlistId = s.playlistId
                AND g.groupTitle = s.groupTitle AND g.locked = 1
                WHERE s.playlistId = :playlistId AND s.`key` = :showKey)""",
    )
    suspend fun showLocked(playlistId: Long, showKey: String): Boolean

    /** The show an episode belongs to, or null when the episode is gone. */
    @Query("SELECT showKey FROM vod_episodes WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun showKeyOfEpisode(playlistId: Long, key: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM group_prefs WHERE locked = 1) OR EXISTS(SELECT 1 FROM channel_prefs WHERE locked = 1) OR EXISTS(SELECT 1 FROM show_prefs WHERE locked = 1)")
    suspend fun hasAnyLock(): Boolean

    /** Removes every lock, keeping other edits: rows left holding nothing are deleted. */
    @Transaction
    suspend fun clearAllLocks() {
        unlockChannels()
        unlockGroups()
        deleteAllShowPrefs()
        deleteDefaultChannelPrefs()
        deleteDefaultGroupPrefs()
    }

    @Query("UPDATE channel_prefs SET locked = 0 WHERE locked = 1")
    suspend fun unlockChannels()

    @Query("UPDATE group_prefs SET locked = 0 WHERE locked = 1")
    suspend fun unlockGroups()

    @Query("DELETE FROM show_prefs")
    suspend fun deleteAllShowPrefs()

    @Query(
        """DELETE FROM channel_prefs WHERE hidden = 0 AND customName IS NULL AND customLogo IS NULL
           AND epgOverride IS NULL AND epgShiftMinutes = 0 AND position IS NULL AND videoDecoder IS NULL
           AND audioDecoder IS NULL AND externalPlayer IS NULL AND aspectMode IS NULL AND locked = 0""",
    )
    suspend fun deleteDefaultChannelPrefs()

    @Query(
        """DELETE FROM group_prefs WHERE hidden = 0 AND customName IS NULL AND position IS NULL
           AND sortMode = 'PLAYLIST' AND locked = 0""",
    )
    suspend fun deleteDefaultGroupPrefs()

    /**
     * Re-derives `channels.visible` ([CHANNEL_VISIBLE], as [OverlayDao.applyChannel] writes it) for the channels
     * that sit in a locked group or have a locked prefs row: the only ones a lock change can affect.
     */
    @Query(
        """UPDATE channels SET
           visible = $CHANNEL_VISIBLE
           WHERE EXISTS (SELECT 1 FROM group_prefs g WHERE g.playlistId = channels.playlistId
                   AND g.groupTitle = channels.groupTitle AND g.locked = 1)
              OR EXISTS (SELECT 1 FROM channel_prefs c WHERE c.playlistId = channels.playlistId
                   AND c.channelKey = channels.`key` AND c.locked = 1)""",
    )
    suspend fun rederiveLocked()
}

/**
 * Whether channel [channelKey] is locked: its own lock or its group's (read from the channel's row). Says
 * nothing about the PIN being entered; callers combine it with the unlock state.
 */
suspend fun LockDao.isChannelLocked(playlistId: Long, channelKey: String): Boolean = channelLocked(playlistId, channelKey)

/**
 * Whether channel [channelKey] is locked right now: it has a lock and the PIN is not entered ([unlocked] false).
 * A failed lookup counts as locked.
 */
suspend fun LockDao.isChannelLockedNow(unlocked: Boolean, playlistId: Long, channelKey: String): Boolean =
    !unlocked && failClosed { isChannelLocked(playlistId, channelKey) }

/**
 * Whether a film or an episode is locked. A film: its own lock or its category's. An episode: its show's
 * lock or its show's group's. Says nothing about the PIN being entered. Anything else is not locked.
 */
suspend fun LockDao.isVodLocked(kind: VodKind, playlistId: Long, key: String): Boolean = when (kind) {
    VodKind.MOVIE -> filmLocked(playlistId, key)
    VodKind.EPISODE -> showKeyOfEpisode(playlistId, key)?.let { showLocked(playlistId, it) } ?: false
    VodKind.RECORDING -> false
}

/**
 * Runs a lock lookup, counting a failed read as locked so nothing opens on a lookup error. Cancellation
 * still propagates.
 */
internal suspend inline fun failClosed(check: () -> Boolean): Boolean = try {
    check()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    true
}
