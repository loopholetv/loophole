package io.github.loopholetv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.loopholetv.data.repo.RefreshCoordinator
import io.github.loopholetv.data.repo.RefreshKind
import kotlinx.coroutines.CancellationException

/** Implemented by the Application so [RefreshWorker] can reach the process's coordinator. */
interface RefreshRunnerProvider {
    val refreshCoordinator: RefreshCoordinator
}

/**
 * Periodic playlist or EPG update ([KEY_KIND] = a [RefreshKind] name): refreshes whatever is due.
 * Always succeeds: failures are already stored as playlist/EPG statuses, and retrying would only
 * hammer the provider.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = RefreshKind.entries.firstOrNull { it.name == inputData.getString(KEY_KIND) }
            ?: return Result.success()
        try {
            (applicationContext as RefreshRunnerProvider).refreshCoordinator.runDue(kind)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not logged: messages may embed source URLs with credentials.
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
    }
}
