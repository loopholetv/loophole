package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelKeyId
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.data.stalker.StalkerProtocolException
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.StalkerUnreachableException
import io.github.loopholetv.data.stalker.deriveIdentity
import io.github.loopholetv.data.stalker.normaliseMac
import io.github.loopholetv.data.stalker.portalConfig
import io.github.loopholetv.data.stalker.stalkerSecrets
import io.github.loopholetv.data.stalker.withDerivedIdentity
import io.github.loopholetv.data.vod.ORPHAN_PROGRESS_DAYS
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.xtream.XtreamAuthException
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.data.xtream.XtreamNetworkException
import io.github.loopholetv.data.xtream.XtreamProtocolException
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
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The films' and episodes' keys with the name and category (group) that identify them across a re-key. */
private class VodNameGroups(val films: List<KeyNameGroup>, val episodes: List<KeyNameGroup>)

/** A new source for an existing playlist ([PlaylistRepository.updateSource]); the type may change. */
sealed interface SourceEdit {
    data class M3uUrl(val url: String) : SourceEdit {
        override fun toString() = "SourceEdit.M3uUrl"
    }

    data class LocalFile(val location: String) : SourceEdit {
        override fun toString() = "SourceEdit.LocalFile"
    }

    data class Xtream(val creds: XtreamCredentials) : SourceEdit {
        override fun toString() = "SourceEdit.Xtream"
    }

    data class Portal(val cfg: PortalConfig) : SourceEdit {
        override fun toString() = "SourceEdit.Portal"
    }
}

/**
 * Adds, edits, refreshes and deletes playlists. Each add/refresh streams the source into the
 * database inside one transaction, so a failure at any point (including a dropped download) leaves
 * the previous state untouched. Failure messages are fixed text or redacted. Add, refresh, source
 * edits and delete run one at a time (a waiting call opens its request only once it holds the lock).
 *
 * A missing optional dependency ([local], [stalker], [sessions]) fails that type's import with
 * [ImportError.Unexpected], so tests that only need M3U/Xtream don't build them.
 */
class PlaylistRepository internal constructor(
    private val db: AppDatabase,
    http: OkHttpClient,
    xtream: XtreamClient,
    private val now: () -> Instant = Instant::now,
    private val overlay: ChannelOverlay = ChannelOverlay(db),
    private val local: LocalM3uOpener? = null,
    private val stalker: StalkerClient? = null,
    private val sessions: StalkerSessions? = null,
    /** The download user agent for a playlist (null while adding): playlist > general > default. */
    private val downloadUa: suspend (PlaylistEntity?) -> String = { "Loophole/test" },
    /** Shared with the guide's repository, so [exclusive] stops guide updates too. */
    private val stops: RefreshStops = RefreshStops(),
    /** Told the new playlist's id after each successful add; a failure in it is ignored. */
    private val onAdded: suspend (Long) -> Unit = {},
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val maintenance = db.maintenanceDao()
    private val vodDao = db.vodWriteDao()
    private val vodProgress = db.vodProgressDao()
    private val vodLibrary = db.vodLibraryDao()
    private val recordDao = db.recordDao()
    private val xtreamVodDao = db.xtreamVodDao()
    private val xtreamVod = XtreamVodImport(xtream, db, now)
    private val m3u = M3uSourceOpener(http)
    private val xtreamLive = XtreamSourceOpener(xtream)
    private val mutex = Mutex()

    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.all()

    // Adds: each reports a successful import to [onAdded] once the lock is released.
    suspend fun addM3u(name: String, rawUrl: String, progress: (Int) -> Unit): ImportResult = reportAdded(newM3u(name, rawUrl, progress))
    suspend fun addXtream(name: String, creds: XtreamCredentials, progress: (Int) -> Unit): ImportResult = reportAdded(newXtream(name, creds, progress))
    suspend fun addLocalFile(name: String, location: String, progress: (Int) -> Unit): ImportResult = reportAdded(newLocalFile(name, location, progress))
    suspend fun addStalker(name: String, cfg: PortalConfig, progress: (Int) -> Unit): ImportResult = reportAdded(newStalker(name, cfg, progress))

    private suspend fun reportAdded(result: ImportResult): ImportResult {
        if (result is ImportResult.Success) {
            try {
                onAdded(result.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A listener failure must not fail the add.
            }
        }
        return result
    }

    /** [progress] receives the running channel count after each batch (on a database thread). */
    private suspend fun newM3u(name: String, rawUrl: String, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val url = withHttpScheme(rawUrl)
        runImport({ m3uSecrets(url) }) {
            m3u.open(url, downloadUa(null)).use { source ->
                importInto(source, progress) {
                    playlistDao.insert(newPlaylist(name, PlaylistType.M3U).copy(url = url))
                }
            }
        }
    }

    /**
     * [creds] already default a scheme-less server address to http:// (see [XtreamCredentials]).
     * The live import commits the new row first, then the films and series are fetched; an account
     * with neither channels nor titles leaves no playlist behind.
     */
    private suspend fun newXtream(name: String, creds: XtreamCredentials, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        runImport({ xtreamSecrets(creds) }) {
            val ua = downloadUa(null)
            val row = newPlaylist(name, PlaylistType.XTREAM).copy(
                xtreamServer = creds.server,
                xtreamUser = creds.username,
                xtreamPass = creds.password,
            )
            val live = xtreamLive.open(creds, ua).use { source ->
                importInto(source, progress, m3uVod = false, allowEmpty = row.xtreamVod) { playlistDao.insert(row) }
            }
            val id = live.playlistId
            if (live.channelCount > 0) {
                updateXtreamVod(id, creds, ua, live.channelCount)
            } else {
                val vod = try {
                    xtreamVod.run(id, creds, ua, liveChannels = 0)
                } catch (e: Throwable) {
                    // Cancelled (or broken) before anything showed up: no empty playlist is left behind.
                    deleteLocked(id)
                    throw e
                }
                if (vod.films + vod.shows == 0) {
                    deleteLocked(id)
                    // Neither list could be read: that is the error, not "no channels".
                    throw vod.bothFailed ?: ImportFailure(ImportError.EmptyPlaylist, NO_CHANNELS)
                }
                recordVod(id, vod)
            }
            live
        }
    }

    /**
     * A local M3U file ([location]: `content://` URI, `file:` URI or absolute path). What was read
     * is kept as the playlist's last-good copy.
     */
    private suspend fun newLocalFile(name: String, location: String, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val opener = local ?: return@withLock unsupported()
        val loc = location.trim()
        runImport({ localSecrets(loc) }) {
            opener.open(loc, null).use { source ->
                importInto(source, progress) {
                    playlistDao.insert(newPlaylist(name, PlaylistType.M3U_FILE).copy(url = loc))
                }.also { keepCopy(source, it.playlistId) }
            }
        }
    }

    /**
     * A MAC portal: finds the portal endpoint and authenticates before the import transaction opens,
     * then stores the endpoint and token with the new row.
     */
    private suspend fun newStalker(name: String, cfg: PortalConfig, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val client = stalker ?: return@withLock unsupported()
        // The one place a NEW portal is created: blank device details are derived here and saved.
        val config = cfg.normalised().withDerivedIdentity()
        var token: String? = null
        runImport({ stalkerSecrets(config, token) }) {
            val endpoint = client.discover(config.portalUrl, config.mac)
            val session = client.authenticate(endpoint, config, savedToken = null)
            token = session.token
            val stored = session.token
            val result = StalkerSource(client, session, config).use { source ->
                importInto(source, progress) {
                    playlistDao.insert(
                        newPlaylist(name, PlaylistType.STALKER).withPortal(config).copy(
                            portalEndpoint = session.endpoint,
                            portalToken = stored,
                        ),
                    )
                }
            }
            // A re-handshake while streaming changed the in-memory token: save it (outside the transaction).
            if (session.token != stored) {
                token = session.token
                playlistDao.setPortalSession(result.playlistId, session.endpoint, session.token)
            }
            result
        }
    }

    /**
     * On failure the old channels stay and the redacted message is stored as `lastError`. A local
     * file that couldn't be opened is read from its last-good copy: that is a success, with
     * "Couldn't open the file. Using the copy from <d MMM>." as `lastError`. Works for a
     * turned-off playlist too (a manual update); only automatic updates skip those.
     */
    suspend fun refresh(playlistId: Long, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        stoppable { refreshLocked(playlistId, progress) }
    }

    /**
     * Saves a new address, file or login (the type may change), marks the playlist for the key
     * carry-over, forgets any portal session and refreshes. On failure the new values stay, the
     * error is stored and the old channels stay; the carry-over then happens at the next
     * successful update.
     */
    suspend fun updateSource(playlistId: Long, edit: SourceEdit, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val old = requireNotNull(playlistDao.byId(playlistId)) { "Unknown playlist $playlistId" }
        val next = edit.applyTo(newPlaylist(old.name, old.type)).withFollowedIdentity(old)
        maintenance.setSource(
            id = playlistId, type = next.type, url = next.url,
            xtreamServer = next.xtreamServer, xtreamUser = next.xtreamUser, xtreamPass = next.xtreamPass,
            mac = next.mac, login = next.portalLogin, password = next.portalPassword, serial = next.portalSerial,
            deviceId = next.portalDeviceId, deviceId2 = next.portalDeviceId2, signature = next.portalSignature,
        )
        sessions?.invalidate(playlistId)
        val oldLocation = old.url?.takeIf { old.type == PlaylistType.M3U_FILE }
        if (oldLocation != null && !(next.type == PlaylistType.M3U_FILE && next.url == oldLocation)) {
            // The old file's grant and copy belong to a location this playlist no longer reads.
            withContext(Dispatchers.IO) {
                releaseIfUnused(oldLocation)
                local?.deleteCopy(playlistId)
            }
        }
        stoppable { refreshLocked(playlistId, progress) }
    }

    /** A blank name is ignored. */
    suspend fun rename(playlistId: Long, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) playlistDao.rename(playlistId, n)
    }

    /** null or blank = use the general user agent. */
    suspend fun setUserAgent(playlistId: Long, ua: String?) = playlistDao.setUserAgent(playlistId, ua.blankToNull())

    /**
     * The "Films and series" setting of an Xtream playlist. Waits for a running update to finish.
     * Off removes the playlist's Xtream films, shows, episodes and provider details at once (watch
     * progress and My list stay); On fetches them at the next update.
     */
    suspend fun setXtreamVod(playlistId: Long, on: Boolean): Unit = mutex.withLock {
        if (on) {
            playlistDao.setXtreamVod(playlistId, true)
        } else {
            db.withTransaction {
                playlistDao.setXtreamVod(playlistId, false)
                xtreamVodDao.removeAll(playlistId)
                playlistDao.clearVodStatus(playlistId)
            }
        }
    }

    /** Null = "Same as default" for either option; [hours] 0 = Off. */
    suspend fun setUpdateOptions(playlistId: Long, hours: Int?, onStart: Boolean?) {
        require(hours == null || hours >= 0) { "Invalid update interval" }
        playlistDao.setUpdateOptions(playlistId, hours, onStart)
    }

    /**
     * Runs [block] with no add, update or delete of a playlist in flight, and none starting until
     * it returns (a copied setup replacing every playlist). A playlist or guide update that is
     * running is stopped rather than waited for, since the caller updates everything afterwards;
     * updates asked for meanwhile return at once without changing anything. Not reentrant: [block] must not call
     * this repository's other suspending writes.
     */
    suspend fun exclusive(block: suspend () -> Unit) {
        // A refresh still downloading would hold the lock for as long as its provider takes.
        stops.during { mutex.withLock { block() } }
    }

    /**
     * Runs a refresh so that [exclusive] can stop it. A stopped refresh changes nothing (its
     * transaction rolls back), stores no error and returns [ImportResult.Stopped]; its caller carries on.
     */
    private suspend fun stoppable(refresh: suspend () -> ImportResult): ImportResult =
        stops.stoppable({ ImportResult.Stopped }, refresh)

    /**
     * The remaining playlists' guide numbers close up. Programmes no remaining channel uses,
     * the file copy, the file grant and the portal session go too.
     */
    suspend fun delete(playlistId: Long): Unit = mutex.withLock { deleteLocked(playlistId) }

    private suspend fun deleteLocked(playlistId: Long) {
        // Once started, a delete finishes with its clean-up even if the caller goes away (Settings
        // closed): a cancelled clean-up would leak the file copy and the URI grant.
        withContext(NonCancellable) {
            val playlist = playlistDao.byId(playlistId)
            db.withTransaction {
                playlistDao.delete(playlistId)
                vodProgress.deleteForPlaylist(playlistId)
                vodLibrary.deleteForPlaylist(playlistId)
                recordDao.deletePendingForPlaylists(listOf(playlistId))
                overlay.renumber()
                maintenance.deleteUnreferencedProgrammes()
            }
            sessions?.invalidate(playlistId)
            withContext(Dispatchers.IO) {
                local?.deleteCopy(playlistId)
                if (playlist?.type == PlaylistType.M3U_FILE) playlist.url?.let { releaseIfUnused(it) }
            }
        }
    }

    // --- internals ---

    private suspend fun refreshLocked(playlistId: Long, progress: (Int) -> Unit): ImportResult {
        val playlist = requireNotNull(playlistDao.byId(playlistId)) { "Unknown playlist $playlistId" }
        var notice: String? = null
        val result = when (playlist.type) {
            PlaylistType.M3U -> {
                val url = playlist.url.orEmpty()
                runImport({ m3uSecrets(url) }) {
                    m3u.open(url, downloadUa(playlist)).use { importInto(it, progress) { playlist.id } }
                }
            }
            PlaylistType.XTREAM -> {
                val creds = playlistCredentials(playlist)
                runImport({ xtreamSecrets(creds) }) { refreshXtream(playlist.id, creds, downloadUa(playlist), progress) }
            }
            PlaylistType.M3U_FILE -> {
                val opener = local
                val location = playlist.url.orEmpty()
                if (opener == null) {
                    unsupported()
                } else {
                    runImport({ localSecrets(location) }) {
                        opener.open(location, playlist.id).use { source ->
                            importInto(source, progress, keepRefreshTime = source.usedCopyFrom != null) { playlist.id }.also {
                                keepCopy(source, playlist.id)
                                notice = source.usedCopyFrom?.let(::copyNotice)
                            }
                        }
                    }
                }
            }
            PlaylistType.STALKER -> {
                val client = stalker
                val portal = sessions
                if (client == null || portal == null) {
                    unsupported()
                } else {
                    runImport({ playlistSecrets(playlist) + stalkerSecrets(playlist.portalConfig(), playlist.portalToken) }) {
                        // The session (and any token save) is acquired before the transaction opens;
                        // its auth retry also covers the channel stream.
                        portal.withSession(playlist.id) { session, cfg ->
                            StalkerSource(client, session, cfg).use { importInto(it, progress) { playlist.id } }
                        }
                    }
                }
            }
        }
        when (result) {
            is ImportResult.Failure -> playlistDao.setError(playlist.id, result.message)
            is ImportResult.Success -> notice?.let { playlistDao.setError(playlist.id, it) }
            ImportResult.Stopped -> Unit
        }
        return result
    }

    /**
     * An Xtream refresh: the live import commits first, then the films and series follow (or are
     * removed when the setting is Off). With no live channels the live import is rolled back. A
     * playlist that had channels keeps them: the live part fails as [ImportError.EmptyPlaylist], but
     * the films and series are still updated. One that had none is committed empty if the account
     * lists films or shows; otherwise it fails the same way (or with the lists' error when neither
     * could be read).
     */
    private suspend fun refreshXtream(id: Long, creds: XtreamCredentials, ua: String, progress: (Int) -> Unit): ImportResult.Success =
        xtreamLive.open(creds, ua).use { source ->
            val live = try {
                importInto(source, progress, m3uVod = false) { id }
            } catch (empty: LiveEmpty) {
                val hadChannels = channelDao.keyIds(id).isNotEmpty()
                val vod = xtreamVod.run(id, creds, ua, liveChannels = 0)
                val listed = vod.films + vod.shows > 0
                if (hadChannels && (listed || vod.failed)) recordVod(id, vod)
                if (hadChannels || !listed) throw vod.bothFailed ?: ImportFailure(ImportError.EmptyPlaylist, NO_CHANNELS)
                return@use importInto(NoLiveChannels(source, empty.epgUrls), progress, m3uVod = false, allowEmpty = true) { id }
                    .also { recordVod(id, vod) }
            }
            updateXtreamVod(id, creds, ua, live.channelCount)
            live
        }

    /** Stores how a films-and-series update went: its error, or the time of a complete one. */
    private suspend fun recordVod(id: Long, vod: XtreamVodResult) {
        if (vod.failed) playlistDao.setVodError(id, VOD_NOT_UPDATED) else playlistDao.setVodUpdated(id, now())
    }

    /** After a committed live import: fetches the films and series, or removes them when the setting is Off. */
    private suspend fun updateXtreamVod(id: Long, creds: XtreamCredentials, ua: String, liveChannels: Int) {
        if (playlistDao.byId(id)?.xtreamVod == false) {
            db.withTransaction {
                xtreamVodDao.removeAll(id)
                playlistDao.clearVodStatus(id)
            }
            return
        }
        val vod = xtreamVod.run(id, creds, ua, liveChannels)
        recordVod(id, vod)
    }

    /**
     * One transaction: obtain the playlist id ([playlistId] may insert the row), stream every
     * channel, prune vanished ones and mark the playlist refreshed. Throwing anywhere rolls it all back.
     *
     * After a source edit (`rekeyPending`), the carry-over moves edits, favourites and history from vanished
     * keys to new keys with the same name and group. The old list is read before the writer
     * streams, because [ChannelWriter.finish] deletes the vanished rows.
     *
     * [m3uVod] false (Xtream): films and series come later from [XtreamVodImport], so playlist-entry
     * films and episodes are removed instead of written. [allowEmpty] commits a source with nothing in
     * it; otherwise an Xtream playlist with "Films and series" On throws [LiveEmpty] (rolled back) so
     * its films and series can still be fetched.
     */
    private suspend fun importInto(
        source: ChannelSource,
        progress: (Int) -> Unit,
        keepRefreshTime: Boolean = false,
        m3uVod: Boolean = true,
        allowEmpty: Boolean = false,
        playlistId: suspend () -> Long,
    ): ImportResult.Success = db.withTransaction {
        val id = playlistId()
        val row = playlistDao.byId(id)
        val rekey = row?.rekeyPending == true
        // A playlist that stopped being Xtream: its Xtream titles go first, so the M3U writer neither
        // carries their progress over nor counts them as already known.
        if (m3uVod) xtreamVodDao.removeAll(id) else xtreamVodDao.removeM3uVod(id)
        val before = if (rekey) maintenance.keyNameGroups(id) else emptyList()
        val existing = channelDao.keyIds(id)
        val vodBefore = if (rekey && m3uVod) vodNameGroups(id) else null
        val writer = ChannelWriter(channelDao, id, existing, progress)
        val vodWriter = if (m3uVod) VodWriter(vodDao, db.channelPrefsDao(), id, now(), rekey) else null
        val epgUrls = pipe<ImportItem, String>(
            produce = { emit -> source.stream(emit) },
            consume = {
                when (it) {
                    is ImportedChannel -> writer.add(it)
                    is ImportedVod -> vodWriter?.add(it)
                }
            },
        )
        val count = writer.finish()
        val vodCount = vodWriter?.finish() ?: 0
        if (count == 0 && vodCount == 0 && !allowEmpty) {
            if (!m3uVod && row?.xtreamVod == true) throw LiveEmpty(epgUrls)
            throw ImportFailure(source.noChannelsError(), NO_CHANNELS)
        }
        xtreamVodDao.deleteDetailsWithoutFilm(id)
        if (rekey) carryOver(id, before, existing)
        if (vodBefore != null) carryOverProgress(id, vodBefore)
        val orphanCutoff = now().minus(ORPHAN_PROGRESS_DAYS.toLong(), ChronoUnit.DAYS)
        vodProgress.deleteOrphans(orphanCutoff)
        vodLibrary.deleteOrphans(orphanCutoff)
        // The writer overwrote the derived columns; re-derive the edits before the transaction commits.
        overlay.applyPlaylist(id); overlay.renumber()
        // A read from the last-good copy isn't a real update: the next scheduled one tries the file again.
        val at = if (keepRefreshTime) row?.lastRefreshedAt ?: now() else now()
        playlistDao.markRefreshed(id, at, epgUrls)
        source.archiveInfo()?.let {
            playlistDao.setArchiveInfo(id, it.zone, it.ext)
            playlistDao.setAccountMaxConnections(id, it.maxConnections)
        }
        source.accountInfo()?.let { playlistDao.setAccountInfo(id, it.status, it.expiresAt) }
        if (rekey) playlistDao.setRekeyPending(id, false)
        ImportResult.Success(id, count)
    }

    /**
     * The key carry-over inside the import transaction: edits, favourites and history move only for keys that have
     * any (a 10k-channel re-key stays cheap), and the automatic guide match moves for every pair,
     * so the guide keeps showing programmes before the next EPG update.
     */
    private suspend fun carryOver(id: Long, before: List<KeyNameGroup>, existing: List<ChannelKeyId>) {
        val plan = carryOverPlan(before, maintenance.keyNameGroups(id))
        if (plan.isEmpty()) return
        val withData = maintenance.keysWithUserData(id).toHashSet()
        val oldEpg = existing.associate { it.key to it.epgChannelId }
        for ((from, to) in plan) {
            if (from in withData) maintenance.rekey(id, from, to)
            oldEpg[from]?.let { maintenance.carryEpgMatch(id, to, it) }
        }
    }

    private suspend fun vodNameGroups(id: Long) =
        VodNameGroups(vodDao.filmNameGroups(id), vodDao.episodeNameGroups(id))

    /** Viewing progress, My list and dismissals follow their film or episode to the new key, by the same name-and-group rule as channels. */
    private suspend fun carryOverProgress(id: Long, before: VodNameGroups) {
        val after = vodNameGroups(id)
        for ((from, to) in carryOverPlan(before.films, after.films)) {
            vodProgress.rekey(VodKind.MOVIE, id, from, to)
            vodLibrary.rekeyFilm(id, from, to)
        }
        for ((from, to) in carryOverPlan(before.episodes, after.episodes)) vodProgress.rekey(VodKind.EPISODE, id, from, to)
    }

    /** Gives back a file's read grant unless another playlist still reads the same file. */
    private suspend fun releaseIfUnused(location: String) {
        val opener = local ?: return
        if (maintenance.countUsing(PlaylistType.M3U_FILE, location) == 0) opener.release(location)
    }

    /** After the import committed: the file read becomes the playlist's last-good copy. */
    private suspend fun keepCopy(source: LocalM3uSource, playlistId: Long) =
        withContext(Dispatchers.IO) { source.keepCopyAs(playlistId) }

    private fun copyNotice(at: Instant): String =
        "$COPY_NOTICE_PREFIX${COPY_DATE.format(at.atZone(ZoneId.systemDefault()))}."

    /** [secrets] is read only on failure, so values learnt during the import (a portal token) count. */
    private suspend fun runImport(secrets: () -> List<String>, block: suspend () -> ImportResult.Success): ImportResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val (error, message) = classify(e)
            ImportResult.Failure(error, redact(message, secrets()))
        }

    /** Anything not recognised (a bug, a database error) becomes [ImportError.Unexpected] with a generic message. */
    private fun classify(e: Exception): Pair<ImportError, String> = when (e) {
        is ImportFailure -> e.error to e.message.orEmpty()
        is XtreamAuthException -> ImportError.AuthFailed to e.message.orEmpty()
        is XtreamProtocolException -> ImportError.NotAPlaylist to e.message.orEmpty()
        is XtreamNetworkException -> ImportError.Unreachable to "Could not reach the server: ${e.message}"
        // Portal failures get fixed text, never the exception's own.
        is StalkerAuthException -> ImportError.AuthFailed to PORTAL_AUTH_FAILED
        is StalkerUnreachableException -> ImportError.Unreachable to PORTAL_UNREACHABLE
        is StalkerProtocolException -> ImportError.NotAPlaylist to PORTAL_NOT_A_PORTAL
        is StalkerLinkException -> ImportError.Unexpected to PORTAL_UNEXPECTED
        is IOException -> ImportError.Unreachable to "Could not reach the server: ${e.message ?: e.javaClass.simpleName}"
        else -> ImportError.Unexpected to GENERIC_FAILURE
    }

    private fun unsupported() = ImportResult.Failure(ImportError.Unexpected, UNSUPPORTED)

    private fun newPlaylist(name: String, type: PlaylistType) = PlaylistEntity(
        name = name,
        type = type,
        url = null,
        xtreamServer = null,
        xtreamUser = null,
        xtreamPass = null,
        epgUrlsFromSource = "",
        manualEpgUrl = null,
        lastRefreshedAt = null,
        lastError = null,
    )

    /** The source columns [this] edit sets on a blank row (everything else stays null). */
    private fun SourceEdit.applyTo(blank: PlaylistEntity): PlaylistEntity = when (this) {
        is SourceEdit.M3uUrl -> blank.copy(type = PlaylistType.M3U, url = withHttpScheme(url.trim()))
        is SourceEdit.LocalFile -> blank.copy(type = PlaylistType.M3U_FILE, url = location.trim())
        is SourceEdit.Xtream -> blank.copy(
            type = PlaylistType.XTREAM,
            xtreamServer = creds.server,
            xtreamUser = creds.username,
            xtreamPass = creds.password,
        )
        is SourceEdit.Portal -> blank.copy(type = PlaylistType.STALKER).withPortal(cfg.normalised())
    }

    /**
     * A MAC change moves the device details that were derived from the old MAC to the new one.
     * A detail that differs from what the old MAC derives (typed, or blank on an older portal) stays.
     */
    private fun PlaylistEntity.withFollowedIdentity(old: PlaylistEntity): PlaylistEntity {
        if (type != PlaylistType.STALKER || old.type != PlaylistType.STALKER) return this
        val oldMac = old.mac?.let { normaliseMac(it) } ?: return this
        val newMac = mac?.let { normaliseMac(it) } ?: return this
        if (newMac == oldMac) return this
        val was = deriveIdentity(oldMac) ?: return this
        val now = deriveIdentity(newMac) ?: return this
        fun follow(stored: String?, next: String?, derivedOld: String, derivedNew: String) =
            if (stored == derivedOld && (next.isNullOrBlank() || next == stored)) derivedNew else next
        return copy(
            portalSerial = follow(old.portalSerial, portalSerial, was.serial, now.serial),
            portalDeviceId = follow(old.portalDeviceId, portalDeviceId, was.deviceId, now.deviceId),
            portalDeviceId2 = follow(old.portalDeviceId2, portalDeviceId2, was.deviceId2, now.deviceId2),
            portalSignature = follow(old.portalSignature, portalSignature, was.signature, now.signature),
        )
    }

    private fun PlaylistEntity.withPortal(cfg: PortalConfig) = copy(
        url = cfg.portalUrl,
        mac = cfg.mac,
        portalLogin = cfg.login,
        portalPassword = cfg.password,
        portalSerial = cfg.serial,
        portalDeviceId = cfg.deviceId,
        portalDeviceId2 = cfg.deviceId2,
        portalSignature = cfg.signature,
    )

    /** Trimmed, the MAC in its canonical form when it parses, blank identity values as null. */
    private fun PortalConfig.normalised() = PortalConfig(
        portalUrl = portalUrl.trim(),
        mac = normaliseMac(mac) ?: mac.trim(),
        login = login.blankToNull(),
        password = password.blankToNull(),
        serial = serial.blankToNull(),
        deviceId = deviceId.blankToNull(),
        deviceId2 = deviceId2.blankToNull(),
        signature = signature.blankToNull(),
    )

    /** The live import found no channels, but the playlist's films and series may still fill it. */
    private class LiveEmpty(val epgUrls: String) : Exception()

    /** Replays [source]'s account and archive details, and [epgUrls], with no channels (a films-and-series-only account). */
    private class NoLiveChannels(private val source: ChannelSource, private val epgUrls: String) : ChannelSource {
        override suspend fun stream(emit: (ImportItem) -> Unit): String = epgUrls
        override fun noChannelsError(): ImportError = source.noChannelsError()
        override fun archiveInfo() = source.archiveInfo()
        override fun accountInfo() = source.accountInfo()
    }

    private companion object {
        const val NO_CHANNELS = "The playlist contains no channels"
        const val GENERIC_FAILURE = "Something went wrong while loading the playlist."
        const val UNSUPPORTED = "This playlist type isn't supported yet."
        val COPY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}

// A MAC portal's failures carry fixed copy (in `ImportResult.Failure.message` and the stored
// `lastError`), shown as they are instead of the generic `errorCopy`.
internal const val PORTAL_AUTH_FAILED = "The portal refused this MAC address or login."
internal const val PORTAL_UNREACHABLE = "Couldn't reach the portal."
internal const val PORTAL_NOT_A_PORTAL = "No MAC portal was found at this address."
internal const val PORTAL_UNEXPECTED = "The portal sent an answer Loophole doesn't understand."
internal val PORTAL_ERROR_COPY: Set<String> = setOf(PORTAL_AUTH_FAILED, PORTAL_UNREACHABLE, PORTAL_NOT_A_PORTAL, PORTAL_UNEXPECTED)

/** A successful read of a local file's last-good copy stores "<prefix><d MMM>." as `lastError`. */
internal const val COPY_NOTICE_PREFIX = "Couldn't open the file. Using the copy from "
