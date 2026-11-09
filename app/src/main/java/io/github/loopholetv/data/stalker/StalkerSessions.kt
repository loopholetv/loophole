package io.github.loopholetv.data.stalker

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One portal session per playlist (one per MAC), kept in memory.
 *
 * Single-flight covers only acquisition (probe → handshake → `get_profile` → `get_events init=1`);
 * `block` runs outside the per-playlist lock, so a long channel import never delays a
 * play-time `create_link`. Every handshake/get_profile — acquisition, in-call re-handshake and the
 * retry below — also runs under [StalkerClient]'s per-MAC lock, so they never overlap for one MAC.
 * [save] is called only from acquisition, never from inside `block`: a token changed by a
 * re-handshake is persisted on the next acquisition.
 */
class StalkerSessions(
    private val client: StalkerClient,
    private val load: suspend (Long) -> PlaylistEntity?,
    private val save: suspend (playlistId: Long, endpoint: String, token: String) -> Unit,
) {
    private class Entry(val session: StalkerSession, val cfg: PortalConfig, @Volatile var savedToken: String?)

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val locks = ConcurrentHashMap<Long, Mutex>()
    /** Bumped by [invalidate]; an acquisition that started before it doesn't store its entry. */
    private val epochs = ConcurrentHashMap<Long, AtomicLong>()

    /**
     * Runs [block] with this playlist's session, creating it if needed. A retryable
     * [StalkerAuthException] from [block] (the session still failed after a re-handshake)
     * re-handshakes the session once more — unless another caller already did — and runs [block]
     * once more. Terminal auth failures (refused, denied, wrong login, `cut_off`) are not retried.
     */
    suspend fun <T> withSession(playlistId: Long, block: suspend (StalkerSession, PortalConfig) -> T): T {
        val entry = acquire(playlistId)
        val generation = entry.session.generation
        return try {
            block(entry.session, entry.cfg)
        } catch (e: StalkerAuthException) {
            if (!e.retryable) throw e
            try {
                client.refresh(entry.session, entry.cfg, generation)
            } catch (t: Throwable) {
                entries.remove(playlistId, entry)
                throw t
            }
            block(entry.session, entry.cfg)
        }
    }

    /** Forgets the in-memory session (after an edit of the portal address, MAC or login, or a delete). */
    fun invalidate(playlistId: Long) {
        epochs.getOrPut(playlistId) { AtomicLong() }.incrementAndGet()
        entries.remove(playlistId)
    }

    /**
     * A Settings edit of the portal (or a delete) may have landed while this
     * acquisition was talking to the old portal. Its endpoint and token are saved only when nothing
     * invalidated the session since [epoch] and the stored row still describes [cfg]; otherwise the
     * old portal's token would be written onto the edited row.
     */
    private suspend fun stillCurrent(playlistId: Long, epoch: Long, cfg: PortalConfig): Boolean =
        epochs.getValue(playlistId).get() == epoch && load(playlistId)?.portalConfig() == cfg

    private suspend fun acquire(playlistId: Long): Entry =
        locks.getOrPut(playlistId) { Mutex() }.withLock {
            entries[playlistId]?.let { e ->
                if (e.session.token != e.savedToken) {
                    save(playlistId, e.session.endpoint, e.session.token)
                    e.savedToken = e.session.token
                }
                return@withLock e
            }
            val epoch = epochs.getOrPut(playlistId) { AtomicLong() }.get()
            val p = load(playlistId)
            if (p == null || p.type != PlaylistType.STALKER) throw StalkerProtocolException("This playlist isn't a MAC portal")
            val cfg = p.portalConfig()
            val saved = p.portalEndpoint?.takeIf { it.isNotBlank() }
            val session = if (saved != null) {
                try {
                    client.authenticate(saved, cfg, p.portalToken)
                } catch (_: StalkerProtocolException) {
                    // A stale endpoint (HTTP 404, HTML): probe again once.
                    client.authenticate(client.discover(cfg.portalUrl, cfg.mac), cfg, p.portalToken)
                }
            } else {
                client.authenticate(client.discover(cfg.portalUrl, cfg.mac), cfg, p.portalToken)
            }
            if ((session.endpoint != p.portalEndpoint || session.token != p.portalToken) && stillCurrent(playlistId, epoch, cfg)) {
                save(playlistId, session.endpoint, session.token)
            }
            Entry(session, cfg, session.token).also {
                // Invalidated meanwhile: hand this session to its caller only; the next call starts afresh.
                if (epochs.getValue(playlistId).get() == epoch) entries[playlistId] = it
            }
        }
}
