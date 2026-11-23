package io.github.loopholetv.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.loopholetv.data.tmdb.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the TMDB details lookup in the background; a lookup already queued or running is kept. A kick
 * that arrives while a lookup runs is remembered, and [runTracked] queues one more run after it.
 */
class TmdbScheduler internal constructor(private val enqueue: (ExistingWorkPolicy) -> Unit) {
    constructor(workManager: WorkManager) : this({ policy -> workManager.enqueueUniqueWork(WORK_NAME, policy, request()) })

    private val pending = AtomicBoolean(false)

    fun kick() {
        pending.set(true)
        enqueue(ExistingWorkPolicy.KEEP)
    }

    /**
     * Runs [sync] once. When a kick arrived during it and the run ended Done, or Paused with no recording
     * left, one more run is queued behind this one (KEEP would drop it while this job is still running).
     */
    suspend fun runTracked(sync: suspend () -> SyncOutcome, recordingActive: () -> Boolean): SyncOutcome {
        pending.set(false)
        val outcome = sync()
        val again = when (outcome) {
            SyncOutcome.Done -> true
            SyncOutcome.Paused -> !recordingActive()
            else -> false
        }
        if (again && pending.getAndSet(false)) enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)
        return outcome
    }

    companion object {
        const val WORK_NAME = "tmdb-sync"

        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<TmdbWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
    }
}

/**
 * Calls [kick] each time the last running recording ends ([active] goes from non-empty to empty), so a
 * lookup paused for a recording carries on. A failing [kick] is ignored and the watch continues.
 */
fun kickWhenRecordingsEnd(active: Flow<Map<Long, *>>, kick: suspend () -> Unit, scope: CoroutineScope): Job =
    active.map { it.isNotEmpty() }
        .distinctUntilChanged()
        .runningFold(false to false) { (_, was), now -> was to now }
        .onEach { (was, now) ->
            if (was && !now) {
                try {
                    kick()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next recording's end, a refresh or the next start tries again.
                }
            }
        }
        .launchIn(scope)

/** Kicks when a key is set. Never throws except on cancellation: a failed kick waits for the next trigger. */
suspend fun kickIfKey(hasKey: suspend () -> Boolean, kick: () -> Unit) {
    try {
        if (hasKey()) kick()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The next refresh, recording end or app start tries again.
    }
}

/** After a copied setup replaced the playlists: set the booking alarms again, then look the new titles up. */
suspend fun afterSetupReplaced(rearm: suspend () -> Unit, kick: suspend () -> Unit) {
    try {
        rearm()
    } finally {
        kick()
    }
}
