package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query

/** Projection: an enabled playlist and its highest source number (0 when it has no channels). */
data class PlaylistMaxNumber(val playlistId: Long, val maxNumber: Int)

/** Projection: one channel of a group, with what its group sort needs. */
data class ChannelRankRow(
    val id: Long,
    val key: String,
    val number: Int,
    val name: String,
    /** From `channel_prefs` (the authoritative copy). */
    val customName: String?,
    /** From `channel_prefs`; used by [GroupSort.CUSTOM]. */
    val position: Int?,
    val groupRank: Int,
    val visible: Boolean,
) {
    val displayName: String get() = customName ?: name
}

/** Projection: one group of a playlist, with what group ordering needs. */
data class GroupOrderRow(
    val groupTitle: String,
    /** `MIN(groupOrder)`: where the group first appears in the source. */
    val groupOrder: Int,
    /** From `group_prefs` (null until a group of the playlist was moved). */
    val position: Int?,
    val hidden: Boolean,
    /** Whether any of the group's channels is visible. */
    val anyVisible: Boolean,
) {
    /** Listed in the guide: not hidden and with at least one visible channel. */
    val shown: Boolean get() = !hidden && anyVisible
}

/**
 * Writes the derived columns of `channels` from `playlists`, `channel_prefs` and `group_prefs`.
 * Used by [io.github.loopholetv.data.repo.ChannelOverlay] only; callers hold the transaction.
 */
@Dao
abstract class OverlayDao {
    /**
     * Step 1 of a playlist re-derive: every row gets the playlist/group-level values and the
     * "no channel edit" defaults (`groupRank = number`, as for [GroupSort.PLAYLIST]).
     */
    @Query(
        """UPDATE channels SET
           visible = (SELECT enabled FROM playlists WHERE id = :playlistId)
               AND NOT COALESCE((SELECT hidden FROM group_prefs
                   WHERE playlistId = :playlistId AND groupTitle = channels.groupTitle), 0),
           customName = NULL, customLogo = NULL, epgShiftMinutes = 0, guideEpgId = epgChannelId,
           groupRank = number
           WHERE playlistId = :playlistId""",
    )
    abstract suspend fun resetDerived(playlistId: Long)

    /** Step 2: rows that have a `channel_prefs` row take its values (after [resetDerived]). */
    @Query(
        """UPDATE channels SET
           visible = visible AND NOT (SELECT hidden FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           customName = (SELECT customName FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           customLogo = (SELECT customLogo FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           epgShiftMinutes = (SELECT epgShiftMinutes FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           guideEpgId = COALESCE((SELECT epgOverride FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`), epgChannelId)
           WHERE playlistId = :playlistId
             AND `key` IN (SELECT channelKey FROM channel_prefs WHERE playlistId = :playlistId)""",
    )
    abstract suspend fun applyChannelPrefs(playlistId: Long)

    /** Re-derives one channel's visibility and channel-edit columns (not its `groupRank`). */
    @Query(
        """UPDATE channels SET
           visible = (SELECT enabled FROM playlists WHERE id = channels.playlistId)
               AND NOT COALESCE((SELECT hidden FROM group_prefs
                   WHERE playlistId = channels.playlistId AND groupTitle = channels.groupTitle), 0)
               AND NOT COALESCE((SELECT hidden FROM channel_prefs
                   WHERE playlistId = channels.playlistId AND channelKey = channels.`key`), 0),
           customName = (SELECT customName FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           customLogo = (SELECT customLogo FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`),
           epgShiftMinutes = COALESCE((SELECT epgShiftMinutes FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`), 0),
           guideEpgId = COALESCE((SELECT epgOverride FROM channel_prefs
               WHERE playlistId = channels.playlistId AND channelKey = channels.`key`), epgChannelId)
           WHERE playlistId = :playlistId AND `key` = :key""",
    )
    abstract suspend fun applyChannel(playlistId: Long, key: String)

    /** Titles of [playlistId]'s groups whose sort is not [GroupSort.PLAYLIST]. */
    @Query("SELECT groupTitle FROM group_prefs WHERE playlistId = :playlistId AND sortMode != 'PLAYLIST'")
    abstract suspend fun sortedGroupTitles(playlistId: Long): List<String>

    /** [GroupSort.PLAYLIST]: a group's rank is the channel number. */
    @Query("UPDATE channels SET groupRank = number WHERE playlistId = :playlistId AND groupTitle = :groupTitle AND groupRank != number")
    abstract suspend fun rankByNumber(playlistId: Long, groupTitle: String)

    @Query(
        """SELECT c.id, c.`key`, c.number, c.name, cp.customName, cp.position, c.groupRank, c.visible
           FROM channels c LEFT JOIN channel_prefs cp ON cp.playlistId = c.playlistId AND cp.channelKey = c.`key`
           WHERE c.playlistId = :playlistId AND c.groupTitle = :groupTitle""",
    )
    abstract suspend fun rankRows(playlistId: Long, groupTitle: String): List<ChannelRankRow>

    @Query("UPDATE channels SET groupRank = :rank WHERE id = :id")
    abstract suspend fun setRank(id: Long, rank: Int)

    /** Every group present in [playlistId]'s channels (vanished groups' prefs are not listed). */
    @Query(
        """SELECT c.groupTitle, MIN(c.groupOrder) AS groupOrder, gp.position, COALESCE(gp.hidden, 0) AS hidden,
           MAX(c.visible) AS anyVisible
           FROM channels c LEFT JOIN group_prefs gp ON gp.playlistId = c.playlistId AND gp.groupTitle = c.groupTitle
           WHERE c.playlistId = :playlistId GROUP BY c.groupTitle""",
    )
    abstract suspend fun groupRows(playlistId: Long): List<GroupOrderRow>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND `key` = :key")
    abstract suspend fun channelByKey(playlistId: Long, key: String): ChannelEntity?

    @Query(
        """SELECT p.id AS playlistId, COALESCE(MAX(c.number), 0) AS maxNumber
           FROM playlists p LEFT JOIN channels c ON c.playlistId = p.id
           WHERE p.enabled = 1 GROUP BY p.id ORDER BY p.id""",
    )
    abstract suspend fun enabledMaxNumbers(): List<PlaylistMaxNumber>

    @Query("SELECT id FROM playlists ORDER BY id")
    abstract suspend fun playlistIds(): List<Long>

    @Query("UPDATE channels SET guideNumber = number + :offset WHERE playlistId = :playlistId AND guideNumber != number + :offset")
    abstract suspend fun offsetNumbers(playlistId: Long, offset: Int)

    /** A turned-off playlist keeps its own numbers. */
    @Query(
        """UPDATE channels SET guideNumber = number
           WHERE guideNumber != number AND playlistId IN (SELECT id FROM playlists WHERE enabled = 0)""",
    )
    abstract suspend fun resetDisabledNumbers()
}
