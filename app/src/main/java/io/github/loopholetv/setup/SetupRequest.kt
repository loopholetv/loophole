package io.github.loopholetv.setup

import androidx.annotation.WorkerThread
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * One pending request to receive a setup. It allows three wrong codes and a
 * limited time, and it can be used only once. Sealed blobs are bound to [id]
 * (see [SetupCrypto.aadFor]).
 *
 * [clock] must be monotonic (`SystemClock.elapsedRealtime`-style) so a wall-clock
 * change cannot stretch or shorten the lifetime; [expiresAtMs] is on the same clock.
 */
class SetupRequest(
    val id: String,
    val code: String,
    private val clock: () -> Long,
    val expiresAtMs: Long,
    private var attemptsLeft: Int = MAX_ATTEMPTS,
) {
    init {
        require(attemptsLeft > 0) { "attemptsLeft must be positive" }
    }

    sealed interface Outcome {
        /** The caller should `plain.fill(0)` once the setup has been imported. */
        class Accepted(val plain: ByteArray) : Outcome {
            override fun toString() = "Accepted"
            override fun equals(other: Any?) = other is Accepted && MessageDigest.isEqual(plain, other.plain)
            override fun hashCode() = plain.contentHashCode()
        }
        data class WrongCode(val left: Int) : Outcome
        data object Cancelled : Outcome
        data object Expired : Outcome
    }

    /** The final outcome once the request has ended; null while it is still open. */
    private val ended = AtomicReference<Outcome?>(null)
    private val guessLock = Any()

    /**
     * Tries a sealed bundle. Slow (key stretching): call from a background dispatcher.
     * Guesses are serialised so the attempt limit holds under parallel calls; [cancel]
     * never waits for them, and a cancel that lands while a guess is running wins.
     */
    @WorkerThread
    fun tryOpen(sealed: ByteArray): Outcome {
        ended.get()?.let { return endedAs(it) }
        if (clock() >= expiresAtMs) return end(Outcome.Expired)
        synchronized(guessLock) {
            ended.get()?.let { return endedAs(it) }
            val plain = SetupCrypto.open(sealed, code, SetupCrypto.aadFor(id))
            if (plain != null) {
                if (ended.compareAndSet(null, Outcome.Cancelled)) return Outcome.Accepted(plain)
                plain.fill(0)
                return endedAs(ended.get()!!)
            }
            attemptsLeft--
            if (attemptsLeft <= 0) return end(Outcome.Cancelled)
            ended.get()?.let { return endedAs(it) }
            return Outcome.WrongCode(attemptsLeft)
        }
    }

    /** Ends the request. Does not wait for a guess in progress, which then loses. */
    fun cancel() {
        ended.compareAndSet(null, Outcome.Cancelled)
    }

    private fun end(outcome: Outcome): Outcome {
        ended.compareAndSet(null, outcome)
        return endedAs(ended.get()!!)
    }

    // Expired stays Expired; every other ended state reads as Cancelled.
    private fun endedAs(outcome: Outcome): Outcome =
        if (outcome == Outcome.Expired) Outcome.Expired else Outcome.Cancelled

    /** Leaves out the id and the code: either lets another TV in. */
    override fun toString() = "SetupRequest(ended=${ended.get() != null})"

    companion object {
        const val MAX_ATTEMPTS = 3
        const val LIFETIME_MS = 2 * 60 * 1000L
    }
}
