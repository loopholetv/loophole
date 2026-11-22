package io.github.loopholetv.record

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // ScheduledAlarm exposes its fields only as deprecated Java fields
// A plain Application: the app's own start-up would re-arm alarms from the real database in the background.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RecordAlarmsTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var alarms: RecordAlarms
    private lateinit var manager: AlarmManager
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private var lead = 1

    @Before fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDb()
        manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms = newAlarms()
    }

    @After fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        db.close()
    }

    private fun newAlarms() = RecordAlarms(context, db, settings = { AppSettings(reminderLeadMin = lead) }, clock = { now })

    private fun at(time: String) = Instant.parse("2026-11-22T${time}Z")

    private fun scheduled() = shadowOf(manager).scheduledAlarms

    private suspend fun recording(
        start: Instant, stop: Instant, padBefore: Int = 1, padAfter: Int = 5,
        state: RecordingState = RecordingState.SCHEDULED,
    ): Long = db.recordDao().insertRecording(
        RecordingEntity(
            playlistId = 1, channelKey = "c", channelName = "Channel", epgChannelId = "e", title = "Show",
            subTitle = null, episodeKey = null, description = null, start = start, stop = stop,
            padBeforeMin = padBefore, padAfterMin = padAfter, state = state, createdAt = now,
        ),
    )

    private suspend fun reminder(start: Instant, notifiedAt: Instant? = null): Long = db.recordDao().upsertReminder(
        ReminderEntity(
            playlistId = 1, channelKey = "c", channelName = "Channel", epgChannelId = "e", title = "Show",
            subTitle = null, episodeKey = null, start = start, stop = start.plusSeconds(1800), notifiedAt = notifiedAt,
        ),
    )

    /** (request code, trigger time) of every scheduled alarm. */
    private fun armed() = scheduled().map { shadowOf(it.operation).requestCode to it.triggerAtMs }.toSet()

    @Test fun rearmArmsPrepareThirtySecondsBeforeAndStartAtThePaddedStart() = runTest {
        val id = recording(at("21:00:00"), at("22:00:00"), padBefore = 1)
        alarms.rearm()
        assertEquals(
            setOf(
                requestCode(AlarmAction.PREPARE, id) to at("20:58:30").toEpochMilli(),
                requestCode(AlarmAction.START, id) to at("20:59:00").toEpochMilli(),
            ),
            armed(),
        )
    }

    @Test fun aBookingPastItsPaddedStartGetsOnlyAStartAtOnce() = runTest {
        val id = recording(at("11:59:30"), at("13:00:00"), padBefore = 1) // padded start 11:58:30
        alarms.rearm()
        assertEquals(setOf(requestCode(AlarmAction.START, id) to now.plusSeconds(1).toEpochMilli()), armed())
    }

    @Test fun rearmSetsReminderAtLead() = runTest {
        lead = 5
        val id = reminder(at("13:00:00"))
        alarms.rearm()
        val alarm = scheduled().single()
        assertEquals(at("12:55:00").toEpochMilli(), alarm.triggerAtMs)
        assertEquals(requestCode(AlarmAction.REMIND, id), shadowOf(alarm.operation).requestCode)
    }

    @Test fun runningRecordingGetsAStopAlarmAtTheEndOfItsWindow() = runTest {
        val id = recording(at("11:00:00"), at("13:00:00"), padAfter = 5, state = RecordingState.RECORDING)
        alarms.rearm()
        val alarm = scheduled().single()
        assertEquals(at("13:05:00").toEpochMilli(), alarm.triggerAtMs)
        assertEquals(requestCode(AlarmAction.STOP, id), shadowOf(alarm.operation).requestCode)
    }

    @Test fun pastEventsAndSpentBookingsGetNoAlarm() = runTest {
        reminder(at("11:00:00"))
        reminder(at("13:00:00"), notifiedAt = at("11:59:00"))
        recording(at("10:00:00"), at("11:00:00"))
        recording(at("14:00:00"), at("15:00:00"), state = RecordingState.DONE)
        recording(at("14:00:00"), at("15:00:00"), state = RecordingState.SKIPPED)
        alarms.rearm()
        assertTrue(scheduled().isEmpty())
    }

    @Test fun rearmCancelsAlarmsForCancelledBookings() = runTest {
        val kept = recording(at("21:00:00"), at("22:00:00"))
        val cancelled = recording(at("23:00:00"), at("23:30:00"))
        val reminderId = reminder(at("13:00:00"))
        alarms.rearm()
        assertEquals(5, scheduled().size)

        db.recordDao().deleteRecording(cancelled)
        db.recordDao().deleteReminder(reminderId)
        alarms.rearm()

        assertEquals(
            setOf(requestCode(AlarmAction.PREPARE, kept), requestCode(AlarmAction.START, kept)),
            scheduled().map { shadowOf(it.operation).requestCode }.toSet(),
        )
    }

    @Test fun staleAlarmsAreCancelledByANewInstanceAfterARestart() = runTest {
        val gone = recording(at("21:00:00"), at("22:00:00"))
        alarms.rearm()
        db.recordDao().deleteRecording(gone)

        newAlarms().rearm()

        assertTrue(scheduled().isEmpty())
    }

    @Test fun rearmDoesNotDuplicateAlarms() = runTest {
        recording(at("21:00:00"), at("22:00:00"))
        alarms.rearm()
        alarms.rearm()
        assertEquals(2, scheduled().size)
    }

    @Test fun movedBookingIsRescheduledToItsNewTime() = runTest {
        val id = recording(at("21:00:00"), at("22:00:00"))
        alarms.rearm()
        db.recordDao().updateRecording(db.recordDao().recording(id)!!.copy(start = at("21:30:00"), stop = at("22:30:00")))
        alarms.rearm()
        assertEquals(setOf(at("21:28:30").toEpochMilli(), at("21:29:00").toEpochMilli()), armed().map { it.second }.toSet())
    }

    @Test fun fallsBackToInexactWhenExactRefused() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        recording(at("21:00:00"), at("22:00:00"))
        alarms.rearm()
        assertEquals(setOf(at("20:58:30").toEpochMilli(), at("20:59:00").toEpochMilli()), armed().map { it.second }.toSet())
        for (alarm in scheduled()) {
            assertTrue(alarm.isAllowWhileIdle)
            assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
        }
    }

    @Test fun usesExactAlarmsWhenAllowed() = runTest {
        recording(at("21:00:00"), at("22:00:00"))
        alarms.rearm()
        assertEquals(2, scheduled().size)
        assertTrue(scheduled().all { it.isExact() })
    }

    @Test fun dispatchIgnoresAlarmsUntilHandlersAreSet() = runTest {
        val dispatch = AlarmDispatch()
        dispatch.onAlarm(AlarmAction.REMIND, 1)
        dispatch.onAlarm(AlarmAction.PREPARE, 1)
        dispatch.recover()

        val seen = mutableListOf<String>()
        dispatch.reminders = { seen += "remind $it" }
        dispatch.recordings = object : AlarmHandler {
            override suspend fun onAlarm(action: AlarmAction, id: Long) { seen += "$action $id" }
            override suspend fun recover() { seen += "recover" }
        }
        dispatch.onAlarm(AlarmAction.REMIND, 1)
        dispatch.onAlarm(AlarmAction.PREPARE, 2)
        dispatch.onAlarm(AlarmAction.START, 2)
        dispatch.onAlarm(AlarmAction.STOP, 3)
        dispatch.recover()
        assertEquals(listOf("remind 1", "PREPARE 2", "START 2", "STOP 3", "recover"), seen)
    }

    @Test fun reminderBookedAfterItsLeadFiresAtOnce() = runTest {
        lead = 5
        val id = reminder(at("12:03:00")) // its lead moment (11:58) was two minutes ago
        alarms.rearm()
        val alarm = scheduled().single()
        assertEquals(now.plusSeconds(1).toEpochMilli(), alarm.triggerAtMs)
        assertEquals(requestCode(AlarmAction.REMIND, id), shadowOf(alarm.operation).requestCode)
    }

    @Test fun recordingBookedTenSecondsBeforeItsPaddedStartPreparesAtOnce() = runTest {
        val id = recording(at("12:01:10"), at("13:00:00"), padBefore = 1) // padded start 12:00:10
        alarms.rearm()
        assertEquals(
            setOf(
                requestCode(AlarmAction.PREPARE, id) to now.plusSeconds(1).toEpochMilli(),
                requestCode(AlarmAction.START, id) to at("12:00:10").toEpochMilli(),
            ),
            armed(),
        )
    }

    @Test fun overdueStopFiresAtOnce() = runTest {
        val id = recording(at("10:00:00"), at("11:00:00"), padAfter = 5, state = RecordingState.RECORDING)
        alarms.rearm()
        val alarm = scheduled().single()
        assertEquals(now.plusSeconds(1).toEpochMilli(), alarm.triggerAtMs)
        assertEquals(requestCode(AlarmAction.STOP, id), shadowOf(alarm.operation).requestCode)
    }

    @Test fun requestCodeRejectsIdsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { requestCode(AlarmAction.REMIND, 1L shl 28) }
        assertThrows(IllegalArgumentException::class.java) { requestCode(AlarmAction.REMIND, -1) }
    }

    @Test fun twoConcurrentRecoversRunTheRecoveryOnce() = runTest {
        var recovered = 0
        var rearmed = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handler = object : AlarmHandler {
            override suspend fun onAlarm(action: AlarmAction, id: Long) = Unit
            override suspend fun recover() { recovered++; gate.await() }
        }
        val once = RecoverAndRearm(handler, { rearmed++ }, nowMs = { 0L })
        val a = launch { once.run() }
        val b = launch { once.run() }
        testScheduler.runCurrent()
        gate.complete(Unit)
        a.join(); b.join()
        assertEquals(1, recovered)
        assertEquals(2, rearmed)
    }

    @Test fun slowWorkOutlivesTheBroadcastHoldAndFinishIsCalledOnce() = runTest {
        var finished = 0
        var expired = 0
        var done = false
        val job = holdBroadcast(this, 5_000, { finished++ }, { expired++ }) {
            kotlinx.coroutines.delay(20_000)
            done = true
        }
        testScheduler.advanceTimeBy(4_999); testScheduler.runCurrent()
        assertEquals(0, finished)
        testScheduler.advanceTimeBy(2); testScheduler.runCurrent()
        assertEquals(1, finished)
        assertEquals(1, expired)
        assertTrue(!done)
        job.join()
        assertTrue(done)
        assertEquals(1, finished)
    }

    @Test fun quickWorkFinishesOnceWithoutExpiring() = runTest {
        var finished = 0
        var expired = 0
        holdBroadcast(this, 5_000, { finished++ }, { expired++ }) { kotlinx.coroutines.delay(100) }.join()
        testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(1, finished)
        assertEquals(0, expired)
    }

    @Test fun failingWorkStillFinishesTheBroadcast() = runTest {
        var finished = 0
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
        holdBroadcast(CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob() + handler), 5_000, { finished++ }, {}) { error("x") }.join()
        assertEquals(1, finished)
    }

    @Test fun requestCodeCombinesActionAndId() {
        assertEquals((1 shl 28) or 7, requestCode(AlarmAction.PREPARE, 7))
        assertEquals(AlarmAction.entries.size, AlarmAction.entries.map { requestCode(it, 7) }.toSet().size)
    }

    private fun ShadowAlarmManager.ScheduledAlarm.isExact() =
        isAllowWhileIdle && type == AlarmManager.RTC_WAKEUP && windowLengthMs == ShadowAlarmManager.WINDOW_EXACT
}
