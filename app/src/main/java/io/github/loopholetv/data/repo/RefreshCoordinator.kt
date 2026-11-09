package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Background (scheduled and app-start) playlist/EPG updates. Goes through the shared repositories,
 * whose Mutexes serialise it with Settings-triggered updates; refreshes never touch the stream
 * connection, so they may run while watching.
 *
 * Runs are serialised by [lock], and every due check reads settings, playlists and EPG status inside
 * it right before the refresh it guards. So when the playlist and EPG jobs (or a job and the startup
 * run) fire together, the second sees the first's fresh times and does nothing. The chained EPG update is also
 * skipped when the EPG was updated within [START_GAP] (e.g. by Settings or a job just before).
 *
 * Never throws except [CancellationException]: repositories persist their own failures, and anything
 * unexpected (e.g. a playlist deleted mid-run) skips that playlist without logging, since messages
 * may embed source URLs with credentials.
 *
 * Last-success times: playlist = [PlaylistEntity.lastRefreshedAt]; EPG = [EpgStatusEntity.updatedAt]
 * (set on full and partial success, kept on failure, null when there is no guide → due at once).
 */
class RefreshCoordinator(
    private val playlists: suspend () -> List<PlaylistEntity>,
    private val epgStatus: suspend (Long) -> EpgStatusEntity?,
    private val refreshPlaylistFn: suspend (Long, (Int) -> Unit) -> ImportResult,
    private val refreshEpgFn: suspend (Long, (Int) -> Unit) -> EpgStatusEntity,
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Instant = Instant::now,
    private val updateSourceFn: suspend (Long, SourceEdit, (Int) -> Unit) -> ImportResult =
        { _, _, _ -> throw UnsupportedOperationException("No source editing") },
) {
    constructor(
        playlists: PlaylistRepository,
        epg: EpgRepository,
        settings: SettingsStore,
        clock: () -> Instant = Instant::now,
    ) : this(
        playlists = { playlists.playlists().first() },
        epgStatus = { epg.status(it).first() },
        refreshPlaylistFn = { id, progress -> playlists.refresh(id, progress) },
        refreshEpgFn = { id, progress -> epg.refresh(id, progress) },
        settings = { settings.current() },
        clock = clock,
        updateSourceFn = { id, edit, progress -> playlists.updateSource(id, edit, progress) },
    )

    /** Not reentrant: public entry points take it, the private helpers below assume it is held. */
    private val lock = Mutex()

    /**
     * Refreshes one playlist, then (when enabled, successful and the EPG wasn't just updated) its
     * EPG. A repository exception becomes [ImportError.Unexpected] (its message is not kept: it may
     * hold credentials). With [chainEpg] false (Settings' manual "Update playlist") the playlist is
     * refreshed under the lock and the caller updates the guide itself (always, with progress).
     */
    suspend fun refreshPlaylist(id: Long, progress: (Int) -> Unit = {}, chainEpg: Boolean = true): ImportResult =
        lock.withLock {
            if (chainEpg) {
                refreshPlaylistLocked(id, progress)
            } else {
                guarded { refreshPlaylistFn(id, progress) } ?: ImportResult.Failure(ImportError.Unexpected, "Playlist update failed")
            }
        }

    /**
     * Settings: saves an edited address, file or login and refreshes the playlist (with the key carry-over),
     * then — the source changed, so its guide may have too — the EPG chain without the
     * "recently updated" skip, when enabled and the playlist is turned on.
     */
    suspend fun updateSource(id: Long, edit: SourceEdit, progress: (Int) -> Unit = {}): ImportResult =
        lock.withLock {
            val result = guarded { updateSourceFn(id, edit, progress) }
                ?: return@withLock ImportResult.Failure(ImportError.Unexpected, "Playlist update failed")
            if (result is ImportResult.Success) chainEpg(id, skipIfRecent = false)
            result
        }

    /** Null only when the repository threw (e.g. the playlist was deleted). */
    suspend fun refreshEpg(id: Long, progress: (Int) -> Unit = {}): EpgStatusEntity? =
        lock.withLock { guarded { refreshEpgFn(id, progress) } }

    /**
     * Settings → "Update all": every turned-on playlist in id order, one at a time, each followed by
     * its EPG chain. [progress] gets the playlist id and its running channel count. Returns
     * each playlist's result.
     */
    suspend fun refreshAll(progress: (playlistId: Long, count: Int) -> Unit = { _, _ -> }): Map<Long, ImportResult> =
        lock.withLock {
            val ids = guarded { playlists().filter { it.enabled }.map { it.id }.sorted() } ?: return@withLock emptyMap()
            val results = LinkedHashMap<Long, ImportResult>()
            for (id in ids) {
                // Re-read: a playlist turned off or deleted meanwhile (e.g. from Settings) is skipped.
                if (guarded { playlists().firstOrNull { it.id == id }?.enabled == true } != true) continue
                results[id] = refreshPlaylistLocked(id) { progress(id, it) }
            }
            results
        }

    /**
     * Scheduled run: refreshes every turned-on playlist whose [kind] is due, checked right before
     * each refresh. A playlist's own interval wins over the general one; turned-off playlists
     * are never updated automatically.
     */
    suspend fun runDue(kind: RefreshKind): Unit = lock.withLock {
        val s = guarded { settings() } ?: return@withLock
        val ids = guarded { playlists().map { it.id } } ?: return@withLock
        for (id in ids) {
            guarded {
                val p = playlists().firstOrNull { it.id == id } // fresh; null = deleted
                if (p != null && p.enabled) {
                    when (kind) {
                        RefreshKind.PLAYLIST ->
                            if (isDue(p.lastRefreshedAt, effectiveHours(p, s), clock())) refreshPlaylistLocked(id) {}
                        RefreshKind.EPG ->
                            if (isDue(epgStatus(id)?.updatedAt, s.epgUpdateHours, clock())) refreshEpgFn(id) {}
                    }
                }
                Unit
            }
        }
    }

    /**
     * App-start run, per playlist: the playlist when [startRefreshWanted]; then the EPG when wanted on
     * its own or chained after a playlist refresh, at most once. The EPG is skipped after the playlist was unreachable or
     * rejected our credentials (its source is then very likely unreachable too; old data stays), and
     * when the playlist refresh threw (e.g. deleted mid-run).
     */
    suspend fun runStartup(): Unit = lock.withLock {
        val s = guarded { settings() } ?: return@withLock
        val ids = guarded { playlists().map { it.id } } ?: return@withLock
        for (id in ids) {
            guarded { startupOne(id, s) }
        }
    }

    /** A manual update of a turned-off playlist works, but doesn't chain its EPG. */
    private suspend fun refreshPlaylistLocked(id: Long, progress: (Int) -> Unit): ImportResult {
        val result = guarded { refreshPlaylistFn(id, progress) }
            ?: return ImportResult.Failure(ImportError.Unexpected, "Playlist update failed")
        if (result is ImportResult.Success) chainEpg(id, skipIfRecent = true)
        return result
    }

    /** The EPG after a playlist change, when enabled and the playlist is turned on. */
    private suspend fun chainEpg(id: Long, skipIfRecent: Boolean) {
        if (guarded { settings() }?.epgUpdateOnPlaylistChange != true) return
        guarded {
            val enabled = playlists().firstOrNull { it.id == id }?.enabled == true
            if (enabled && !(skipIfRecent && epgRecentlyUpdated(id))) refreshEpgFn(id) {}
            Unit
        }
    }

    private suspend fun startupOne(id: Long, s: AppSettings) {
        val p = playlists().firstOrNull { it.id == id } ?: return
        if (!p.enabled) return
        var chained = false
        if (startRefreshWanted(effectiveOnStart(p, s), p.lastRefreshedAt, effectiveHours(p, s), clock())) {
            val result = refreshPlaylistFn(id) {}
            if (result is ImportResult.Failure &&
                (result.error == ImportError.AuthFailed || result.error == ImportError.Unreachable)
            ) {
                return
            }
            chained = result is ImportResult.Success && s.epgUpdateOnPlaylistChange
        }
        // Read after the playlist update, so an EPG refreshed meanwhile (e.g. by Settings) counts.
        val epgLast = epgStatus(id)?.updatedAt
        val epgWanted = startRefreshWanted(s.epgUpdateOnStart, epgLast, s.epgUpdateHours, clock())
        if (epgWanted || (chained && !recent(epgLast))) refreshEpgFn(id) {}
    }

    private suspend fun epgRecentlyUpdated(id: Long) = recent(epgStatus(id)?.updatedAt)

    private fun recent(at: Instant?) = at != null && clock().isBefore(at.plus(START_GAP))

    private suspend fun <T : Any> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
