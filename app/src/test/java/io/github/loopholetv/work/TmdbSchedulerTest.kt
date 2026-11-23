package io.github.loopholetv.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.loopholetv.data.tmdb.SyncOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeRunnerApp::class)
class TmdbSchedulerTest {
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun infos() = workManager.getWorkInfosForUniqueWork(TmdbScheduler.WORK_NAME).get()

    @Test
    fun kickEnqueuesOneNetworkJob() {
        val scheduler = TmdbScheduler(workManager)
        scheduler.kick()
        scheduler.kick()

        val work = infos().single()
        assertEquals("tmdb-sync", TmdbScheduler.WORK_NAME)
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun outcomesMapToWorkResults() {
        assertEquals(ListenableWorker.Result.success(), tmdbWorkResult(SyncOutcome.Done))
        assertEquals(ListenableWorker.Result.success(), tmdbWorkResult(SyncOutcome.BadKey))
        assertEquals(ListenableWorker.Result.retry(), tmdbWorkResult(SyncOutcome.RetryLater(10)))
        // Paused ends the job: the end of the recording kicks a fresh one.
        assertEquals(ListenableWorker.Result.success(), tmdbWorkResult(SyncOutcome.Paused))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun kicksOnlyWhenTheLastRecordingEnds() = runTest(UnconfinedTestDispatcher()) {
        val active = MutableStateFlow<Map<Long, String>>(emptyMap())
        var kicks = 0
        kickWhenRecordingsEnd(active, { kicks++ }, backgroundScope)
        assertEquals("nothing ended at start", 0, kicks)

        active.value = mapOf(1L to "a")
        active.value = mapOf(1L to "a", 2L to "b")
        active.value = mapOf(2L to "b")
        assertEquals("still recording", 0, kicks)

        active.value = emptyMap()
        assertEquals(1, kicks)
        active.value = emptyMap()
        assertEquals(1, kicks)

        active.value = mapOf(3L to "c")
        active.value = emptyMap()
        assertEquals(2, kicks)
    }

    @Test
    fun backoffIsExponentialFromOneMinute() {
        val request = TmdbScheduler.request()
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(1), request.workSpec.backoffDelayDuration)
    }

    // --- kicks that arrive while a run is in progress ---

    private val enqueued = mutableListOf<ExistingWorkPolicy>()
    private val tracked = TmdbScheduler { enqueued += it }

    @Test
    fun kickDuringRunCausesOneMoreRun() = runTest {
        val outcome = tracked.runTracked(sync = { tracked.kick(); SyncOutcome.Done }, recordingActive = { false })
        assertEquals(SyncOutcome.Done, outcome)
        // The in-run kick is dropped by KEEP while this job runs; the follow-up is appended after it.
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.APPEND_OR_REPLACE), enqueued)
    }

    @Test
    fun noKickDuringRunNoExtraRun() = runTest {
        tracked.kick() // the kick that started this run
        tracked.runTracked(sync = { SyncOutcome.Done }, recordingActive = { false })
        assertEquals(listOf(ExistingWorkPolicy.KEEP), enqueued)
    }

    @Test
    fun pausedRunRekicksOnlyWhenTheRecordingHasEnded() = runTest {
        tracked.runTracked(sync = { tracked.kick(); SyncOutcome.Paused }, recordingActive = { true })
        assertEquals(listOf(ExistingWorkPolicy.KEEP), enqueued)

        enqueued.clear()
        tracked.runTracked(sync = { tracked.kick(); SyncOutcome.Paused }, recordingActive = { false })
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.APPEND_OR_REPLACE), enqueued)
    }

    @Test
    fun retryAndBadKeyDoNotRekick() = runTest {
        tracked.runTracked(sync = { tracked.kick(); SyncOutcome.RetryLater(null) }, recordingActive = { false })
        tracked.runTracked(sync = { tracked.kick(); SyncOutcome.BadKey }, recordingActive = { false })
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), enqueued)
    }

    @Test
    fun copiedSetupKicksEvenWhenRearmingFails() = runTest {
        var kicks = 0
        afterSetupReplaced(rearm = {}, kick = { kicks++ })
        try {
            afterSetupReplaced(rearm = { error("alarms down") }, kick = { kicks++ })
        } catch (_: IllegalStateException) {
        }
        assertEquals(2, kicks)
    }

    @Test
    fun aFailingKickIsSwallowed() = runTest {
        var asked = 0
        kickIfKey(hasKey = { asked++; true }, kick = { error("work manager down") })
        kickIfKey(hasKey = { error("settings down") }, kick = {})
        assertEquals(1, asked)
    }
}
