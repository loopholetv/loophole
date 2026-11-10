package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEpgRow
import io.github.loopholetv.data.db.EpgChannelEntity
import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.xmltv.XmltvChannel
import io.github.loopholetv.data.xmltv.XmltvParser
import io.github.loopholetv.data.xmltv.XmltvProgramme
import io.github.loopholetv.data.xmltv.XmltvTooLargeException
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.util.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipException

/**
 * Downloads a playlist's XMLTV guides, matches them to its channels and stores the programmes.
 *
 * Sources ([epgSourceUrls]): the playlist's announced EPG URLs (Xtream: its `xmltv.php`; a MAC portal:
 * its own guide via `portalEpg`) unless turned off, then its added links (`epg_sources`), read one
 * after the other; for each guide channel the first source that has it wins. Channels with a manual
 * guide id (`channel_prefs.epgOverride`) are not matched; their ids are reserved instead. Every
 * `<channel>` the working sources list is kept in `epg_channels` (replaced after a fully successful
 * refresh, added to after a partial one) for choosing a guide by hand. No write transaction is held across the download: programmes are inserted in
 * short batches tagged with a new generation, and only after every source has been read does one
 * short transaction drop older generations of the matched guide channels, prune programmes that
 * ended more than `pastDays` days (plus a 30 minute grace, plus the largest positive guide time shift)
 * before the refresh started (0 = every programme that has ended), update the `epgChannelId` of the newly matched channels (others are
 * cleared on a fully successful refresh, but keep theirs if any source failed) and record the status. Sources fail independently: a failed source's rows are removed
 * from the new generation and its channels stay free for later sources, and the rest is committed
 * with a "(N of M guide sources failed)" status message. Only if every source fails is the new
 * generation deleted, leaving the old programmes and mappings as they were (WAL keeps them
 * readable throughout); a cancellation also discards it. The new generation is marked pending (`epg_pending`) before its first row,
 * so reads never see it until the commit; a refresh killed mid-way leaves a marker that the next
 * refresh discards together with its rows. If the playlist is deleted meanwhile, nothing is stored
 * and a "removed" status is returned. Refreshes run one at a time.
 */
class EpgRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val xtream: XtreamClient = XtreamClient(http),
    private val now: () -> Instant = Instant::now,
    private val options: suspend () -> EpgStoreOptions = { EpgStoreOptions() },
    /** `User-Agent` for [playlist]'s XMLTV downloads; null leaves OkHttp's own. */
    private val userAgentFor: suspend (PlaylistEntity) -> String? = { null },
    /** Streams a MAC portal's own guide ([PORTAL_EPG_SOURCE]) as [XmltvChannel]s and [XmltvProgramme]s. */
    private val portalEpg: suspend (PlaylistEntity, emit: (Any) -> Unit) -> Unit = { _, _ -> throw IOException("No portal guide") },
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val programmeDao = db.programmeDao()
    private val statusDao = db.epgStatusDao()
    private val sourceDao = db.epgSourceDao()
    private val guideChannelDao = db.epgChannelDao()
    private val prefsDao = db.channelPrefsDao()
    private val mutex = Mutex()

    fun status(playlistId: Long): Flow<EpgStatusEntity?> = statusDao.observe(playlistId)

    /** [playlistId]'s added guide links, in the order they are read (after its own guide, if used). */
    fun sources(playlistId: Long): Flow<List<EpgSourceEntity>> = sourceDao.observe(playlistId)

    /** Appends [url] (trimmed) to [playlistId]'s guide links; a blank one is ignored. Takes effect on the next [refresh]. */
    suspend fun addSource(playlistId: Long, url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) sourceDao.add(playlistId, trimmed)
    }

    suspend fun removeSource(sourceId: Long) = sourceDao.delete(sourceId)

    /** Whether [playlistId]'s own announced guide (M3U header, Xtream `xmltv.php`, portal guide) is read. */
    suspend fun setUseSourceEpg(playlistId: Long, use: Boolean) = playlistDao.setUseSourceEpg(playlistId, use)

    /**
     * Never throws for source problems: the outcome is returned and stored as the playlist's status
     * (message redacted). [progress] receives the number of programmes stored so far, on a background thread.
     */
    suspend fun refresh(playlistId: Long, progress: (Int) -> Unit): EpgStatusEntity = mutex.withLock {
        withContext(Dispatchers.Default) {
            val playlist = requireNotNull(playlistDao.byId(playlistId)) { "Unknown playlist $playlistId" }
            val urls = epgSourceUrls(playlist, xtream::epgUrl, sourceDao.forPlaylist(playlistId).map { it.url })
            if (urls.isEmpty()) return@withContext fail(playlist.id, EPG_NO_SOURCE)
            val secrets = playlistSecrets(playlist) + urls.filter { it != PORTAL_EPG_SOURCE }.flatMap(::m3uSecrets)
            importAll(playlist, urls, secrets, progress, options())
        }
    }

    /**
     * Removes every stored programme and every playlist's status, waiting for a running refresh.
     * Channel mappings (`epgChannelId`) are kept.
     */
    suspend fun clearGuide() = mutex.withLock {
        withContext(Dispatchers.Default) {
            db.withTransaction {
                programmeDao.deleteAll()
                programmeDao.deleteAllPending()
                statusDao.deleteAll()
            }
        }
    }

    // --- internals ---

    private suspend fun importAll(
        playlist: PlaylistEntity,
        urls: List<String>,
        secrets: List<String>,
        progress: (Int) -> Unit,
        options: EpgStoreOptions,
    ): EpgStatusEntity {
        val playlistId = playlist.id
        val startedAt = now()
        val generation = db.withTransaction {
            // Rows of a refresh killed before it could commit or clean up.
            programmeDao.pendingGenerations().forEach { programmeDao.discard(it) }
            // Strictly newer than anything stored, even if the clock is coarse or went backwards.
            maxOf(startedAt.toEpochMilli(), (programmeDao.maxGeneration() ?: 0L) + 1)
                .also { programmeDao.insertPending(it) }
        }
        val rows = channelDao.epgRows(playlistId)
        // Manual mapping: overridden channels stay out of the matcher, and their chosen ids are reserved
        // so the first source listing one stores (and swaps) its programmes.
        val overrideByKey = prefsDao.epgOverrides(playlistId)
            .mapNotNull { prefs -> prefs.epgOverride?.let { prefs.channelKey to it } }.toMap()
        val overrides = HashMap<Long, String>()
        // The keys are read only when there are overrides (ChannelEpgRow has no key).
        if (overrideByKey.isNotEmpty()) {
            for (k in channelDao.keyIds(playlistId)) overrideByKey[k.key]?.let { overrides[k.id] = it }
        }
        val importer = EpgImporter(
            db,
            rows.filter { it.id !in overrides }.map { ChannelKeyView(it.id, it.tvgId, it.tvgName, it.name) },
            generation,
            progress,
            options.storeDescriptions,
            reserved = overrides.values.toSet(),
        )
        return try {
            val failures = ArrayList<Exception>()
            for (url in urls) {
                try {
                    importer.importSource { emit ->
                        if (url == PORTAL_EPG_SOURCE) portalEpg(playlist, emit) else download(url, userAgentFor(playlist), emit)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    failures += e // the importer has undone this source; the others carry on
                }
            }
            if (failures.size == urls.size) throw failures.first()
            importer.flush()
            commit(playlistId, rows, overrides.keys, importer, generation, startedAt, options.pastDays, failed = failures.size, total = urls.size)
        } catch (e: Throwable) {
            withContext(NonCancellable) { db.withTransaction { programmeDao.discard(generation) } }
            if (e is CancellationException || e !is Exception) throw e
            currentCoroutineContext().ensureActive()
            fail(playlistId, redact(describe(e), secrets))
        }
    }

    private suspend fun download(url: String, userAgent: String?, emit: (Any) -> Unit) {
        val httpUrl = withHttpScheme(url).toHttpUrlOrNull() ?: throw IOException("Not an http(s) URL")
        val request = Request.Builder().url(httpUrl).apply { if (userAgent != null) header("User-Agent", userAgent) }.build()
        val call = http.newCall(request)
        blockingCall(call) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw EpgHttpException(response.code)
                // The parser closes the stream; it detects gzip by its magic bytes.
                XmltvParser.parse(response.body.byteStream(), { emit(it) }, { emit(it) })
            }
        }
    }

    private suspend fun commit(
        playlistId: Long,
        rows: List<ChannelEpgRow>,
        overridden: Set<Long>,
        importer: EpgImporter,
        generation: Long,
        startedAt: Instant,
        pastDays: Int,
        failed: Int,
        total: Int,
    ): EpgStatusEntity = db.withTransaction {
        if (playlistDao.byId(playlistId) == null) {
            programmeDao.discard(generation)
            return@withTransaction removed(playlistId)
        }
        programmeDao.deletePending(generation)
        // Only guide channels a working source delivered are swapped; failed sources' ids were released.
        importer.matchedEpgIds.chunked(BATCH_SIZE).forEach { programmeDao.deleteOlderGenerations(it, generation) }
        // Times are shifted when read, so a channel shown later needs its programmes kept that much longer.
        val shiftMargin = Duration.ofMinutes(maxOf(0, prefsDao.maxPositiveShift() ?: 0).toLong())
        programmeDao.deleteEndedBefore(startedAt.minus(Duration.ofDays(pastDays.coerceAtLeast(0).toLong())).minus(GRACE).minus(shiftMargin))
        for (row in rows) {
            if (row.id in overridden) {
                // Not matched this time: keep its last automatic id (for "Automatic") and re-derive guideEpgId.
                channelDao.setEpgChannelId(row.id, row.epgChannelId)
                continue
            }
            // After a partial failure unmatched channels keep their mapping (their old programmes were
            // not swapped out); after a fully successful refresh they are cleared and pruned normally.
            val epgId = importer.assignments[row.id] ?: if (failed > 0) continue else null
            if (epgId != row.epgChannelId) channelDao.setEpgChannelId(row.id, epgId)
        }
        val listed = importer.guideChannels.map { (id, name) -> EpgChannelEntity(playlistId, id, name) }
        // A failed source's channels stay listed until every source works again.
        if (failed > 0) guideChannelDao.upsertAll(listed) else guideChannelDao.replaceForPlaylist(playlistId, listed)
        val message = if (failed > 0) "($failed of $total guide sources failed)" else null
        EpgStatusEntity(playlistId, startedAt, channelDao.countWithEpg(playlistId), ok = true, message = message)
            .also { statusDao.upsert(it) }
    }

    /** Keeps the last successful time; mappings are untouched, so the count is the current one. */
    private suspend fun fail(playlistId: Long, message: String): EpgStatusEntity = db.withTransaction {
        if (playlistDao.byId(playlistId) == null) return@withTransaction removed(playlistId)
        EpgStatusEntity(
            playlistId = playlistId,
            updatedAt = statusDao.get(playlistId)?.updatedAt,
            matchedChannels = channelDao.countWithEpg(playlistId),
            ok = false,
            message = message,
        ).also { statusDao.upsert(it) }
    }

    /** The playlist was deleted meanwhile (by PlaylistRepository): nothing is stored. */
    private fun removed(playlistId: Long) =
        EpgStatusEntity(playlistId, updatedAt = null, matchedChannels = 0, ok = false, message = REMOVED)

    /** Short, fixed texts only: no exception message (which may carry a URL) is ever shown. */
    private fun describe(e: Exception): String = when (e) {
        is EpgHttpException -> "The TV guide server returned an error (${e.code})."
        is XmltvTooLargeException -> "The TV guide file is too large."
        is XmlPullParserException, is ZipException -> DAMAGED
        is IOException -> "Couldn't reach the TV guide server."
        else -> "Something went wrong while loading the TV guide."
    }

    private companion object {
        /** Kept after the retention so the guide's current window has no hole at its left edge. */
        val GRACE: Duration = Duration.ofMinutes(30)
        const val REMOVED = "This playlist was removed."
        const val DAMAGED = "The TV guide file is damaged or not in XMLTV format."
    }
}

/** Stored as the status of an update that found no guide source (not shown on Settings → EPG). */
const val EPG_NO_SOURCE = "No TV guide source. Add one in Settings → EPG."

/** The source "URL" standing for a MAC portal's own guide (read through `portalEpg`, never downloaded). */
const val PORTAL_EPG_SOURCE = "portal:guide"

/** EPG URLs announced by [p] itself (M3U header, the Xtream `xmltv.php`, or the portal guide), untrimmed. */
internal fun announcedEpgUrls(p: PlaylistEntity, xtreamEpgUrl: (XtreamCredentials) -> String): List<String> =
    when (p.type) {
        PlaylistType.M3U, PlaylistType.M3U_FILE -> p.epgUrlsFromSource.lineSequence().toList()
        PlaylistType.XTREAM -> listOf(xtreamEpgUrl(playlistCredentials(p)))
        PlaylistType.STALKER -> listOf(PORTAL_EPG_SOURCE)
    }

/**
 * Every EPG source of [p] in reading order: its announced ones when [PlaylistEntity.useSourceEpg],
 * then the [added] links in position order; trimmed, non-blank, de-duplicated. `manualEpgUrl` is no
 * longer read (MIGRATION_3_4 moved it into `epg_sources`).
 */
internal fun epgSourceUrls(
    p: PlaylistEntity,
    xtreamEpgUrl: (XtreamCredentials) -> String,
    added: List<String>,
): List<String> =
    ((if (p.useSourceEpg) announcedEpgUrls(p, xtreamEpgUrl) else emptyList()) +
        // A typed "portal:guide" link must never reach the portal reader.
        added.map { it.trim() }.filter { it != PORTAL_EPG_SOURCE })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Literal secrets of [p]'s own source (M3U URL secrets, Xtream credentials, or portal address and credentials). */
internal fun playlistSecrets(p: PlaylistEntity): List<String> = when (p.type) {
    PlaylistType.M3U -> m3uSecrets(p.url.orEmpty())
    PlaylistType.XTREAM -> xtreamSecrets(playlistCredentials(p))
    PlaylistType.M3U_FILE -> emptyList()
    PlaylistType.STALKER -> with(p) {
        val addresses = listOfNotNull(url, portalEndpoint).map { it.trim() }.filter { it.isNotEmpty() }
        val hosts = addresses.mapNotNull { withHttpScheme(it).toHttpUrlOrNull()?.host }
        (listOfNotNull(mac, portalToken, portalLogin, portalPassword, portalSerial, portalDeviceId, portalDeviceId2, portalSignature) +
            addresses + hosts).distinct()
    }
}

internal fun playlistCredentials(p: PlaylistEntity) =
    XtreamCredentials(p.xtreamServer.orEmpty(), p.xtreamUser.orEmpty(), p.xtreamPass.orEmpty())
