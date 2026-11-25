package io.github.loopholetv.record

import android.app.Application
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.isChannelLockedNow
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.ui.vod.MY_LIST_ROW_TITLE
import io.github.loopholetv.ui.vod.VodBrowseViewModel
import io.github.loopholetv.ui.vod.VodRowModel
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.db.isChannelLocked
import io.github.loopholetv.ui.MainDispatcherRule
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private class Surface : ReminderSurface {
    val shown = mutableListOf<ReminderPrompt>()

    override fun show(p: ReminderPrompt) {
        shown += p
    }

    override fun dismiss(id: Long) = Unit
}

/** Reminders of locked channels show nothing and stay out of My list while the PIN is not entered. */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ParentalRemindersTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private var playlist = 0L
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val overlay = Surface()
    private val notify = Surface()
    private var unlocked = false
    private val repo by lazy { VodRepository(db) { now } }

    @Before fun setUp() = kotlinx.coroutines.runBlocking {
        db = inMemoryDb()
        playlist = db.addPlaylist()
        Unit
    }

    @After fun tearDown() {
        main.clear()
        db.close()
    }

    private suspend fun booked(key: String = "c1"): Long = db.recordDao().upsertReminder(
        ReminderEntity(
            playlistId = playlist, channelKey = key, channelName = "Channel $key", epgChannelId = "e", title = "News",
            subTitle = null, episodeKey = null, start = now.plusSeconds(60), stop = now.plusSeconds(1860),
        ),
    )

    private suspend fun lock(key: String) = db.channelPrefsDao().upsert(ChannelPrefsEntity(playlist, key, locked = true))

    private fun TestScope.reminders() = Reminders(
        db, settings = { AppSettings() }, foreground = { true }, canOverlay = { true }, overlay = overlay, notify = notify,
        clock = { now }, scope = backgroundScope, delayMs = { awaitCancellation() },
        lockedNow = { ref -> with(db.lockDao()) { isChannelLockedNow(unlocked, ref.playlistId, ref.key) } },
    )

    @Test fun lockedReminderShowsNothingButStays() = runTest {
        lock("c1")
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        assertNull(r.inApp.value)
        assertTrue(overlay.shown.isEmpty() && notify.shown.isEmpty())
        assertNotNull(db.recordDao().reminder(id))
    }

    @Test fun unlockedReminderShows() = runTest {
        lock("c1")
        unlocked = true
        val id = booked()
        val r = reminders()
        r.alarm(id).join()
        assertEquals("Channel c1", r.inApp.value?.channel)
    }

    @Test fun reminderOfUnlockedChannelShowsWhileLocked() = runTest {
        lock("c1")
        val id = booked("c2")
        val r = reminders()
        r.alarm(id).join()
        assertEquals("Channel c2", r.inApp.value?.channel)
    }

    @Test fun myListReminderCardHiddenWhileLocked() = runTest {
        lock("c1")
        val locked = MutableStateFlow(true)
        fun reminder(id: Long, key: String) = ReminderEntity(
            id = id, playlistId = playlist, channelKey = key, channelName = key, epgChannelId = null, title = "Show $key",
            subTitle = null, episodeKey = null, start = now.plusSeconds(1800), stop = now.plusSeconds(5400),
        )
        val vm = main.keep(
            VodBrowseViewModel(
                VodKind.EPISODE, repo, VodLibraryRepository(db, repo) { now }, ChannelEdits(db, ChannelOverlay(db)),
                reminders = kotlinx.coroutines.flow.flowOf(listOf(reminder(1, "c1"), reminder(2, "c2"))),
                clock = { now }, lockedNow = locked,
                channelLocked = { p, k -> db.lockDao().isChannelLocked(p, k) },
            ),
        )
        suspend fun titles(count: Int) = (vm.rows.first { r -> (r.firstOrNull() as? VodRowModel.MyList)?.cards?.size == count }
            .first() as VodRowModel.MyList).cards.map { it.title }

        assertEquals(listOf("Show c2"), titles(1))
        locked.value = false
        assertEquals(listOf("Show c1", "Show c2"), titles(2))
        assertEquals(MY_LIST_ROW_TITLE, (vm.rows.first().first() as VodRowModel.MyList).title)
    }
}
