package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.github.loopholetv.data.repo.KeyNameGroup

/** Portal channel id → the name and guide id it was imported with (for the portal guide). */
data class PortalChannelRow(val key: String, val name: String, val tvgId: String?)

/**
 * Cross-table upkeep for playlist sources: the channel-key carry-over, programme cleanup after a delete
 * and targeted playlist writes for source edits (so a concurrently written portal token or
 * `lastError` is never clobbered by a whole-row update).
 */
@Dao
abstract class MaintenanceDao {
    @Query("SELECT `key`, name, groupTitle AS `group` FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun keyNameGroups(playlistId: Long): List<KeyNameGroup>

    /** Moves [from]'s edits, favourite and history to [to]. A row already present for [to] wins. */
    @Transaction
    open suspend fun rekey(playlistId: Long, from: String, to: String) {
        rekeyPrefs(playlistId, from, to)
        rekeyFavourites(playlistId, from, to)
        rekeyHistory(playlistId, from, to)
    }

    @Query("UPDATE OR IGNORE channel_prefs SET channelKey = :to WHERE playlistId = :playlistId AND channelKey = :from")
    protected abstract suspend fun rekeyPrefs(playlistId: Long, from: String, to: String)

    @Query("UPDATE OR IGNORE favourites SET channelKey = :to WHERE playlistId = :playlistId AND channelKey = :from")
    protected abstract suspend fun rekeyFavourites(playlistId: Long, from: String, to: String)

    @Query("UPDATE OR IGNORE history SET channelKey = :to WHERE playlistId = :playlistId AND channelKey = :from")
    protected abstract suspend fun rekeyHistory(playlistId: Long, from: String, to: String)

    /**
     * Programmes no channel uses any more. Generations still being written (`epg_pending`) are
     * kept: another playlist's EPG refresh may be storing rows its channels don't own yet.
     */
    @Query(
        """DELETE FROM programmes
           WHERE epgChannelId NOT IN (SELECT guideEpgId FROM channels WHERE guideEpgId IS NOT NULL)
           AND $COMMITTED""",
    )
    abstract suspend fun deleteUnreferencedProgrammes()

    /**
     * Saves an edited source (the type may change). The portal session is cleared (a new address
     * or MAC needs a new handshake) and [PlaylistEntity.rekeyPending] is set for the key carry-over.
     */
    @Query(
        """UPDATE playlists SET type = :type, url = :url,
               xtreamServer = :xtreamServer, xtreamUser = :xtreamUser, xtreamPass = :xtreamPass,
               mac = :mac, portalLogin = :login, portalPassword = :password, portalSerial = :serial,
               portalDeviceId = :deviceId, portalDeviceId2 = :deviceId2, portalSignature = :signature,
               portalEndpoint = NULL, portalToken = NULL, rekeyPending = 1
           WHERE id = :id""",
    )
    abstract suspend fun setSource(
        id: Long,
        type: PlaylistType,
        url: String?,
        xtreamServer: String?,
        xtreamUser: String?,
        xtreamPass: String?,
        mac: String?,
        login: String?,
        password: String?,
        serial: String?,
        deviceId: String?,
        deviceId2: String?,
        signature: String?,
    )

    /** The automatic guide match moves with a re-keyed channel (unless the new row already has one). */
    @Query(
        """UPDATE channels SET epgChannelId = :epgChannelId
           WHERE playlistId = :playlistId AND `key` = :key AND epgChannelId IS NULL""",
    )
    abstract suspend fun carryEpgMatch(playlistId: Long, key: String, epgChannelId: String)

    /** Keys with edits, a favourite or history: the only ones a re-key has to move. */
    @Query(
        """SELECT channelKey FROM channel_prefs WHERE playlistId = :playlistId
           UNION SELECT channelKey FROM favourites WHERE playlistId = :playlistId
           UNION SELECT channelKey FROM history WHERE playlistId = :playlistId""",
    )
    abstract suspend fun keysWithUserData(playlistId: Long): List<String>

    /** How many playlists read the local file [url] (a shared file keeps its grant until the last goes). */
    @Query("SELECT COUNT(*) FROM playlists WHERE type = :type AND url = :url")
    abstract suspend fun countUsing(type: PlaylistType, url: String): Int

    @Query("SELECT `key`, name, tvgId FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun portalChannels(playlistId: Long): List<PortalChannelRow>
}
