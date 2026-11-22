package io.github.loopholetv.record

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.ZoneOffset

private class FakeSurface : ReminderSurface {
    val shown = mutableListOf<ReminderPrompt>()
    val dismissed = mutableListOf<Long>()
    var failOnShow = false

    override fun show(p: ReminderPrompt) {
        if (failOnShow) throw IllegalStateException("no window")
        shown += p
    }

    override fun dismiss(id: Long) {
        dismissed += id
    }
}

// A plain Application: the app's own start-up would re-arm alarms from the real database in the background.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RemindersTest {
    private lateinit var db: AppDatabase
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val overlay = FakeSurface()
    private val notify = FakeSurface()
    private val launched = mutableListOf<ChannelRef>()
    private var foreground = false
    private var canOverlay = false
    private var autoSwitch = false
    private var clockNow = now

    @Before fun setUp() {
        db = inMemoryDb()
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun booked(start: Instant = now.plusSeconds(60), notifiedAt: Instant? = null): Long =
        db.recordDao().upsertReminder(
            ReminderEntity(
                playlistId = 3, channelKey = "c1", channelName = "Channel One", epgChannelId = "e", title = "News",
                subTitle = null, episodeKey = null, start = start, stop = start.plusSeconds(1800), notifiedAt = notifiedAt,
            ),
        )

    private fun kotlinx.coroutines.test.TestScope.reminders(delayMs: suspend (Long) -> Unit = { awaitCancellation() }) = Reminders(
        db, settings = { AppSettings(reminderAutoSwitch = autoSwitch) },
        foreground = { foreground }, canOverlay = { canOverlay }, overlay = overlay, notify = notify,
        clock = { clockNow }, scope = backgroundScope, delayMs = delayMs, launch = { launched += it },
    )

    @Test fun foregroundShowsInApp() = runTest {
        foreground = true
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        val p = r.inApp.value
        assertNotNull(p)
        assertEquals("Channel One", p!!.channel)
        assertEquals("News", p.title)
        assertTrue(overlay.shown.isEmpty() && notify.shown.isEmpty())
    }

    @Test fun backgroundWithOverlayUsesOverlay() = runTest {
        canOverlay = true
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        assertEquals(1, overlay.shown.size)
        assertTrue(notify.shown.isEmpty())
        assertNull(r.inApp.value)
    }

    @Test fun noPermissionUsesNotification() = runTest {
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        assertEquals(1, notify.shown.size)
        assertTrue(overlay.shown.isEmpty())
    }

    @Test fun overlayThatCannotOpenFallsBackToTheNotification() = runTest {
        canOverlay = true
        overlay.failOnShow = true
        val id = booked()
        reminders().alarm(id).join()
        assertEquals(1, notify.shown.size)
    }

    @Test fun timeoutDismissesWithoutSwitch() = runTest {
        canOverlay = true
        val gate = CompletableDeferred<Unit>()
        val id = booked()
        val r = reminders { assertEquals(POPUP_TIMEOUT_MS, it); gate.await() }
        val emitted = mutableListOf<ChannelRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.watch.toList(emitted) }
        r.alarm(id).join()
        val timer = r.timerJob(id)!!
        gate.complete(Unit)
        timer.join()
        assertEquals(listOf(id), overlay.dismissed)
        assertTrue(launched.isEmpty() && emitted.isEmpty())
    }

    @Test fun autoSwitchEmitsWatchOnTimeout() = runTest {
        foreground = true
        autoSwitch = true
        val gate = CompletableDeferred<Unit>()
        val id = booked()
        val r = reminders { gate.await() }
        val emitted = mutableListOf<ChannelRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.watch.toList(emitted) }
        r.alarm(id).join()
        r.shown(id)
        val timer = r.timerJob(id)!!
        gate.complete(Unit)
        timer.join()
        assertEquals(listOf(ChannelRef(3, "c1")), emitted)
        assertNull(r.inApp.value)
    }

    @Test fun autoSwitchFromTheOverlayStartsTheActivity() = runTest {
        canOverlay = true
        autoSwitch = true
        val gate = CompletableDeferred<Unit>()
        val id = booked()
        val r = reminders { gate.await() }
        r.alarm(id).join()
        val timer = r.timerJob(id)!!
        gate.complete(Unit)
        timer.join()
        assertEquals(listOf(ChannelRef(3, "c1")), launched)
        assertEquals(listOf(id), overlay.dismissed)
    }

    @Test fun answerTwiceOneOutcome() = runTest {
        foreground = true
        val id = booked()
        val r = reminders()
        val emitted = mutableListOf<ChannelRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.watch.toList(emitted) }
        r.alarm(id).join()
        r.answer(id, watch = true)
        r.answer(id, watch = false)
        r.answer(id, watch = true)
        assertEquals(listOf(ChannelRef(3, "c1")), emitted)
        assertNull(r.inApp.value)
    }

    @Test fun anAnswerFromTheOverlayLaunchesOnceAndDismisses() = runTest {
        canOverlay = true
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        r.answer(id, watch = true)
        r.answer(id, watch = true)
        assertEquals(listOf(ChannelRef(3, "c1")), launched)
        assertEquals(listOf(id), overlay.dismissed)
    }

    @Test fun aHandledReminderIsNotArmedAgainEvenWhenShowingFails() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        canOverlay = true
        overlay.failOnShow = true
        notify.failOnShow = true
        val context: Context = ApplicationProvider.getApplicationContext()
        val alarms = RecordAlarms(context, db, settings = { AppSettings() }, clock = { now })
        val id = booked()
        alarms.rearm()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)

        reminders().alarm(id).join()
        alarms.rearm()

        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertNotNull(db.recordDao().reminder(id)!!.notifiedAt)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    @Test fun anAlarmForAReminderThatIsGoneDoesNothing() = runTest {
        val r = reminders()
        r.alarm(999).join()
        assertTrue(overlay.shown.isEmpty() && notify.shown.isEmpty() && r.inApp.value == null)
    }

    @Test fun anAlarmForAProgrammeThatEndedMarksItWithoutShowing() = runTest {
        foreground = true
        val id = booked(start = now.minusSeconds(3600))
        val r = reminders()
        r.alarm(id).join()
        assertNull(r.inApp.value)
        assertNotNull(db.recordDao().reminder(id)!!.notifiedAt)
    }

    private fun prompt(leadMin: Int) = ReminderPrompt(1, "Channel One", "News", Instant.parse("2026-11-22T20:05:00Z"), leadMin)

    @Test fun leadZeroSaysStartingNow() {
        assertEquals("Starting now on Channel One" to "News · 20:05", reminderText(prompt(0), ZoneOffset.UTC))
    }

    @Test fun leadFiveSaysStartsInFiveMin() {
        assertEquals("Starts in 5 min on Channel One" to "News · 20:05", reminderText(prompt(5), ZoneOffset.UTC))
    }

    @Test fun theChannelTravelsThroughTheLaunchExtra() {
        val ref = ChannelRef(7, "http://x.example.test/a/b?c=1")
        assertEquals(ref, parsePlayChannel(playChannelExtra(ref)))
        assertNull(parsePlayChannel("nonsense"))
        assertNull(parsePlayChannel(null))
    }

    @Test fun aReminderQueuedBehindAQuestionExpiresOnlyAfterItIsShown() = runTest {
        foreground = true
        autoSwitch = true
        val gate = CompletableDeferred<Unit>()
        var delays = 0
        val id = booked()
        val r = reminders { delays++; gate.await() }
        val emitted = mutableListOf<ChannelRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.watch.toList(emitted) }
        r.alarm(id).join()
        assertNull(r.timerJob(id))
        assertEquals(0, delays)
        r.shown(id)
        r.shown(id)
        val timer = r.timerJob(id)!!
        runCurrent()
        assertEquals(1, delays)
        assertTrue(emitted.isEmpty())
        gate.complete(Unit)
        timer.join()
        assertEquals(listOf(ChannelRef(3, "c1")), emitted)
    }

    @Test fun theAlarmReturnsOnceTheReminderIsShown() = runTest {
        val id = booked()
        val r = reminders()
        // Real time for the alarm's wait: the database answers on its own threads.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { r.onAlarm(id) }
        assertEquals(1, notify.shown.size)
    }

    @Test fun anInAppCardLeftWaitingPastItsLeadAndFiveMinutesIsDropped() = runTest {
        foreground = true
        autoSwitch = true
        val start = now.plusSeconds(60)
        val id = booked(start)
        val r = reminders()
        val emitted = mutableListOf<ChannelRef>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.watch.toList(emitted) }
        r.alarm(id).join()
        assertNotNull(r.inApp.value)
        // Lead 1 min: still shown at 6 min past the start, dropped just after.
        clockNow = start.plusSeconds(6 * 60 + 1)
        r.shown(id)
        assertNull(r.inApp.value)
        assertNull(r.timerJob(id))
        assertTrue(emitted.isEmpty())
    }

    @Test fun anInAppCardWhoseProgrammeEndedIsDropped() = runTest {
        foreground = true
        val start = now.plusSeconds(60)
        val id = booked(start)
        val r = reminders()
        r.alarm(id).join()
        clockNow = start.plusSeconds(1800)
        r.shown(id)
        assertNull(r.inApp.value)
    }

    @Test fun anInAppCardShownSoonAfterItsStartStillTimesOut() = runTest {
        foreground = true
        val start = now.plusSeconds(60)
        val id = booked(start)
        val r = reminders()
        r.alarm(id).join()
        clockNow = start.plusSeconds(5 * 60)
        r.shown(id)
        assertNotNull(r.inApp.value)
        assertNotNull(r.timerJob(id))
    }

    @Test fun aReplacedOverlayReminderNeverAutoSwitches() = runTest {
        canOverlay = true
        autoSwitch = true
        val gate = CompletableDeferred<Unit>()
        val first = booked()
        val second = db.recordDao().upsertReminder(
            ReminderEntity(
                playlistId = 4, channelKey = "c2", channelName = "Two", epgChannelId = "e", title = "Film",
                subTitle = null, episodeKey = null, start = now.plusSeconds(120), stop = now.plusSeconds(3000),
            ),
        )
        val r = reminders { gate.await() }
        r.alarm(first).join()
        val oldTimer = r.timerJob(first)!!
        r.alarm(second).join()
        assertTrue(oldTimer.isCancelled)
        gate.complete(Unit)
        r.timerJob(second)!!.join()
        assertEquals(listOf(ChannelRef(4, "c2")), launched)
    }

    @Test fun badChannelExtrasAreRefused() {
        assertNull(parsePlayChannel("abc/key"))
        assertNull(parsePlayChannel("7/"))
        assertNull(parsePlayChannel("/key"))
    }
}
