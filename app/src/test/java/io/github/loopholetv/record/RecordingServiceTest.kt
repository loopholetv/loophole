package io.github.loopholetv.record

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

// A plain Application: the app's own start-up would open the real database and the casting stack.
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private val holds = ArrayList<Boolean>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(RecordingService::class.java).create()
        service = controller.get()
        service.holdServer = { holds += it }
    }

    private fun start(id: Long, title: String) {
        val intent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_ID, id)
            .putExtra(RecordingService.EXTRA_TITLE, title)
        service.onStartCommand(intent, 0, 1)
    }

    private fun end(id: Long) {
        val intent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
            .putExtra(RecordingService.EXTRA_ID, id)
        service.onStartCommand(intent, 0, 2)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun startsForegroundWithTitle() {
        start(1, "Evening News")

        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Recording Evening News", title(notification))
        assertEquals(Notifications.RECORDINGS, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        start(2, "Film")
        assertEquals("Recording 2 programmes", title(shadow.lastForegroundNotification))

        end(1)
        assertEquals("Recording Film", title(shadow.lastForegroundNotification))
    }

    @Test fun stopsWhenLastRecordingEnds() {
        start(1, "A")
        start(2, "B")

        end(1)
        assertFalse(shadowOf(service).isStoppedBySelf)

        end(2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun holdsAndReleasesLocks() {
        start(1, "A")
        start(2, "B")

        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(service.wifiLock!!.isHeld)
        assertEquals(listOf(true), holds)

        end(1)
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(service.wifiLock!!.isHeld)

        end(2)
        assertFalse(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(service.wifiLock!!.isHeld)
        assertEquals(listOf(true, false), holds)
    }

    @Test fun aStartWithNothingToRecordStillGoesForegroundThenStops() {
        service.onStartCommand(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        assertEquals(1, service.foregroundCalls)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
