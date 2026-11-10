package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.ChannelDao
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelKeyId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope

internal const val BATCH_SIZE = 500
internal const val UNCATEGORISED = "Uncategorised"

/** One channel as delivered by a source. Optional fields are already blank-to-null normalised. */
internal data class ImportedChannel(
    val key: String,
    val name: String,
    val streamUrl: String,
    /** tvg-chno / Xtream num; used as the channel number when unique within the playlist. */
    val preferredNumber: Int?,
    val logo: String?,
    val group: String?,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
    val catchupType: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    val catchupCorrectionMinutes: Int = 0,
    /** MAC portal: the stream needs a `create_link` at play time. */
    val needsLink: Boolean = false,
)

internal fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Writes one playlist's channels in batches of [BATCH_SIZE]. Must run inside the import transaction.
 *
 * Rows are matched by `(playlistId, key)`: existing rows are updated in place (keeping their id and
 * EPG match), new ones inserted, and [finish] deletes rows whose key was not seen. A key repeated
 * within the source is kept once (first wins).
 *
 * Numbering rule (no two channels of a playlist share a number):
 * - a channel's preferred number (tvg-chno / Xtream num, if > 0) is used when no other channel of
 *   the playlist has the same preferred number;
 * - every other channel (none, invalid, or duplicated) is a fallback: fallbacks are numbered
 *   consecutively, in source order, starting just above the highest number used by the first group.
 *   Without any preferred numbers this is simply the 1-based position.
 *
 * While streaming, fallbacks are written with the provisional number `-(position)`; [finish] turns
 * them into final numbers with one UPDATE per run of consecutive positions.
 */
internal class ChannelWriter(
    private val dao: ChannelDao,
    private val playlistId: Long,
    existing: List<ChannelKeyId>,
    private val progress: (Int) -> Unit,
) {
    private val unseen = existing.associateByTo(HashMap()) { it.key }
    private val seenKeys = HashSet<String>()
    private val groupOrders = HashMap<String, Int>()
    private val firstPositionOfPreferred = HashMap<Int, Int>()
    private val duplicatedPreferred = HashSet<Int>()
    private val fallbackPositions = ArrayList<Int>()
    private val batch = ArrayList<ChannelEntity>(BATCH_SIZE)

    var count = 0
        private set

    suspend fun add(c: ImportedChannel) {
        if (!seenKeys.add(c.key)) return
        val position = ++count
        val preferred = c.preferredNumber?.takeIf { it > 0 }
        if (preferred == null) {
            fallbackPositions += position
        } else {
            val first = firstPositionOfPreferred.putIfAbsent(preferred, position)
            if (first != null) {
                if (duplicatedPreferred.add(preferred)) fallbackPositions += first
                fallbackPositions += position
            }
        }
        val previous = unseen.remove(c.key)
        val group = c.group ?: UNCATEGORISED
        batch += ChannelEntity(
            id = previous?.id ?: 0,
            playlistId = playlistId,
            key = c.key,
            number = preferred ?: -position,
            name = c.name,
            streamUrl = c.streamUrl,
            logo = c.logo,
            groupTitle = group,
            groupOrder = groupOrders.getOrPut(group) { groupOrders.size },
            sortOrder = position - 1,
            tvgId = c.tvgId,
            tvgName = c.tvgName,
            userAgent = c.userAgent,
            referrer = c.referrer,
            catchupType = c.catchupType,
            catchupDays = c.catchupDays,
            catchupSource = c.catchupSource,
            catchupCorrectionMinutes = c.catchupCorrectionMinutes,
            epgChannelId = previous?.epgChannelId,
            needsLink = c.needsLink,
        )
        if (batch.size >= BATCH_SIZE) flush()
    }

    /** Flushes, removes vanished rows and applies the numbering rule. Returns the channel count. */
    suspend fun finish(): Int {
        flush()
        unseen.values.map { it.id }.chunked(BATCH_SIZE).forEach { dao.deleteByIds(it) }
        duplicatedPreferred.chunked(BATCH_SIZE).forEach { dao.demoteToFallback(playlistId, it) }
        val base = firstPositionOfPreferred.keys.filter { it !in duplicatedPreferred }.maxOrNull() ?: 0
        for (run in fallbackRuns(fallbackPositions.sorted())) {
            // number = -(position)  →  base + rank = base + firstRank + (position - firstPosition)
            dao.assignFallbackRun(
                playlistId,
                lowest = -run.lastPosition,
                highest = -run.firstPosition,
                offset = base + run.firstRank - run.firstPosition,
            )
        }
        return count
    }

    private suspend fun flush() {
        if (batch.isEmpty()) return
        val (existingRows, newRows) = batch.partition { it.id != 0L }
        if (existingRows.isNotEmpty()) dao.updateAll(existingRows)
        if (newRows.isNotEmpty()) dao.insertAll(newRows)
        batch.clear()
        progress(count)
    }
}

/** A run of consecutive source positions among the fallbacks; [firstRank] is 1-based among fallbacks. */
internal data class FallbackRun(val firstPosition: Int, val lastPosition: Int, val firstRank: Int)

internal fun fallbackRuns(sortedPositions: List<Int>): List<FallbackRun> {
    val runs = ArrayList<FallbackRun>()
    var i = 0
    while (i < sortedPositions.size) {
        var j = i
        while (j + 1 < sortedPositions.size && sortedPositions[j + 1] == sortedPositions[j] + 1) j++
        runs += FallbackRun(sortedPositions[i], sortedPositions[j], firstRank = i + 1)
        i = j + 1
    }
    return runs
}

/**
 * Runs the blocking, callback-style [produce] on IO and feeds its items to the suspending [consume]
 * in the caller's context (the DB transaction), with back-pressure. A failure on either side cancels
 * the other and is rethrown; [produce]'s return value is returned.
 */
internal suspend fun <T, R> pipe(produce: suspend (emit: (T) -> Unit) -> R, consume: suspend (T) -> Unit): R =
    coroutineScope {
        val channel = Channel<T>(BATCH_SIZE)
        val producer = async(Dispatchers.IO) {
            try {
                produce { channel.trySendBlocking(it).getOrThrow() }
            } finally {
                channel.close()
            }
        }
        channel.consumeEach { consume(it) }
        producer.await()
    }
