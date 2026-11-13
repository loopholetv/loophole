package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.ChannelPrefsDao
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodWriteDao
import io.github.loopholetv.data.vod.EpisodeShowInput
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.assignShows
import io.github.loopholetv.data.vod.parseEpisodeNumber
import io.github.loopholetv.data.vod.vodKey
import java.time.Instant

/**
 * Writes one playlist's films and episodes in batches of [BATCH_SIZE]. Must run inside the import
 * transaction, like [ChannelWriter].
 *
 * Rows are matched by `(playlistId, key)`. When a key repeats within the source but the stream URL
 * differs (a provider that names items in the query, `movie.php?id=…`), that entry is keyed by its
 * full URL instead; an entry whose full URL repeats too is a duplicate and is dropped (first wins).
 * A row new to the playlist gets `firstSeenAt = now`, except on the playlist's first import (no
 * film or episode stored yet) and on a [rekey] import (the source address changed, so every key is
 * new), where new rows count as old ([Instant.EPOCH]) so that "recently added" only shows items
 * that appear later. Existing rows keep their stored time.
 *
 * New episodes get a provisional show key from their group while streaming; existing ones keep
 * their stored key. [finish] assigns the real shows once every episode is known and updates only
 * the episodes whose show changed.
 */
internal class VodWriter(
    private val dao: VodWriteDao,
    private val prefs: ChannelPrefsDao,
    private val playlistId: Long,
    private val now: Instant,
    private val rekey: Boolean = false,
) {
    private var storedFilms: Map<String, Instant>? = null
    private var storedEpisodes: Map<String, Instant>? = null
    private var storedShowKeys: Map<String, String> = emptyMap()
    private var initial = false
    private val seenFilms = HashSet<String>()
    private val seenEpisodes = HashSet<String>()
    private val filmUrls = HashSet<String>()
    private val episodeUrls = HashSet<String>()
    private val films = ArrayList<FilmEntity>(BATCH_SIZE)
    private val episodes = ArrayList<EpisodeEntity>(BATCH_SIZE)
    private var position = 0

    suspend fun add(v: ImportedVod) {
        if (v.streamUrl.isBlank()) return
        loadStored()
        when (v.kind) {
            VodKind.MOVIE -> addFilm(v)
            VodKind.EPISODE -> addEpisode(v)
        }
    }

    private suspend fun addFilm(v: ImportedVod) {
        val key = uniqueKey(v, seenFilms, filmUrls) ?: return
        films += FilmEntity(
            playlistId = playlistId,
            key = key,
            name = v.name,
            category = v.group,
            logo = v.logo,
            streamUrl = v.streamUrl,
            userAgent = v.userAgent,
            referrer = v.referrer,
            tvgId = v.tvgId,
            position = ++position,
            firstSeenAt = firstSeen(storedFilms, key),
        )
        if (films.size >= BATCH_SIZE) flushFilms()
    }

    private suspend fun addEpisode(v: ImportedVod) {
        val key = uniqueKey(v, seenEpisodes, episodeUrls) ?: return
        val number = parseEpisodeNumber(v.name)
        episodes += EpisodeEntity(
            playlistId = playlistId,
            key = key,
            showKey = storedShowKeys[key] ?: "g:${v.group}",
            groupTitle = v.group,
            name = v.name,
            season = number?.season,
            episode = number?.episode,
            logo = v.logo,
            streamUrl = v.streamUrl,
            userAgent = v.userAgent,
            referrer = v.referrer,
            tvgId = v.tvgId,
            position = ++position,
            firstSeenAt = firstSeen(storedEpisodes, key),
        )
        if (episodes.size >= BATCH_SIZE) flushEpisodes()
    }

    /** The key [v] is stored under, claimed in [seen]; null when it duplicates an earlier entry. */
    private fun uniqueKey(v: ImportedVod, seen: MutableSet<String>, urls: MutableSet<String>): String? {
        if (!urls.add(v.streamUrl)) return null
        return when {
            seen.add(v.key) -> v.key
            seen.add(v.streamUrl) -> v.streamUrl
            else -> null
        }
    }

    /** Flushes, drops what the source no longer lists and rebuilds the shows. Returns films plus episodes written. */
    suspend fun finish(): Int {
        loadStored()
        flushFilms()
        flushEpisodes()
        storedFilms.orEmpty().keys.filter { it !in seenFilms }.chunked(BATCH_SIZE)
            .forEach { dao.deleteFilms(playlistId, it) }
        storedEpisodes.orEmpty().keys.filter { it !in seenEpisodes }.chunked(BATCH_SIZE)
            .forEach { dao.deleteEpisodes(playlistId, it) }
        rebuildShows()
        carryHiddenChannels()
        return seenFilms.size + seenEpisodes.size
    }

    private suspend fun loadStored() {
        if (storedFilms != null) return
        val f = dao.filmKeys(playlistId).associate { it.key to it.firstSeenAt }
        val e = dao.episodeKeys(playlistId).associate { it.key to it.firstSeenAt }
        storedShowKeys = dao.episodeShowKeys(playlistId).associate { it.key to it.showKey }
        initial = rekey || (f.isEmpty() && e.isEmpty())
        storedFilms = f
        storedEpisodes = e
    }

    private fun firstSeen(stored: Map<String, Instant>?, key: String): Instant =
        stored?.get(key) ?: if (initial) Instant.EPOCH else now

    private suspend fun flushFilms() {
        if (films.isEmpty()) return
        dao.upsertFilms(films.toList())
        films.clear()
    }

    private suspend fun flushEpisodes() {
        if (episodes.isEmpty()) return
        dao.upsertEpisodes(episodes.toList())
        episodes.clear()
    }

    private suspend fun rebuildShows() {
        val rows = dao.episodesForShows(playlistId)
        val assigned = assignShows(rows.map { EpisodeShowInput(it.key, it.groupTitle, it.name) })
        for (r in rows) {
            val show = assigned.getValue(r.key).showKey
            if (show != r.showKey) dao.setShowKey(playlistId, r.key, show)
        }
        dao.deleteShows(playlistId)
        val shows = rows.groupBy { assigned.getValue(it.key).showKey }.map { (key, eps) ->
            val a = assigned.getValue(eps.first().key)
            ShowEntity(
                playlistId = playlistId,
                key = key,
                name = a.showName,
                groupTitle = eps.first().groupTitle,
                category = a.category,
                logo = eps.sortedBy { it.position }.firstNotNullOfOrNull { it.logo },
                // The newest episode: a show that gains a season counts as recently added again.
                firstSeenAt = eps.maxOf { it.firstSeenAt },
                position = eps.minOf { it.position },
            )
        }
        shows.chunked(BATCH_SIZE).forEach { dao.insertShows(it) }
    }

    /**
     * A channel that was hidden before its entry became a film or episode keeps its hidden state
     * under the new key: its full stream URL when an item is keyed by that, otherwise the URL
     * without query. Re-running is harmless.
     */
    private suspend fun carryHiddenChannels() {
        val vodKeys = seenFilms + seenEpisodes
        for (row in prefs.forPlaylist(playlistId)) {
            if (!row.hidden) continue
            val key = row.channelKey.takeIf { it in vodKeys } ?: vodKey(row.channelKey)
            if (key !in vodKeys) continue
            val target = prefs.get(playlistId, key)
            if (target?.hidden == true) continue
            prefs.upsert(target?.copy(hidden = true) ?: ChannelPrefsEntity(playlistId, key, hidden = true))
        }
    }
}
