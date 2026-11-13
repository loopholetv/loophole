package io.github.loopholetv.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lets a copied setup stop the playlist and guide updates in flight, shared by both repositories.
 * The updates it stops belong to playlists that are about to be replaced, and everything is
 * updated again afterwards, so waiting for them would only delay the guide.
 */
class RefreshStops {
    /** How many [during] calls are waiting or running: updates stop, and new ones don't start. */
    private val stopping = AtomicInteger(0)

    /** The updates running now, so [during] can stop them. Guarded by itself. */
    private val running = HashSet<Job>()

    /** Runs [block] with every update stopped, and none starting until it returns. */
    suspend fun <T> during(block: suspend () -> T): T {
        stopping.incrementAndGet()
        try {
            synchronized(running) { running.toList() }.forEach { it.cancel() }
            return block()
        } finally {
            stopping.decrementAndGet()
        }
    }

    /**
     * Runs [update] so that [during] can stop it. A stopped update returns [stopped] instead, and
     * its caller carries on; the caller's own cancellation still cancels it.
     */
    suspend fun <T> stoppable(stopped: () -> T, update: suspend () -> T): T {
        val job = Job(currentCoroutineContext()[Job])
        // Registered before the check, so a [during] that starts meanwhile either sees it or is seen.
        synchronized(running) { running += job }
        if (stopping.get() > 0) job.cancel()
        return try {
            withContext(job) { update() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            stopped()
        } finally {
            synchronized(running) { running -= job }
            job.complete()
        }
    }
}
