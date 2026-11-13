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
import io.github.loopholetv.data.stalker.normaliseMac
import io.github.loopholetv.data.stalker.portalConfig
import io.github.loopholetv.data.stalker.stalkerSecrets
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
import java.time.format.DateTimeFormatter
import java.util.Locale

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
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val maintenance = db.maintenanceDao()
    private val m3u = M3uSourceOpener(http)
    private val xtream = XtreamSourceOpener(xtream)
    private val mutex = Mutex()

    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.all()

    /** [progress] receives the running channel count after each batch (on a database thread). */
    suspend fun addM3u(name: String, rawUrl: String, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val url = withHttpScheme(rawUrl)
        runImport({ m3uSecrets(url) }) {
            m3u.open(url, downloadUa(null)).use { source ->
                importInto(source, progress) {
                    playlistDao.insert(newPlaylist(name, PlaylistType.M3U).copy(url = url))
                }
            }
        }
    }

    /** [creds] already default a scheme-less server address to http:// (see [XtreamCredentials]). */
    suspend fun addXtream(name: String, creds: XtreamCredentials, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        runImport({ xtreamSecrets(creds) }) {
            xtream.open(creds, downloadUa(null)).use { source ->
                importInto(source, progress) {
                    playlistDao.insert(
                        newPlaylist(name, PlaylistType.XTREAM).copy(
                            xtreamServer = creds.server,
                            xtreamUser = creds.username,
                            xtreamPass = creds.password,
                        ),
                    )
                }
            }
        }
    }

    /**
     * A local M3U file ([location]: `content://` URI, `file:` URI or absolute path). What was read
     * is kept as the playlist's last-good copy.
     */
    suspend fun addLocalFile(name: String, location: String, progress: (Int) -> Unit): ImportResult = mutex.withLock {
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
    suspend fun addStalker(name: String, cfg: PortalConfig, progress: (Int) -> Unit): ImportResult = mutex.withLock {
        val client = stalker ?: return@withLock unsupported()
        val config = cfg.normalised()
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
        val next = edit.applyTo(newPlaylist(old.name, old.type))
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
    suspend fun delete(playlistId: Long): Unit = mutex.withLock {
        // Once started, a delete finishes with its clean-up even if the caller goes away (Settings
        // closed): a cancelled clean-up would leak the file copy and the URI grant.
        withContext(NonCancellable) {
            val playlist = playlistDao.byId(playlistId)
            db.withTransaction {
                playlistDao.delete(playlistId)
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
                runImport({ xtreamSecrets(creds) }) {
                    xtream.open(creds, downloadUa(playlist)).use { importInto(it, progress) { playlist.id } }
                }
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
     * One transaction: obtain the playlist id ([playlistId] may insert the row), stream every
     * channel, prune vanished ones and mark the playlist refreshed. Throwing anywhere rolls it all back.
     *
     * After a source edit (`rekeyPending`), the carry-over moves edits, favourites and history from vanished
     * keys to new keys with the same name and group. The old list is read before the writer
     * streams, because [ChannelWriter.finish] deletes the vanished rows.
     */
    private suspend fun importInto(
        source: ChannelSource,
        progress: (Int) -> Unit,
        keepRefreshTime: Boolean = false,
        playlistId: suspend () -> Long,
    ): ImportResult.Success = db.withTransaction {
        val id = playlistId()
        val row = playlistDao.byId(id)
        val rekey = row?.rekeyPending == true
        val before = if (rekey) maintenance.keyNameGroups(id) else emptyList()
        val existing = channelDao.keyIds(id)
        val writer = ChannelWriter(channelDao, id, existing, progress)
        val epgUrls = pipe(produce = { emit -> source.stream(emit) }, consume = { writer.add(it) })
        val count = writer.finish()
        if (count == 0) throw ImportFailure(source.noChannelsError(), "The playlist contains no channels")
        if (rekey) carryOver(id, before, existing)
        // The writer overwrote the derived columns; re-derive the edits before the transaction commits.
        overlay.applyPlaylist(id); overlay.renumber()
        // A read from the last-good copy isn't a real update: the next scheduled one tries the file again.
        val at = if (keepRefreshTime) row?.lastRefreshedAt ?: now() else now()
        playlistDao.markRefreshed(id, at, epgUrls)
        source.archiveInfo()?.let { playlistDao.setArchiveInfo(id, it.zone, it.ext) }
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

    private companion object {
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
