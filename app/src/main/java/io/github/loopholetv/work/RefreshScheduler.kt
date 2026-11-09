package io.github.loopholetv.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.repo.RefreshKind
import io.github.loopholetv.data.repo.effectiveHours
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit

/**
 * Keeps one unique periodic job per [RefreshKind] in step with the interval settings. The
 * worker only refreshes what [isDue][io.github.loopholetv.data.repo.isDue], so a job firing right
 * after a startup or manual update does nothing.
 */
class RefreshScheduler(private val workManager: WorkManager) {

    /**
     * [playlistHours]: the effective update interval of each turned-on playlist. The playlist
     * job runs at the shortest non-zero one, which the worker's per-playlist due check then
     * filters; with none (all Off, all turned off, no playlists) the job is cancelled.
     */
    fun apply(settings: AppSettings, playlistHours: List<Int> = listOf(settings.playlistUpdateHours)) =
        applyIntervals(playlistCadence(playlistHours), settings.epgUpdateHours)

    private fun applyIntervals(playlistHours: Int, epgHours: Int) {
        schedule(RefreshKind.PLAYLIST, PLAYLIST_WORK, playlistHours)
        schedule(RefreshKind.EPG, EPG_WORK, epgHours)
    }

    /**
     * Applies the intervals now and whenever the settings or the playlists' own intervals change.
     * If the settings can't be read (DataStore error), the default intervals are applied so updates
     * keep running for this process; if the playlists can't be read, the general interval is used.
     */
    fun observe(settings: Flow<AppSettings>, playlists: Flow<List<PlaylistEntity>>, scope: CoroutineScope): Job =
        combine(
            settings.catch { e -> if (e is CancellationException) throw e else emit(AppSettings()) },
            playlists.catch { e -> if (e is CancellationException) throw e else emit(UNKNOWN) },
        ) { s, list ->
            val hours = if (list === UNKNOWN) listOf(s.playlistUpdateHours) else {
                list.filter { it.enabled }.map { effectiveHours(it, s) }
            }
            playlistCadence(hours) to s.epgUpdateHours
        }
            .distinctUntilChanged()
            .onEach { (playlistHours, epgHours) ->
                try {
                    applyIntervals(playlistHours, epgHours)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep observing; the next change (or start) tries again.
                }
            }
            .launchIn(scope)

    private fun schedule(kind: RefreshKind, name: String, hours: Int) {
        if (hours <= 0) {
            workManager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(RefreshWorker.KEY_KIND to kind.name))
            // JobScheduler stops a job after ~10 min; a stopped (e.g. huge EPG) run then retries an
            // hour later instead of the default 30 s exponential backoff.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .addTag(name)
            .build()
        // UPDATE keeps the existing schedule (enqueue time) when nothing changed, so applying on every
        // app start does not push the next run back.
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val PLAYLIST_WORK = "loophole-refresh-playlist"
        const val EPG_WORK = "loophole-refresh-epg"

        /** Marks "the playlists couldn't be read" (compared by identity). */
        private val UNKNOWN: List<PlaylistEntity> = ArrayList()

        /** The shortest non-zero interval, or 0 (Off) when there is none. */
        internal fun playlistCadence(hours: List<Int>): Int = hours.filter { it > 0 }.minOrNull() ?: 0
    }
}
