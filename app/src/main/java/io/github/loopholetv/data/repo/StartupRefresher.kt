package io.github.loopholetv.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.data.settings.VodFillFlags
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-off refresh of M3U playlists after the update that added films and shows: their entries were
 * imported as channels before, so each turned-on M3U and local-file playlist is refreshed once,
 * ignoring its interval. The flag is set only when every one of them succeeded, so a failure
 * retries on the next start. Xtream and portal playlists are not forced. A start with no such
 * playlist just sets the flag.
 *
 * A turned-off playlist's server is not contacted: it is noted as skipped instead, and refreshed by
 * [afterEnabled] when it is turned back on.
 */
class VodFill(
    private val playlists: suspend () -> List<PlaylistEntity>,
    private val refresh: suspend (Long) -> ImportResult,
    private val flags: VodFillFlags,
) {
    constructor(playlists: PlaylistRepository, coordinator: RefreshCoordinator, settings: SettingsStore) : this(
        playlists = { playlists.playlists().first() },
        refresh = { id -> coordinator.refreshPlaylist(id, chainEpg = false) },
        flags = settings,
    )

    /** Sets the flag without refreshing: a first start has nothing to fill. */
    suspend fun skip() = flags.markFilled(emptySet())

    suspend fun run() {
        if (flags.isFilled()) return
        val (on, off) = playlists().filter { it.type in FILLED_TYPES }.partition { it.enabled }
        var allDone = true
        for (p in on) {
            if (!refreshed(p.id)) allDone = false
        }
        if (allDone) flags.markFilled(off.mapTo(HashSet()) { it.id })
    }

    /** Playlist [id] was just turned on: if the fill left it out, it is refreshed now. */
    suspend fun afterEnabled(id: Long) {
        if (id !in flags.skipped()) return
        if (refreshed(id)) flags.clearSkipped(id)
    }

    private suspend fun refreshed(id: Long): Boolean = try {
        refresh(id) is ImportResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private companion object {
        val FILLED_TYPES = setOf(PlaylistType.M3U, PlaylistType.M3U_FILE)
    }
}

/**
 * Runs the app-start update ([RefreshCoordinator.runStartup]) once per process. Runs in [scope] (the
 * application scope), never blocks the UI and never throws: anything unexpected is swallowed (without
 * logging, since messages may embed source URLs).
 */
class StartupRefresher(
    private val run: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val vodFill: VodFill? = null,
) {
    constructor(coordinator: RefreshCoordinator, scope: CoroutineScope, vodFill: VodFill? = null) :
        this({ coordinator.runStartup() }, scope, vodFill)

    private val started = AtomicBoolean(false)

    /** Called instead of [start] when there is no playlist yet: nothing to fill afterwards. */
    fun markFilledWithoutPlaylists(): Job? = vodFill?.let { fill -> scope.launch { swallowing { fill.skip() } } }

    private suspend fun swallowing(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Intentionally ignored: see class doc.
        }
    }

    /** Idempotent: only the first call launches work. Returns the job (null on repeat calls). */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                // First, so the regular update below sees the fresh refresh time and does not repeat it.
                vodFill?.let { fill -> swallowing { fill.run() } }
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Intentionally ignored: see class doc.
            }
        }
    }
}
