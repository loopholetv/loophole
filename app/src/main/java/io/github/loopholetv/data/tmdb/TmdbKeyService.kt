package io.github.loopholetv.data.tmdb

import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.work.TmdbScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Settings page says about the TMDB key. */
enum class KeyStatus { NONE, CHECKING, WORKING, BAD_KEY, UNREACHABLE }

/**
 * The user's TMDB key: saving checks it with one request, and a key TMDB turned down is not used
 * again (by the lookups or a recheck) until a different key is saved or the key is removed. The
 * rejection is kept in memory only, so a restart tries the key once more.
 */
class TmdbKeyService(
    private val store: SettingsStore,
    private val client: TmdbClient,
    private val scheduler: TmdbScheduler,
) {
    private val _status = MutableStateFlow(KeyStatus.NONE)
    val status: StateFlow<KeyStatus> = _status.asStateFlow()

    private val lock = Mutex()

    /** The key TMDB refused; null when none was. */
    @Volatile private var rejected: String? = null

    /** The key last handed to a request, so [markBadKey] marks the one that was actually sent. */
    @Volatile private var lastUsed: String? = null

    /** The key to send: stored, of a known shape, and not refused. Null otherwise. */
    suspend fun key(): String? {
        val k = storedKey() ?: return null
        if (tmdbKeyType(k) == null || k == rejected) return null
        lastUsed = k
        return k
    }

    suspend fun hasKey(): Boolean = key() != null

    /**
     * Trims [raw] and saves it. Blank removes the key; a shape that is no TMDB key, or the key TMDB
     * already refused, is not accepted without a request. Otherwise the key is stored and checked,
     * and the lookups start when it works.
     */
    suspend fun save(raw: String): KeyStatus {
        val k = raw.trim()
        if (k.isEmpty()) {
            remove()
            return KeyStatus.NONE
        }
        return lock.withLock {
            if (tmdbKeyType(k) == null || k == rejected) {
                _status.value = KeyStatus.BAD_KEY
                return@withLock KeyStatus.BAD_KEY
            }
            rejected = null
            store.update { it.copy(tmdbKey = k) }
            check(k, kickOnWorking = true)
        }
    }

    suspend fun remove() = lock.withLock {
        store.update { it.copy(tmdbKey = "") }
        rejected = null
        lastUsed = null
        _status.value = KeyStatus.NONE
    }

    /** Checks the stored key again (the Settings page opened). A refused key is not sent again. */
    suspend fun recheck() {
        lock.withLock {
            val k = storedKey()
            when {
                k == null -> _status.value = KeyStatus.NONE
                tmdbKeyType(k) == null || k == rejected -> _status.value = KeyStatus.BAD_KEY
                // Kick only when it starts working, so opening the page doesn't queue a run each time.
                else -> check(k, kickOnWorking = _status.value != KeyStatus.WORKING)
            }
        }
    }

    /**
     * TMDB refused the key during a lookup: no more requests until the key changes. When no key is stored
     * (it was removed while the lookup ran, so the request went without one) nothing is recorded.
     */
    suspend fun markBadKey() {
        lock.withLock {
            val stored = try {
                storedKey()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable settings: the lookups find no key either.
                null
            }
            if (stored == null) {
                _status.value = KeyStatus.NONE
                return
            }
            val used = lastUsed ?: stored
            rejected = used
            // A key saved since that request has its own status.
            if (used == stored) _status.value = KeyStatus.BAD_KEY
        }
    }

    /** The key's last four characters, for the masked row; null when none is set. */
    suspend fun last4(): String? = storedKey()?.takeLast(4)

    override fun toString(): String = "TmdbKeyService(status=${_status.value})"

    private suspend fun storedKey(): String? = store.current().tmdbKey.trim().takeIf { it.isNotEmpty() }

    /** Runs under [lock]. */
    private suspend fun check(k: String, kickOnWorking: Boolean): KeyStatus {
        _status.value = KeyStatus.CHECKING
        val result = try {
            client.configuration()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TmdbResult.Failed(null)
        }
        val status = when (result) {
            is TmdbResult.Ok -> KeyStatus.WORKING
            TmdbResult.BadKey -> KeyStatus.BAD_KEY.also { rejected = k }
            else -> KeyStatus.UNREACHABLE
        }
        _status.value = status
        if (status == KeyStatus.WORKING && kickOnWorking) scheduler.kick()
        return status
    }
}
