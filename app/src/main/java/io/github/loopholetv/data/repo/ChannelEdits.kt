package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ChannelRankRow
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.GroupOrderRow
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice

/** Guide time shift step. */
const val SHIFT_STEP_MINUTES = 30

/** Every allowed guide time shift: −12 h … +12 h in 30-minute steps; 0 is "Off". */
val SHIFT_CHOICES: List<Int> = (-24..24).map { it * SHIFT_STEP_MINUTES }

/**
 * The user's channel, group and playlist edits. Each call is one transaction: it writes the pref
 * row (deleting it once it holds no edit) and re-derives what the edit changes through [overlay].
 */
class ChannelEdits(private val db: AppDatabase, private val overlay: ChannelOverlay) {
    private val channelPrefs = db.channelPrefsDao()
    private val groupPrefs = db.groupPrefsDao()
    private val overlayDao = db.overlayDao()

    suspend fun setHidden(ref: ChannelRef, hidden: Boolean) = editChannel(ref) { it.copy(hidden = hidden) }

    /** A blank name, or the source name, restores the original. */
    suspend fun rename(ref: ChannelRef, name: String?) = db.withTransaction {
        val channel = overlayDao.channelByKey(ref.playlistId, ref.key)
        val custom = name.blankToNull()?.takeIf { it != channel?.name }
        val changed = channelPrefs.get(ref.playlistId, ref.key)?.customName != custom
        writeChannelPrefs(ref) { it.copy(customName = custom) }
        overlay.applyChannel(ref.playlistId, ref.key)
        if (changed && channel != null && sortOf(ref.playlistId, channel.groupTitle) == GroupSort.NAME) {
            overlay.rankGroup(ref.playlistId, channel.groupTitle)
        }
    }

    /** A blank address restores the original logo. */
    suspend fun setLogo(ref: ChannelRef, url: String?) = editChannel(ref) { it.copy(customLogo = url.blankToNull()) }

    /** A blank id (or null, "Automatic") goes back to the matcher's guide id. */
    suspend fun setEpgOverride(ref: ChannelRef, epgId: String?) =
        editChannel(ref) { it.copy(epgOverride = epgId.blankToNull()) }

    /** [minutes] must be one of [SHIFT_CHOICES]. */
    suspend fun setShift(ref: ChannelRef, minutes: Int) {
        require(minutes in SHIFT_CHOICES) { "Unsupported time shift: $minutes" }
        editChannel(ref) { it.copy(epgShiftMinutes = minutes) }
    }

    /** Null follows the playback settings. */
    suspend fun setVideoDecoder(ref: ChannelRef, choice: DecoderChoice?) =
        editPlayback(ref) { it.copy(videoDecoder = choice) }

    /** Null follows the playback settings. */
    suspend fun setAudioDecoder(ref: ChannelRef, choice: DecoderChoice?) =
        editPlayback(ref) { it.copy(audioDecoder = choice) }

    /** A package name, or `"ask"` for the system chooser; blank or null plays the channel here. */
    suspend fun setExternalPlayer(ref: ChannelRef, choice: String?) =
        editPlayback(ref) { it.copy(externalPlayer = choice.blankToNull()) }

    /** [AspectMode.FIT] is the default and is stored as null. */
    suspend fun setAspect(ref: ChannelRef, mode: AspectMode?) =
        editPlayback(ref) { it.copy(aspectMode = mode?.takeIf { m -> m != AspectMode.FIT }) }

    /**
     * Moves [channel] one place up (`direction < 0`) or down within its group, in `groupRank` order.
     * A visible channel swaps with the nearest visible neighbour, so the guide always shows
     * the move. The first move (or one next to a channel without a position, or with the same one) writes the position of
     * every channel of the group in the current order and sets the group to [GroupSort.CUSTOM].
     * Returns false at either end (no wrap) or when the channel is gone.
     */
    suspend fun move(channel: ChannelEntity, direction: Int): Boolean {
        require(direction != 0) { "direction must be non-zero" }
        val step = Integer.signum(direction)
        val playlistId = channel.playlistId
        val group = channel.groupTitle
        return db.withTransaction {
            val rows = overlayDao.rankRows(playlistId, group)
                .sortedWith(compareBy<ChannelRankRow>({ it.groupRank }, { it.number }, { it.id }))
            val from = rows.indexOfFirst { it.key == channel.key }
            if (from < 0) return@withTransaction false
            val to = neighbour(rows.size, from, step) { !rows[from].visible || rows[it].visible }
                ?: return@withTransaction false
            val sort = sortOf(playlistId, group)
            if (sort == GroupSort.CUSTOM && distinctPositions(rows[from].position, rows[to].position)) {
                val a = rows[from]
                val b = rows[to]
                writeChannelPrefs(ChannelRef(playlistId, a.key)) { it.copy(position = b.position) }
                writeChannelPrefs(ChannelRef(playlistId, b.key)) { it.copy(position = a.position) }
            } else {
                val order = rows.toMutableList().also { java.util.Collections.swap(it, from, to) }
                order.forEachIndexed { i, row ->
                    if (row.position != i) writeChannelPrefs(ChannelRef(playlistId, row.key)) { it.copy(position = i) }
                }
                if (sort != GroupSort.CUSTOM) writeGroupPrefs(playlistId, group) { it.copy(sortMode = GroupSort.CUSTOM) }
            }
            overlay.rankGroup(playlistId, group)
            true
        }
    }

    /** Hides or shows every channel of a group (re-derives the playlist). */
    suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean) = db.withTransaction {
        writeGroupPrefs(playlistId, title) { it.copy(hidden = hidden) }
        overlay.applyPlaylist(playlistId)
    }

    /** A blank name, or the original title, restores the original. Channels are unaffected. */
    suspend fun renameGroup(playlistId: Long, title: String, name: String?) = db.withTransaction {
        val custom = name.blankToNull()?.takeIf { it != title }
        writeGroupPrefs(playlistId, title) { it.copy(customName = custom) }
    }

    /**
     * Moves a group one place up or down among the playlist's groups, ordered by position (groups
     * without one last), then by first appearance in the source. A group the guide lists (not hidden,
     * with a visible channel) skips groups it doesn't list. The first move (or one next to a group
     * without a position, or with the same one) writes every group's position.
     */
    suspend fun moveGroup(playlistId: Long, title: String, direction: Int): Boolean {
        require(direction != 0) { "direction must be non-zero" }
        val step = Integer.signum(direction)
        return db.withTransaction {
            val rows = overlayDao.groupRows(playlistId).sortedWith(
                compareBy<GroupOrderRow>({ it.position == null }, { it.position }, { it.groupOrder }, { it.groupTitle }),
            )
            val from = rows.indexOfFirst { it.groupTitle == title }
            if (from < 0) return@withTransaction false
            val to = neighbour(rows.size, from, step) { !rows[from].shown || rows[it].shown }
                ?: return@withTransaction false
            if (distinctPositions(rows[from].position, rows[to].position)) {
                val a = rows[from]
                val b = rows[to]
                writeGroupPrefs(playlistId, a.groupTitle) { it.copy(position = b.position) }
                writeGroupPrefs(playlistId, b.groupTitle) { it.copy(position = a.position) }
            } else {
                val order = rows.toMutableList().also { java.util.Collections.swap(it, from, to) }
                order.forEachIndexed { i, row ->
                    if (row.position != i) writeGroupPrefs(playlistId, row.groupTitle) { it.copy(position = i) }
                }
            }
            true
        }
    }

    suspend fun setGroupSort(playlistId: Long, title: String, sort: GroupSort) = db.withTransaction {
        writeGroupPrefs(playlistId, title) { it.copy(sortMode = sort) }
        overlay.rankGroup(playlistId, title)
    }

    /** Turns a playlist on or off: its channels' visibility and every playlist's numbers follow. */
    suspend fun setPlaylistEnabled(playlistId: Long, enabled: Boolean) = db.withTransaction {
        db.playlistDao().setEnabled(playlistId, enabled)
        overlay.applyPlaylist(playlistId)
        overlay.renumber()
    }

    // --- internals ---

    /** One channel-only edit: write the pref row and re-derive just that channel. */
    private suspend fun editChannel(ref: ChannelRef, change: (ChannelPrefsEntity) -> ChannelPrefsEntity) =
        db.withTransaction {
            writeChannelPrefs(ref, change)
            overlay.applyChannel(ref.playlistId, ref.key)
        }

    /** A playback-only edit: nothing on `channels` derives from it, so nothing is re-derived. */
    private suspend fun editPlayback(ref: ChannelRef, change: (ChannelPrefsEntity) -> ChannelPrefsEntity) =
        db.withTransaction { writeChannelPrefs(ref, change) }

    private suspend fun writeChannelPrefs(ref: ChannelRef, change: (ChannelPrefsEntity) -> ChannelPrefsEntity) {
        val next = change(channelPrefs.get(ref.playlistId, ref.key) ?: ChannelPrefsEntity(ref.playlistId, ref.key))
        if (next.isDefault()) channelPrefs.delete(ref.playlistId, ref.key) else channelPrefs.upsert(next)
    }

    private suspend fun writeGroupPrefs(playlistId: Long, title: String, change: (GroupPrefsEntity) -> GroupPrefsEntity) {
        val next = change(groupPrefs.get(playlistId, title) ?: GroupPrefsEntity(playlistId, title))
        if (next.isDefault()) groupPrefs.delete(playlistId, title) else groupPrefs.upsert(next)
    }

    private suspend fun sortOf(playlistId: Long, title: String): GroupSort =
        groupPrefs.get(playlistId, title)?.sortMode ?: GroupSort.PLAYLIST

    /**
     * Whether a move may just swap the two positions. Equal positions (a channel regrouped into a
     * custom group, or one that returned after the group was re-materialised) would make the swap a
     * no-op, so those, and missing ones, re-materialise the whole order instead.
     */
    private fun distinctPositions(a: Int?, b: Int?): Boolean = a != null && b != null && a != b

    /** The nearest index from [from] in [step] direction that is [eligible], or null past either end. */
    private inline fun neighbour(size: Int, from: Int, step: Int, eligible: (Int) -> Boolean): Int? {
        var i = from + step
        while (i in 0 until size) {
            if (eligible(i)) return i
            i += step
        }
        return null
    }
}
