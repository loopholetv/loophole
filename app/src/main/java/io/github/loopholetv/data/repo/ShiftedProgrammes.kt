package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.MAX_WINDOW_IDS
import io.github.loopholetv.data.db.ProgrammeDao
import io.github.loopholetv.data.db.ProgrammeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

/**
 * What a guide row reads: a guide channel at a time shift. Two channels sharing a guide id with
 * different shifts are different keys.
 */
data class GuideKey(val epgId: String, val shiftMinutes: Int)

/** The channel's guide lookup (its `guideEpgId` and shift); null when it has no guide channel. */
val ChannelEntity.guideKey: GuideKey?
    get() = guideEpgId?.let { GuideKey(it, epgShiftMinutes) }

/** This programme as shown with a guide time shift of [minutes]: shown time = stored time + shift. */
fun ProgrammeEntity.shiftedBy(minutes: Int): ProgrammeEntity {
    if (minutes == 0) return this
    val d = Duration.ofMinutes(minutes.toLong())
    return copy(start = start.plus(d), stop = stop.plus(d))
}

/**
 * Programme reads with each channel's guide time shift applied (stored times stay absolute; the
 * shift is applied here, at read time). Windows are given in shown time.
 */
class ShiftedProgrammes(private val dao: ProgrammeDao) {
    /**
     * Committed programmes overlapping the shown window [from, to) for every key, shifted. Each key
     * of [keys] is in the map (an empty list when it has nothing). One [ProgrammeDao.inWindow] query
     * per distinct shift (over `[from − shift, to − shift)`) and at most [MAX_WINDOW_IDS] ids each.
     */
    fun inWindow(keys: Collection<GuideKey>, from: Instant, to: Instant): Flow<Map<GuideKey, List<ProgrammeEntity>>> {
        val distinct = keys.toSet()
        if (distinct.isEmpty()) return flowOf(emptyMap())
        val queries = distinct.groupBy { it.shiftMinutes }.flatMap { (shift, sameShift) ->
            val d = Duration.ofMinutes(shift.toLong())
            sameShift.map { it.epgId }.chunked(MAX_WINDOW_IDS).map { ids ->
                dao.inWindow(ids, from.minus(d), to.minus(d)).map { rows -> rows.byKey(shift) }
            }
        }
        return combine(queries) { parts ->
            val result = LinkedHashMap<GuideKey, List<ProgrammeEntity>>(distinct.size)
            for (key in distinct) result[key] = emptyList()
            for (part in parts) result.putAll(part)
            result
        }
    }

    /** The programme airing at shown time [at] on [channel] (if any) and the one after it, shifted. */
    suspend fun nowAndNext(channel: ChannelEntity, at: Instant): List<ProgrammeEntity> {
        val key = channel.guideKey ?: return emptyList()
        return dao.nowAndNext(key.epgId, at.minus(Duration.ofMinutes(key.shiftMinutes.toLong())))
            .map { it.shiftedBy(key.shiftMinutes) }
    }

    private fun List<ProgrammeEntity>.byKey(shift: Int): Map<GuideKey, List<ProgrammeEntity>> =
        groupBy({ GuideKey(it.epgChannelId, shift) }, { it.shiftedBy(shift) })
}
