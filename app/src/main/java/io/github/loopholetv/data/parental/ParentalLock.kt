package io.github.loopholetv.data.parental

import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the PIN currently allows. Held in memory only: a new process always starts [Locked] (or [NoPin]). */
sealed interface LockState {
    /** No PIN is set, so nothing is locked. */
    data object NoPin : LockState

    data object Locked : LockState

    /** The PIN was entered; [untilMs] (clock time) is when it locks again, or null for "until you leave Loophole". */
    data class Unlocked(val untilMs: Long?) : LockState
}

/** The result of checking a typed PIN. */
sealed interface PinCheck {
    data object Ok : PinCheck

    data object Wrong : PinCheck

    /** Too many wrong tries; checks are refused for another [seconds]. */
    data class Wait(val seconds: Int) : PinCheck
}

/**
 * Counts wrong PINs in a row: after [MAX_TRIES] the next [WAIT_MS] are refused, then the count starts over.
 * One per TV and one per phone session.
 */
class PinTries(private val clock: () -> Long) {
    private var wrong = 0
    private var refuseUntil: Long? = null

    /** Seconds left of a refusal (rounded up), or null when a try is allowed. */
    @Synchronized
    fun waitSeconds(): Int? {
        val until = refuseUntil ?: return null
        val left = until - clock()
        if (left <= 0) {
            refuseUntil = null
            return null
        }
        return ((left + 999) / 1000).toInt()
    }

    @Synchronized
    fun wrong() {
        if (++wrong >= MAX_TRIES) {
            wrong = 0
            refuseUntil = clock() + WAIT_MS
        }
    }

    @Synchronized
    fun reset() {
        wrong = 0
        refuseUntil = null
    }

    companion object {
        const val MAX_TRIES = 5
        const val WAIT_MS = 30_000L
    }
}

/**
 * The PIN and the unlock period. The database's `lock_state` row and the channel visibility derived
 * from it change together in one transaction, so there is never a committed moment where one says
 * locked and the other open.
 *
 * [clock] is in milliseconds and also times the unlock period: a coroutine on [scope] waits for
 * `untilMs - clock()` and then locks again.
 */
class ParentalLock(
    private val settings: SettingsStore,
    private val edits: ChannelEdits,
    private val overlay: ChannelOverlay,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<LockState>(LockState.Locked)

    /** Starts as [LockState.Locked] until [startLocked] has looked at the settings. */
    val state: StateFlow<LockState> = _state

    /** The PIN is entered and locked things are showing. False with no PIN: nothing is locked then. */
    val isUnlocked: Boolean get() = _state.value is LockState.Unlocked

    private val tries = PinTries(clock)
    private val mutex = Mutex()
    private var timer: Job? = null

    suspend fun hasPin(): Boolean = settings.current().parental.hasPin

    /** A raw check with no side effects: no counting, no unlocking. */
    suspend fun matches(pin: String): Boolean {
        val parental = settings.current().parental
        val hash = parental.pinHash ?: return false
        val salt = parental.pinSalt ?: return false
        return withContext(Dispatchers.Default) { PinHasher.matches(pin, salt, hash) }
    }

    /** Checks [pin] against the tries limit, and unlocks on [PinCheck.Ok]. */
    suspend fun check(pin: String): PinCheck {
        tries.waitSeconds()?.let { return PinCheck.Wait(it) }
        if (matches(pin)) {
            tries.reset()
            unlock()
            return PinCheck.Ok
        }
        tries.wrong()
        return tries.waitSeconds()?.let { PinCheck.Wait(it) } ?: PinCheck.Wrong
    }

    /** Sets (or changes) the PIN, which must be 4 digits, and unlocks. */
    suspend fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }) { "A PIN is $PIN_LENGTH digits" }
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        mutex.withLock {
            settings.update { it.copy(parental = it.parental.copy(pinHash = hash, pinSalt = salt)) }
            tries.reset()
            unlockLocked()
        }
    }

    /** Clears the PIN and every lock, and turns the Settings lock off. */
    suspend fun removeAll() = mutex.withLock {
        cancelTimer()
        edits.clearAllLocks()
        settings.update {
            it.copy(parental = it.parental.copy(pinHash = null, pinSalt = null, settingsLock = SettingsLock.OFF))
        }
        tries.reset()
        overlay.setLockState(unlocked = false)
        _state.value = LockState.NoPin
    }

    suspend fun unlock() = mutex.withLock { unlockLocked() }

    suspend fun relock() = mutex.withLock {
        cancelTimer()
        lockLocked()
    }

    /** Locks at start-up, whatever the previous run left behind. Must finish before anything reads channels. */
    suspend fun startLocked() = mutex.withLock {
        cancelTimer()
        _state.value = LockState.Locked
        writeLockedWithRetry()
        // Failing to read the settings must not open anything: stay locked.
        val pin = try {
            hasPin()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        if (!pin) _state.value = LockState.NoPin
    }

    /**
     * A setup import is about to commit (it can bring a PIN and locks): the in-memory state is [LockState.Locked]
     * from now on, so no play skips the lock lookup while they commit, even on a TV that had no PIN. The import
     * ends with [startLocked], which settles the state from the settings again, whether it worked or not.
     */
    suspend fun lockForImport() = mutex.withLock {
        cancelTimer()
        _state.value = LockState.Locked
    }

    /** Retries a failing write a few times; the last failure is thrown so the process doesn't run with stale visibility. */
    private suspend fun writeLockedWithRetry() {
        repeat(START_ATTEMPTS - 1) { attempt ->
            try {
                return overlay.setLockState(unlocked = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(START_BACKOFF_MS * (attempt + 1))
            }
        }
        overlay.setLockState(unlocked = false)
    }

    /** The app is no longer in front: lock again. The returned job ends when that is done. */
    fun onLeftApp(): Job = scope.launch { relock() }

    private suspend fun unlockLocked() {
        cancelTimer()
        val minutes = settings.current().parental.unlockMinutes
        val until = if (minutes == 0) null else clock() + minutes * MINUTE_MS
        overlay.setLockState(unlocked = true)
        _state.value = LockState.Unlocked(until)
        if (until != null) timer = scope.launch { lockWhenDue(until) }
    }

    /** Locks the state first so a failing write leaves it closed, then the database. */
    private suspend fun lockLocked() {
        _state.value = LockState.Locked
        overlay.setLockState(unlocked = false)
        if (!hasPin()) _state.value = LockState.NoPin
    }

    private suspend fun lockWhenDue(until: Long) {
        delay((until - clock()).coerceAtLeast(0))
        mutex.withLock {
            // Not the unlock this timer was made for: it was replaced or ended meanwhile.
            if ((_state.value as? LockState.Unlocked)?.untilMs != until) return
            // This coroutine is the timer, so it must not cancel itself before the write is done.
            timer = null
            lockLocked()
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val PIN_LENGTH = 4
        const val MINUTE_MS = 60_000L
        const val START_ATTEMPTS = 3
        const val START_BACKOFF_MS = 100L
    }
}
