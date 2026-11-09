package io.github.loopholetv.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeRunnerApp::class)
class RefreshSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: RefreshScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = RefreshScheduler(workManager)
    }

    private fun info(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun enqueued(name: String): WorkInfo {
        val active = info(name).filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("one enqueued $name", 1, active.size)
        return active.single()
    }

    @Test
    fun enqueuesBothWithIntervals() {
        scheduler.apply(AppSettings(playlistUpdateHours = 24, epgUpdateHours = 12))

        val playlist = enqueued(RefreshScheduler.PLAYLIST_WORK)
        assertEquals(TimeUnit.HOURS.toMillis(24), playlist.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.HOURS.toMillis(24), playlist.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, playlist.constraints.requiredNetworkType)

        val epg = enqueued(RefreshScheduler.EPG_WORK)
        assertEquals(TimeUnit.HOURS.toMillis(12), epg.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.HOURS.toMillis(12), epg.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, epg.constraints.requiredNetworkType)
    }

    @Test
    fun zeroCancels() {
        scheduler.apply(AppSettings(playlistUpdateHours = 24, epgUpdateHours = 12))
        scheduler.apply(AppSettings(playlistUpdateHours = 0, epgUpdateHours = 12))

        assertTrue(info(RefreshScheduler.PLAYLIST_WORK).none { it.state == WorkInfo.State.ENQUEUED })
        enqueued(RefreshScheduler.EPG_WORK)

        scheduler.apply(AppSettings(playlistUpdateHours = 0, epgUpdateHours = 0))
        assertTrue(info(RefreshScheduler.EPG_WORK).none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun changingIntervalUpdatesWork() {
        scheduler.apply(AppSettings(playlistUpdateHours = 24, epgUpdateHours = 12))
        val before = enqueued(RefreshScheduler.PLAYLIST_WORK).id

        scheduler.apply(AppSettings(playlistUpdateHours = 6, epgUpdateHours = 12))

        val after = enqueued(RefreshScheduler.PLAYLIST_WORK)
        assertEquals("updated in place", before, after.id)
        assertEquals(TimeUnit.HOURS.toMillis(6), after.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun inputDataNamesTheKind() {
        scheduler.apply(AppSettings())
        // WorkInfo does not expose input data; the tag carries the kind for inspection.
        assertTrue(RefreshScheduler.PLAYLIST_WORK in enqueued(RefreshScheduler.PLAYLIST_WORK).tags)
        assertTrue(RefreshScheduler.EPG_WORK in enqueued(RefreshScheduler.EPG_WORK).tags)
    }

    @Test
    fun stoppedJobsBackOffLinearlyByAnHour() {
        scheduler.apply(AppSettings())
        val specs = WorkManagerImpl.getInstance(ApplicationProvider.getApplicationContext()).workDatabase.workSpecDao()
        for (name in listOf(RefreshScheduler.PLAYLIST_WORK, RefreshScheduler.EPG_WORK)) {
            val spec = specs.getWorkSpec(enqueued(name).id.toString())!!
            assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
            assertEquals(TimeUnit.HOURS.toMillis(1), spec.backoffDelayDuration)
        }
    }

    private fun playlist(id: Long, hours: Int? = null, enabled: Boolean = true) = PlaylistEntity(
        id = id, name = "P$id", type = PlaylistType.M3U, url = "http://example.test/$id", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
        lastRefreshedAt = null, lastError = null, enabled = enabled, updateHours = hours,
    )

    private fun playlistInterval(): Long? =
        info(RefreshScheduler.PLAYLIST_WORK).singleOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?.periodicityInfo?.repeatIntervalMillis

    @Test
    fun playlistCadenceIsShortestEnabledInterval() {
        scheduler.apply(AppSettings(playlistUpdateHours = 24), playlistHours = listOf(24, 6, 0))
        assertEquals(TimeUnit.HOURS.toMillis(6), playlistInterval())

        scheduler.apply(AppSettings(playlistUpdateHours = 24), playlistHours = listOf(0, 0))
        assertEquals(null, playlistInterval())
        enqueued(RefreshScheduler.EPG_WORK) // the EPG job follows its own setting

        scheduler.apply(AppSettings(playlistUpdateHours = 24), playlistHours = emptyList())
        assertEquals(null, playlistInterval())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun observeUsesEnabledPlaylistsEffectiveIntervals() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(AppSettings(playlistUpdateHours = 24, epgUpdateHours = 12))
        val playlists = MutableStateFlow(listOf(playlist(1), playlist(2, hours = 0)))
        scheduler.observe(settings, playlists, backgroundScope)
        assertEquals(TimeUnit.HOURS.toMillis(24), playlistInterval()) // 1 is "Same as default"

        playlists.value = listOf(playlist(1), playlist(2, hours = 6))
        assertEquals(TimeUnit.HOURS.toMillis(6), playlistInterval())

        // A turned-off playlist doesn't count.
        playlists.value = listOf(playlist(1, enabled = false), playlist(2, hours = 6, enabled = false))
        assertEquals(null, playlistInterval())

        playlists.value = listOf(playlist(1))
        settings.value = settings.value.copy(playlistUpdateHours = 3)
        assertEquals(TimeUnit.HOURS.toMillis(3), playlistInterval())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun settingsErrorFallsBackToDefaultIntervals() = runTest(UnconfinedTestDispatcher()) {
        scheduler.observe(flow { throw IOException("datastore") }, flowOf(listOf(playlist(1))), backgroundScope)
        assertEquals(
            TimeUnit.HOURS.toMillis(24),
            enqueued(RefreshScheduler.PLAYLIST_WORK).periodicityInfo!!.repeatIntervalMillis,
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(12),
            enqueued(RefreshScheduler.EPG_WORK).periodicityInfo!!.repeatIntervalMillis,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun observeAppliesOnIntervalChangesOnly() = runTest(UnconfinedTestDispatcher()) {
        val settings = MutableStateFlow(AppSettings(playlistUpdateHours = 24, epgUpdateHours = 12))
        val job = scheduler.observe(settings, flowOf(listOf(playlist(1))), backgroundScope)

        enqueued(RefreshScheduler.PLAYLIST_WORK)
        settings.value = settings.value.copy(confirmExit = false) // not an interval: no change
        settings.value = settings.value.copy(epgUpdateHours = 0)
        assertTrue(info(RefreshScheduler.EPG_WORK).none { it.state == WorkInfo.State.ENQUEUED })
        settings.value = settings.value.copy(epgUpdateHours = 3)
        assertEquals(
            TimeUnit.HOURS.toMillis(3),
            enqueued(RefreshScheduler.EPG_WORK).periodicityInfo!!.repeatIntervalMillis,
        )
        job.cancel()
    }
}
