package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelRankRow
import io.github.loopholetv.data.db.GroupSort

/**
 * Guide numbers with several playlists: enabled playlists in id order; the first gets offset 0,
 * each next one the previous offset plus the previous playlist's highest source number.
 */
fun numberOffsets(enabled: List<Pair<Long, Int>>): Map<Long, Int> {
    val offsets = LinkedHashMap<Long, Int>()
    var next = 0
    for ((playlistId, max) in enabled) {
        offsets[playlistId] = next
        next += max
    }
    return offsets
}

/** Order of a [GroupSort.NAME] group: shown name ignoring case, then number. */
internal val BY_NAME: Comparator<ChannelRankRow> =
    compareBy<ChannelRankRow, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.number }.thenBy { it.id }

/** Order of a [GroupSort.CUSTOM] group: manual position (channels without one last), then number. */
internal val BY_POSITION: Comparator<ChannelRankRow> =
    compareBy<ChannelRankRow>({ it.position == null }, { it.position }, { it.number }, { it.id })

/**
 * Writes the user's edits (`channel_prefs`, `group_prefs`, `playlists.enabled`) into the derived
 * columns of `channels`. Every edit lives in the side tables; these columns are only a copy, so an
 * import may overwrite them as long as [applyPlaylist] and [renumber] run before it commits.
 *
 * Except [refreshAll], callers hold the transaction.
 */
class ChannelOverlay(private val db: AppDatabase) {
    private val dao = db.overlayDao()
    private val groupPrefs = db.groupPrefsDao()

    /**
     * Re-derives every channel of [playlistId]: visibility (playlist on, neither channel nor group
     * hidden), custom name/logo, shift, guide id (manual override wins) and every group's rank.
     * Hidden channels keep their number.
     */
    suspend fun applyPlaylist(playlistId: Long) {
        dao.resetDerived(playlistId)
        dao.applyChannelPrefs(playlistId)
        for (title in dao.sortedGroupTitles(playlistId)) rankGroup(playlistId, title)
    }

    /** Re-derives one channel's visibility and edit columns (not its rank; see [rankGroup]). */
    suspend fun applyChannel(playlistId: Long, key: String) = dao.applyChannel(playlistId, key)

    /** `guideNumber = number + offset` for every enabled playlist; turned-off ones keep `number`. */
    suspend fun renumber() {
        val offsets = numberOffsets(dao.enabledMaxNumbers().map { it.playlistId to it.maxNumber })
        for ((playlistId, offset) in offsets) dao.offsetNumbers(playlistId, offset)
        dao.resetDisabledNumbers()
    }

    /** Recomputes `groupRank` for one group under its sort mode. */
    suspend fun rankGroup(playlistId: Long, groupTitle: String) {
        val sort = groupPrefs.get(playlistId, groupTitle)?.sortMode ?: GroupSort.PLAYLIST
        val comparator = when (sort) {
            GroupSort.PLAYLIST -> return dao.rankByNumber(playlistId, groupTitle)
            GroupSort.NAME -> BY_NAME
            GroupSort.CUSTOM -> BY_POSITION
        }
        dao.rankRows(playlistId, groupTitle).sortedWith(comparator).forEachIndexed { i, row ->
            val rank = i + 1
            if (row.groupRank != rank) dao.setRank(row.id, rank)
        }
    }

    /** Every playlist, then [renumber], in one transaction (e.g. after seeding). */
    suspend fun refreshAll() = db.withTransaction {
        for (id in dao.playlistIds()) applyPlaylist(id)
        renumber()
    }
}
