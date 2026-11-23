package io.github.loopholetv.data.tmdb

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.TmdbGenreEntity
import io.github.loopholetv.data.db.TmdbTitleEntity
import io.github.loopholetv.data.db.VodMatchEntity
import io.github.loopholetv.data.vod.TitleKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data object BadKey : SyncOutcome
    data class RetryLater(val afterSec: Long?) : SyncOutcome
    data object Paused : SyncOutcome
}

private val REFETCH_AFTER: Duration = Duration.ofDays(30)
private val NOT_FOUND_RETRY: Duration = Duration.ofDays(7)
private val PRUNE_AFTER: Duration = Duration.ofDays(30)

/** A present title's last-seen time is moved on at most this often (well inside [PRUNE_AFTER]). */
private val TOUCH_AFTER: Duration = Duration.ofDays(1)

/** A run gives up after this many titles fail one after another (TMDB or the network is down). */
internal const val FAILURES_IN_A_ROW = 3

/** Finished titles are written together, at most this many per transaction... */
internal const val WRITE_BATCH_TITLES = 25

/** ...or once the oldest unwritten one has waited this long. */
internal val WRITE_BATCH_WAIT: Duration = Duration.ofSeconds(10)

/**
 * Matches every playlist film and show to TMDB and stores its details. Finished titles are written in
 * batches ([WRITE_BATCH_TITLES] titles or [WRITE_BATCH_WAIT], whichever comes first), so the browse
 * screens re-read once per batch rather than once per title; whatever is unwritten when the run ends,
 * stops or is cancelled is written then, so a stopped run picks up where it left off. Only titles
 * never looked up, or due again, cost requests; none are made without a key or while a recording runs.
 * A title whose request fails is skipped and tried again on a later run; [FAILURES_IN_A_ROW] failures in a
 * row, or TMDB asking to slow down, end the run early.
 * [transaction] runs each batch write (replaceable in tests).
 */
class TmdbSync(
    private val db: AppDatabase,
    private val client: TmdbDetailsSource,
    private val matcher: TmdbMatcher,
    private val keyPresent: suspend () -> Boolean,
    private val recordingActive: () -> Boolean,
    private val language: () -> String,
    private val clock: () -> Instant,
    private val onBadKey: suspend () -> Unit,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { block -> db.withTransaction { block() } },
) {
    private val dao = db.tmdbDao()
    private val fetcher = TmdbFetcher(client, recordingActive)

    /** A looked-up title waiting to be written; [lookedUpFrom] is the state its row had when the lookup started. */
    private class Pending(val fetched: FetchedTitle?, val match: VodMatchEntity, val lookedUpFrom: MatchState?)

    private val pending = ArrayList<Pending>()
    private val pendingTitles = HashMap<Pair<TmdbKind, Long>, TmdbTitleEntity>()
    private var pendingSince: Instant? = null

    private class Title(
        val playlistId: Long,
        val kind: TitleKind,
        val key: String,
        val name: String,
        val tvgId: String?,
        val row: VodMatchEntity?,
    ) {
        val fingerprint = fingerprint(kind, tvgId, name)
        val badge = cleanTitle(name).badge
    }

    suspend fun run(): SyncOutcome {
        if (!keyPresent()) return SyncOutcome.Done
        val lang = language()
        refreshGenres(lang)?.let { return it }
        val runStart = clock()
        var anyFailed = false
        var failedInARow = 0
        try {
            for (t in currentTitles(runStart)) {
                val row = t.row ?: inherit(t)
                if (!isDue(t, row, lang)) continue
                if (recordingActive()) return SyncOutcome.Paused
                val stop = lookUp(t, row, lang)
                when {
                    stop == null -> failedInARow = 0
                    stop.isTitleFailure() -> {
                        // Left unwritten, so it is due again next run; several in a row means TMDB is out of reach.
                        anyFailed = true
                        if (++failedInARow >= FAILURES_IN_A_ROW) return stop
                    }
                    else -> return stop
                }
            }
        } finally {
            // Also on Paused, BadKey, RetryLater and cancellation: what was looked up is kept.
            withContext(NonCancellable) { flush() }
            present.clear()
        }
        dao.pruneUnseen(clock().minus(PRUNE_AFTER))
        dao.deleteOrphanTitles()
        return if (anyFailed) SyncOutcome.RetryLater(null) else SyncOutcome.Done
    }

    /** A request for one title failed without TMDB asking to slow down (which carries a wait). */
    private fun SyncOutcome.isTitleFailure() = this is SyncOutcome.RetryLater && afterSec == null

    /** Fetches each kind's genre names when none are cached in [lang]. */
    private suspend fun refreshGenres(lang: String): SyncOutcome? {
        for (kind in TmdbKind.entries) {
            if (dao.genreLanguages(kind) == listOf(lang)) continue
            if (recordingActive()) return SyncOutcome.Paused
            when (val r = client.genres(kind, lang)) {
                is TmdbResult.Ok -> dao.replaceGenres(kind, r.value.map { TmdbGenreEntity(kind, it.id, it.name, lang) })
                is TmdbResult.NotFound -> Unit
                else -> return stop(r.notOk())
            }
        }
        return null
    }

    /** Every film and show of every playlist, including turned-off ones; see [inherit]. */
    private val present = HashSet<Triple<Long, TitleKind, String>>()

    /** How many titles [present] holds; empty between runs. */
    @VisibleForTesting internal val titlesHeld get() = present.size

    /** Every film and show, marked as seen; only those of turned-on playlists are returned for lookup. */
    private suspend fun currentTitles(now: Instant): List<Title> = buildList {
        present.clear()
        for (playlist in db.playlistDao().all().first()) {
            for (kind in TitleKind.entries) {
                val sources = if (kind == TitleKind.FILM) dao.filmSources(playlist.id) else dao.showSources(playlist.id)
                dao.touchSeen(playlist.id, kind, sources.map { it.key }, now, staleBefore = now.minus(TOUCH_AFTER))
                sources.mapTo(present) { Triple(playlist.id, kind, it.key) }
                if (!playlist.enabled) continue
                val rows = dao.matchesOf(playlist.id, kind).associateBy { it.key }
                sources.forEach { s ->
                    add(Title(playlist.id, kind, s.key, s.name, s.tvgId?.trim()?.ifEmpty { null }, rows[s.key]))
                }
            }
        }
    }

    /**
     * A title new under its key takes over the newest row with its fingerprint, e.g. after its key moved.
     * Only a row whose title is gone (not in [present]) is taken over: a present title sharing the
     * fingerprint (a reused tvg-id, or the same name in two entries) is another title.
     */
    private suspend fun inherit(t: Title): VodMatchEntity? {
        val found = dao.matchesByFingerprint(t.fingerprint)
            .firstOrNull { Triple(it.playlistId, it.kind, it.key) !in present } ?: return null
        val copy = found.copy(
            playlistId = t.playlistId, key = t.key, badge = t.badge,
            sourceName = t.name, sourceTvgId = t.tvgId, lastSeenAt = clock(),
        )
        dao.upsertMatch(copy)
        return copy
    }

    private suspend fun isDue(t: Title, row: VodMatchEntity?, lang: String): Boolean {
        if (row == null) return true
        val sourceChanged = row.sourceName != t.name || row.sourceTvgId != t.tvgId
        return when (row.state) {
            MatchState.MATCHED -> {
                val kind = row.tmdbKind
                val id = row.tmdbId
                kind == null || id == null || storedTitle(kind, id)?.isFresh(lang) != true
            }
            MatchState.NOT_FOUND -> sourceChanged || !row.lookedUpAt.isAfter(clock().minus(NOT_FOUND_RETRY))
            MatchState.REJECTED -> sourceChanged
        }
    }

    private fun TmdbTitleEntity.isFresh(lang: String) =
        language == lang && fetchedAt.isAfter(clock().minus(REFETCH_AFTER))

    /**
     * Matches (or, when already matched, refreshes) one title and queues it. Non-null ends the run, except
     * a failure of this title alone ([isTitleFailure]), which only skips it.
     */
    private suspend fun lookUp(t: Title, row: VodMatchEntity?, lang: String): SyncOutcome? {
        val kind = row?.tmdbKind
        val id = row?.tmdbId
        if (row?.state == MatchState.MATCHED && kind != null && id != null) {
            return store(t, row, kind, id, details(kind, id, lang) { _, _ -> true })
        }
        numericShowId(t)?.let { showId ->
            // A provider's numeric show id is trusted only when TMDB's name for it is the show's name.
            when (val f = details(TmdbKind.TV, showId, lang) { a, b -> sameName(t.name, a, b) }) {
                is Fetch.Got, Fetch.Cached -> return store(t, row, TmdbKind.TV, showId, f)
                Fetch.Missing -> Unit
                else -> return stop(f)
            }
        }
        val outcome = when (t.kind) {
            TitleKind.FILM -> matcher.matchFilm(t.name, t.tvgId, lang)
            TitleKind.SHOW -> matcher.matchShow(t.name, t.tvgId.takeIf { numericShowId(t) == null }, lang)
        }
        return when (outcome) {
            is MatchOutcome.Matched -> store(t, row, outcome.kind, outcome.id, details(outcome.kind, outcome.id, lang) { _, _ -> true })
            MatchOutcome.NotFound -> store(t, row, null, null, Fetch.Missing)
            MatchOutcome.BadKey -> stop(Fetch.BadKey)
            is MatchOutcome.Failed -> SyncOutcome.RetryLater(outcome.retryAfterSec)
        }
    }

    /** A fresh stored copy (that passes [accept]) is reused; otherwise the details are requested. */
    private suspend fun details(kind: TmdbKind, id: Long, lang: String, accept: (String, String?) -> Boolean): Fetch {
        val stored = storedTitle(kind, id)
        if (stored != null && stored.isFresh(lang)) {
            return if (accept(stored.title, stored.originalTitle)) Fetch.Cached else Fetch.Missing
        }
        return fetcher.fetch(kind, id, lang, clock(), accept)
    }

    /** A title's details, including those fetched this run and not yet written. */
    private suspend fun storedTitle(kind: TmdbKind, id: Long): TmdbTitleEntity? =
        pendingTitles[kind to id] ?: dao.titleNow(kind, id)

    /** Queues the title's details (if any) and its match for the next batch write. Non-null ends the run. */
    private suspend fun store(t: Title, row: VodMatchEntity?, kind: TmdbKind?, id: Long?, fetch: Fetch): SyncOutcome? {
        val matched = kind != null && id != null && (fetch is Fetch.Got || fetch == Fetch.Cached)
        if (!matched && fetch != Fetch.Missing) return stop(fetch)
        val now = clock()
        val match = VodMatchEntity(
            playlistId = t.playlistId, kind = t.kind, key = t.key, fingerprint = t.fingerprint,
            state = if (matched) MatchState.MATCHED else MatchState.NOT_FOUND,
            tmdbKind = if (matched) kind else null, tmdbId = if (matched) id else null, badge = t.badge,
            sourceName = t.name, sourceTvgId = t.tvgId, lookedUpAt = now, lastSeenAt = now,
        )
        val fetched = (fetch as? Fetch.Got)?.value
        if (pending.isEmpty()) pendingSince = now
        pending += Pending(fetched, match, row?.state)
        fetched?.let { pendingTitles[it.title.tmdbKind to it.title.tmdbId] = it.title }
        val since = pendingSince
        if (pending.size >= WRITE_BATCH_TITLES || (since != null && !clock().isBefore(since.plus(WRITE_BATCH_WAIT)))) flush()
        return null
    }

    /**
     * Writes every queued title and match in one transaction. A match the viewer rejected while its
     * lookup ran stays rejected; a rejected title looked up again (its name or tvg-id changed) is written.
     */
    private suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        transaction {
            for (item in batch) {
                item.fetched?.let { v ->
                    dao.upsertTitle(v.title, v.genreIds)
                    v.episodes?.let { dao.upsertEpisodes(v.title.tmdbId, it) }
                }
                val m = item.match
                val rejectedMeanwhile = item.lookedUpFrom != MatchState.REJECTED &&
                    dao.match(m.playlistId, m.kind, m.key)?.state == MatchState.REJECTED
                if (!rejectedMeanwhile) dao.upsertMatch(m)
            }
        }
        pending.clear()
        pendingTitles.clear()
        pendingSince = null
    }

    private suspend fun stop(fetch: Fetch): SyncOutcome = when (fetch) {
        Fetch.Paused -> SyncOutcome.Paused
        Fetch.BadKey -> {
            onBadKey()
            SyncOutcome.BadKey
        }
        is Fetch.Failed -> SyncOutcome.RetryLater(fetch.retryAfterSec)
        else -> error("not a stop")
    }

    private fun numericShowId(t: Title): Long? =
        t.tvgId?.takeIf { t.kind == TitleKind.SHOW && it.all { c -> c in '0'..'9' } }?.toLongOrNull()

    private fun sameName(showName: String, title: String, originalTitle: String?): Boolean {
        val wanted = normaliseTitle(cleanTitle(showName).query)
        if (wanted.isEmpty()) return false
        return wanted == normaliseTitle(title) || originalTitle?.let { normaliseTitle(it) } == wanted
    }
}
