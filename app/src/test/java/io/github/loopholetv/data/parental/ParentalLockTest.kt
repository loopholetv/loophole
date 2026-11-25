package io.github.loopholetv.data.parental

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ParentalLockTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var overlay: ChannelOverlay
    private lateinit var edits: ChannelEdits

    @Before fun setUp() {
        db = inMemoryDb()
        overlay = ChannelOverlay(db)
        edits = ChannelEdits(db, overlay)
    }

    @After fun tearDown() = db.close()

    private fun TestScope.settings() = SettingsStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = backgroundScope,
        ) { File(tmp.root, "settings.preferences_pb") },
    )

    /**
     * The lock's own virtual time, apart from runTest's: that one jumps forward whenever the test body waits
     * on the database, which would fire the unlock timer early.
     */
    private val time = TestCoroutineScheduler()

    private fun TestScope.lock(store: SettingsStore = settings()) =
        ParentalLock(store, edits, overlay, CoroutineScope(StandardTestDispatcher(time))) { time.currentTime + BASE }

    /** Runs what is due on [time], waiting on real time for the database in between, until [done]. */
    private suspend fun pumpUntil(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10.seconds) {
            while (!done()) {
                time.runCurrent()
                delay(5)
            }
        }
    }

    private suspend fun lockState() = db.lockDao().unlocked().first()

        private suspend fun ParentalLock.awaitLocked() = pumpUntil { state.value == LockState.Locked }

    @Test fun noPinStateIsNoPin() = runTest {
        val l = lock()
        l.startLocked()
        assertEquals(LockState.NoPin, l.state.value)
        assertFalse(l.hasPin())
        assertFalse(l.isUnlocked)
    }

    @Test fun setPinThenLockedAfterRelock() = runTest {
        val l = lock()
        l.setPin("1234")
        assertTrue(l.hasPin())
        assertTrue(l.state.value is LockState.Unlocked)
        assertTrue(l.isUnlocked)
        assertTrue(lockState())
        l.relock()
        assertEquals(LockState.Locked, l.state.value)
        assertFalse(lockState())
        assertTrue(l.matches("1234"))
        assertFalse(l.matches("4321"))
    }

    @Test fun checkOkUnlocksForChosenMinutes() = runTest {
        val store = settings()
        val l = lock(store)
        l.setPin("1234")
        l.relock()
        val now = time.currentTime + BASE
        assertEquals(PinCheck.Ok, l.check("1234"))
        assertEquals(LockState.Unlocked(now + 15.minutes.inWholeMilliseconds), l.state.value)
        assertTrue(lockState())

        time.advanceTimeBy(14.minutes.inWholeMilliseconds)
        time.runCurrent()
        assertTrue(l.state.value is LockState.Unlocked)
        time.advanceTimeBy(1.minutes.inWholeMilliseconds)
        time.runCurrent()
        l.awaitLocked()
        assertFalse(lockState())
    }

    @Test fun untilLeaveHasNoTimer() = runTest {
        val store = settings()
        store.update { it.copy(parental = it.parental.copy(unlockMinutes = 0)) }
        val l = lock(store)
        l.setPin("1234")
        assertEquals(LockState.Unlocked(null), l.state.value)
        time.advanceTimeBy((24 * 60).minutes.inWholeMilliseconds)
        time.runCurrent()
        assertEquals(LockState.Unlocked(null), l.state.value)
        assertTrue(lockState())
    }

    @Test fun onLeftAppRelocks() = runTest {
        val l = lock()
        l.setPin("1234")
        val job = l.onLeftApp()
        pumpUntil { job.isCompleted }
        assertEquals(LockState.Locked, l.state.value)
        assertFalse(lockState())
    }

    @Test fun fiveWrongThenWait30() = runTest {
        val l = lock()
        l.setPin("1234")
        l.relock()
        repeat(4) { assertEquals(PinCheck.Wrong, l.check("0000")) }
        assertEquals(PinCheck.Wait(30), l.check("0000"))
        // Even the right PIN is refused during the wait.
        time.advanceTimeBy(10.seconds.inWholeMilliseconds)
        assertEquals(PinCheck.Wait(20), l.check("1234"))
        assertEquals(LockState.Locked, l.state.value)
        time.advanceTimeBy(20.seconds.inWholeMilliseconds)
        assertEquals(PinCheck.Ok, l.check("1234"))
    }

    @Test fun aCorrectPinResetsTheCounter() = runTest {
        val l = lock()
        l.setPin("1234")
        l.relock()
        repeat(4) { l.check("0000") }
        assertEquals(PinCheck.Ok, l.check("1234"))
        l.relock()
        repeat(4) { assertEquals(PinCheck.Wrong, l.check("0000")) }
    }

    @Test fun removeAllClearsPinLocksAndSettingsLock() = runTest {
        val store = settings()
        val l = lock(store)
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "a1", 1, groupTitle = "A"), channel(p, "b1", 2, groupTitle = "B"))
        overlay.refreshAll()
        l.setPin("1234")
        store.update { it.copy(parental = it.parental.copy(settingsLock = SettingsLock.ALL)) }
        edits.setGroupLocked(p, "A", true)
        l.relock()
        assertEquals(listOf("b1"), db.channelDao().channelsOf(p).filter { it.visible }.map { it.key })

        l.removeAll()
        val parental = store.current().parental
        assertEquals(null, parental.pinHash)
        assertEquals(null, parental.pinSalt)
        assertEquals(SettingsLock.OFF, parental.settingsLock)
        assertEquals(LockState.NoPin, l.state.value)
        assertFalse(l.hasPin())
        assertEquals(listOf("a1", "b1"), db.channelDao().channelsOf(p).filter { it.visible }.map { it.key }.sorted())
    }

    @Test fun startupRelocksBeforeReads() = runTest {
        val store = settings()
        val l = lock(store)
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "a1", 1, groupTitle = "A"), channel(p, "b1", 2, groupTitle = "B"))
        overlay.refreshAll()
        l.setPin("1234")
        edits.setGroupLocked(p, "A", true)
        // The previous run ended while unlocked.
        assertTrue(lockState())
        assertEquals(listOf("a1", "b1"), db.channelDao().channelsOf(p).filter { it.visible }.map { it.key }.sorted())

        val fresh = lock(store)
        fresh.startLocked()
        assertEquals(LockState.Locked, fresh.state.value)
        assertFalse(lockState())
        assertEquals(listOf("b1"), db.channelDao().channelsOf(p).filter { it.visible }.map { it.key })
    }

    @Test fun startLockedRetriesAFailingWrite() = runTest {
        val store = settings()
        var failures = 2
        val flaky = object : ChannelOverlay(db) {
            override suspend fun setLockState(unlocked: Boolean) {
                if (failures-- > 0) throw java.io.IOException("disk")
                super.setLockState(unlocked)
            }
        }
        ChannelOverlay(db).setLockState(true)
        store.update { it.copy(parental = it.parental.copy(pinHash = "h", pinSalt = "s")) }
        val l = ParentalLock(store, edits, flaky, CoroutineScope(StandardTestDispatcher(time))) { time.currentTime + BASE }
        l.startLocked()
        assertEquals(LockState.Locked, l.state.value)
        assertFalse(lockState())
    }

    @Test fun startLockedRethrowsWhenTheWriteKeepsFailing() = runTest {
        val broken = object : ChannelOverlay(db) {
            override suspend fun setLockState(unlocked: Boolean) = throw java.io.IOException("disk")
        }
        val l = ParentalLock(settings(), edits, broken, CoroutineScope(StandardTestDispatcher(time))) { 0L }
        try {
            l.startLocked()
            org.junit.Assert.fail("expected the write failure")
        } catch (_: java.io.IOException) {
        }
        assertEquals(LockState.Locked, l.state.value)
    }

    @Test fun startLockedStaysLockedWhenSettingsCannotBeRead() = runTest {
        val unreadable = SettingsStore(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> { throw java.io.IOException("read") }
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ) = throw java.io.IOException("write")
        })
        val l = ParentalLock(unreadable, edits, overlay, CoroutineScope(StandardTestDispatcher(time))) { 0L }
        l.startLocked()
        assertEquals(LockState.Locked, l.state.value)
        assertFalse(lockState())
    }

    private companion object {
        const val BASE = 1_000_000L
    }
}
