package io.github.loopholetv.ui.player

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ProgrammeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The footer row's contents: a window of channels around the current one, or History, with the
 * programme airing on each tile. Loads run in [scope]; a new load cancels the one before.
 */
internal class FooterRow(
    private val scope: CoroutineScope,
    private val channels: PlayerChannels,
    private val nowAndNext: suspend (ChannelEntity, Instant) -> List<ProgrammeEntity>,
    private val clock: () -> Instant,
    private val recent: Flow<List<ChannelEntity>>,
) {
    private val _row = MutableStateFlow(ChannelRowUi.EMPTY)
    val row: StateFlow<ChannelRowUi> = _row.asStateFlow()

    private var rowJob: Job? = null
    private var edgeJob: Job? = null

    fun update(f: (ChannelRowUi) -> ChannelRowUi) = _row.update(f)

    /** The channels of [zapScope] around [channel], focus on it. */
    fun load(channel: ChannelEntity, zapScope: ChannelScope) {
        rowJob?.cancel()
        rowJob = scope.launch {
            val list = channels.around(channel, zapScope, ROW_EACH_SIDE)
            val index = list.indexOfFirst { it.id == channel.id }
            _row.value = ChannelRowUi(list, index, emptyMap())
            _row.value = ChannelRowUi(list, index, titlesFor(list))
        }
    }

    /** The recently watched channels, focus on [currentId] (else the first). */
    fun history(currentId: Long) {
        rowJob?.cancel()
        edgeJob?.cancel()
        rowJob = scope.launch {
            val list = recent.catch { emit(emptyList()) }.first()
            val index = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            _row.value = ChannelRowUi(list, index, emptyMap(), RowMode.HISTORY)
            val titles = titlesFor(list)
            _row.update { if (it.mode == RowMode.HISTORY && it.channels == list) it.copy(titles = titles) else it }
        }
    }

    /** Up to [ROW_EACH_SIDE] more channels of [zapScope] past the first or last tile. */
    fun extend(atEnd: Boolean, zapScope: ChannelScope) {
        if (edgeJob?.isActive == true || rowJob?.isActive == true) return
        val row = _row.value
        // History is the whole list already; scope channels don't belong in it.
        if (row.mode != RowMode.CHANNELS) return
        val edge = (if (atEnd) row.channels.lastOrNull() else row.channels.firstOrNull()) ?: return
        edgeJob = scope.launch {
            val around = channels.around(edge, zapScope, ROW_EACH_SIDE)
            val at = around.indexOfFirst { it.id == edge.id }
            val known = row.channels.mapTo(HashSet()) { it.id }
            val more = (if (atEnd) around.drop(at + 1) else around.take(at.coerceAtLeast(0))).filter { it.id !in known }
            if (more.isEmpty() || _row.value !== row) return@launch
            val titles = titlesFor(more)
            _row.value = if (atEnd) {
                row.copy(channels = row.channels + more, titles = row.titles + titles)
            } else {
                row.copy(channels = more + row.channels, currentIndex = row.currentIndex + more.size, titles = row.titles + titles)
            }
        }
    }

    /**
     * [fresh] was edited: hidden, it leaves the row; otherwise its tile shows the new channel and,
     * reloaded in [scope], the programme its (possibly new) guide has airing.
     */
    fun onEdited(fresh: ChannelEntity) {
        val id = fresh.id
        if (!fresh.visible) {
            update { r ->
                val kept = r.channels.filter { it.id != id }
                if (kept.size == r.channels.size) r else r.copy(channels = kept, currentIndex = kept.indexOfFirst { it.id == r.currentId })
            }
            return
        }
        update { r -> r.copy(channels = r.channels.map { if (it.id == id) fresh else it }) }
        scope.launch {
            val title = titlesFor(listOf(fresh))[id]
            update { r ->
                if (r.channels.none { it.id == id }) r
                else r.copy(titles = if (title != null) r.titles + (id to title) else r.titles - id)
            }
        }
    }

    /**
     * The programmes airing now and next on [channel] for the info bar, given to [loaded]. Without
     * a guide (e.g. the manual one was removed) that is null and null at once, so nothing stays
     * from the old one; otherwise they load in [scope] (the job is returned).
     */
    fun nowNext(channel: ChannelEntity, loaded: (ProgrammeEntity?, ProgrammeEntity?) -> Unit): Job? {
        // guideEpgId: the manual choice, else the automatic match; times come back shifted.
        if (channel.guideEpgId == null) {
            loaded(null, null)
            return null
        }
        return scope.launch {
            val at = clock()
            val list = nowAndNext(channel, at)
            loaded(list.firstOrNull { it.start <= at }, list.firstOrNull { it.start > at })
        }
    }

    /** The programme airing now on each of [list] (by channel id), where known. */
    suspend fun titlesFor(list: List<ChannelEntity>): Map<Long, String> {
        val at = clock()
        return buildMap {
            for (c in list) {
                if (c.guideEpgId == null) continue
                nowAndNext(c, at).firstOrNull { it.start <= at }?.let { put(c.id, it.title) }
            }
        }
    }
}
