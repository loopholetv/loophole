package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.xmltv.XmltvChannel
import io.github.loopholetv.data.xmltv.XmltvProgramme
import kotlinx.coroutines.CancellationException

/** Thrown for a non-2xx guide download. */
internal class EpgHttpException(val code: Int) : Exception("HTTP $code")

/**
 * One EPG refresh's streaming half: feeds the sources' channels and programmes through the matcher
 * and stores the programmes of matched guide channels, tagged with [generation], in short
 * transactions of [BATCH_SIZE] rows. Apart from undoing a failed source (see [importSource]) it never
 * deletes anything; the caller swaps generations once every source has been read, or deletes
 * [generation] if none worked.
 *
 * Matching runs lazily: XMLTV lists `<channel>`s before `<programme>`s, so the pending channels of a
 * source are matched on its next programme. A programme whose channel was never declared is
 * treated as a channel with that id and no display names (so tvg-id matching still works). A guide
 * channel matched by an earlier source is "owned" by it: later sources' programmes for that id are
 * skipped, so overlapping guides don't produce duplicates.
 *
 * [reserved] holds the guide ids chosen by hand (manual mapping): the first source that declares one
 * (by `<channel>` or by a programme) owns it, so its programmes are stored and swapped like matched
 * ones, whether or not any channel matches it automatically.
 */
internal class EpgImporter(
    private val db: AppDatabase,
    channels: List<ChannelKeyView>,
    private val generation: Long,
    private val progress: (Int) -> Unit,
    private val storeDescriptions: Boolean,
    private val reserved: Set<String> = emptySet(),
) {
    private val index = EpgMatcher.Index(channels)
    private val dao = db.programmeDao()
    private val batch = ArrayList<ProgrammeEntity>(BATCH_SIZE)
    private val owned = HashSet<String>()
    private val declared = LinkedHashMap<String, String>()

    /** Playlist channel id → XMLTV id, for every channel matched so far by a source that hasn't failed. */
    val assignments = HashMap<Long, String>()

    /**
     * Guide channels declared by `<channel>` in the sources that worked (XMLTV id → first display
     * name, or the id when it has none); the first source declaring an id wins.
     */
    val guideChannels: Map<String, String> get() = declared

    /** XMLTV ids that have programmes stored by this refresh's (non-failed) sources. */
    val matchedEpgIds: Set<String> get() = owned

    var stored = 0
        private set

    /**
     * Streams one source; [produce] calls `emit` with each [XmltvChannel] / [XmltvProgramme] (on an IO
     * thread). If it fails with an [Exception] (not a cancellation), the source is undone before the
     * exception is rethrown: its rows of [generation] are dropped, and its guide channels and matches
     * are released so a later source can still claim those playlist channels.
     */
    suspend fun importSource(produce: suspend (emit: (Any) -> Unit) -> Unit) {
        val source = SourceState()
        try {
            pipe(produce) { item ->
                when (item) {
                    is XmltvChannel -> {
                        declare(source, item)
                        source.listed.putIfAbsent(item.id, item.displayNames.firstOrNull { it.isNotBlank() }?.trim() ?: item.id)
                    }
                    is XmltvProgramme -> onProgramme(source, item)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) undo(source)
            throw e
        }
        for ((id, name) in source.listed) declared.putIfAbsent(id, name)
    }

    private fun declare(source: SourceState, c: XmltvChannel) {
        if (!source.declare(c)) return
        // A manually chosen id belongs to the first source that has it.
        if (c.id in reserved && owned.add(c.id)) source.owned += c.id
    }

    /** Writes the last partial batch. */
    suspend fun flush() {
        if (batch.isEmpty()) return
        val rows = ArrayList(batch)
        batch.clear()
        db.withTransaction { dao.insertAll(rows) }
        stored += rows.size
        progress(stored)
    }

    private suspend fun undo(source: SourceState) {
        if (source.owned.isNotEmpty()) {
            batch.removeAll { it.epgChannelId in source.owned }
            val ids = source.owned.toList()
            val deleted = db.withTransaction {
                ids.chunked(BATCH_SIZE).sumOf { dao.deleteGenerationFor(generation, it) }
            }
            stored -= deleted
            owned -= source.owned
        }
        for (channelId in source.assigned) assignments -= channelId
        index.release(source.assigned)
    }

    private suspend fun onProgramme(source: SourceState, p: XmltvProgramme) {
        if (!source.isKnown(p.channelId)) declare(source, XmltvChannel(p.channelId, emptyList(), null))
        if (source.pending.isNotEmpty()) matchPending(source)
        if (p.channelId !in source.owned) return
        batch += ProgrammeEntity(
            epgChannelId = p.channelId,
            start = p.start,
            stop = p.stop,
            title = p.title,
            subTitle = p.subTitle,
            description = if (storeDescriptions) p.description else null,
            category = p.category,
            generation = generation,
        )
        if (batch.size >= BATCH_SIZE) flush()
    }

    private fun matchPending(source: SourceState) {
        if (index.hasUnmatched) {
            for ((channelId, epgId) in index.match(source.pending)) {
                assignments[channelId] = epgId
                source.assigned += channelId
                if (owned.add(epgId)) source.owned += epgId
            }
        }
        source.pending.clear()
    }

    private class SourceState {
        /** Every XMLTV id seen in this source (first declaration wins). */
        private val seen = HashSet<String>()
        val pending = LinkedHashMap<String, XmltvChannel>()

        /** Guide channels first matched by this source: only it stores their programmes. */
        val owned = HashSet<String>()

        /** Playlist channels this source matched. */
        val assigned = ArrayList<Long>()

        /** This source's `<channel>`s (id → display name); merged into the guide-channel list once it worked. */
        val listed = LinkedHashMap<String, String>()

        fun isKnown(id: String) = id in seen

        /** False when [c]'s id was already seen in this source. */
        fun declare(c: XmltvChannel): Boolean {
            if (!seen.add(c.id)) return false
            pending[c.id] = c
            return true
        }
    }
}
