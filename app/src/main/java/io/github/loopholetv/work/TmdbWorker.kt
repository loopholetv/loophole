package io.github.loopholetv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.github.loopholetv.data.tmdb.SyncOutcome
import kotlinx.coroutines.CancellationException

/** Implemented by the Application so [TmdbWorker] can reach the process's sync. */
interface TmdbSyncProvider {
    /** One lookup run (see [TmdbScheduler.runTracked]). */
    suspend fun runTmdbSync(): SyncOutcome
}

/**
 * A finished run, a refused key or a pause for a recording ends the job (the recording's end starts a new
 * one, see [kickWhenRecordingsEnd]); network trouble retries it with backoff.
 */
internal fun tmdbWorkResult(outcome: SyncOutcome): ListenableWorker.Result = when (outcome) {
    SyncOutcome.Done, SyncOutcome.BadKey, SyncOutcome.Paused -> ListenableWorker.Result.success()
    is SyncOutcome.RetryLater -> ListenableWorker.Result.retry()
}

/** Looks up film and show details from TMDB (see [io.github.loopholetv.data.tmdb.TmdbSync]). */
class TmdbWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        tmdbWorkResult((applicationContext as TmdbSyncProvider).runTmdbSync())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not logged: messages may carry request details.
        Result.retry()
    }
}
