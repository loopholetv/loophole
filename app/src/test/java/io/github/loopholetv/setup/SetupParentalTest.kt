package io.github.loopholetv.setup

import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.data.parental.PinCheck
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.ui.cast.TvAction
import io.github.loopholetv.ui.cast.TvsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Copying a setup with locks and a PIN: the receiver's PIN step, and what lands after the import. */
@RunWith(RobolectricTestRunner::class)
class SetupParentalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Volatile private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val tvs = ArrayList<SetupTestTv>()

    @After fun tearDown() {
        tvs.forEach { it.close() }
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun tv(id: String, name: String) = SetupTestTv(id, name, tmp.root, scope, http, { now }).also { tvs += it }

    /** [a] sends to [b]; [a] has a PIN, a locked channel, group and show. [b] has its own playlist. */
    private fun pair(receiverPin: String?, senderPin: String? = "4321"): Pair<SetupTestTv, SetupTestTv> {
        val a = tv("a", "Living room")
        val b = tv("b", "Bedroom")
        a.sees(b)
        b.sees(a)
        val id = a.addPlaylist("Main")
        runBlocking {
            a.edits.setLocked(ChannelRef(id, "ch1"), true)
            a.edits.setGroupLocked(id, "News", true)
            a.edits.setShowLocked(id, "show1", true)
            if (senderPin != null) {
                a.lock.setPin(senderPin)
                a.settings.update { it.copy(parental = it.parental.copy(unlockMinutes = 30, settingsLock = SettingsLock.ALL)) }
            }
        }
        b.addPlaylist("Old")
        if (receiverPin != null) runBlocking { b.lock.setPin(receiverPin) }
        return a to b
    }

    /** Sends a's setup to b up to the code being accepted. */
    private fun send(a: SetupTestTv, b: SetupTestTv) {
        a.controller.sendTo("b")
        val showing = b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode
        a.await { it is SetupState.EnterCode }
        a.controller.enterCode(showing.code)
    }

    private fun lockedKeys(tv: SetupTestTv): Triple<List<String>, List<String>, List<String>> = runBlocking {
        val dao = tv.db.setupDao()
        val shows = tv.db.lockDao().lockedShows().first().map { it.key }
        Triple(
            dao.channelPrefs().filter { it.locked }.map { it.channelKey },
            dao.groupPrefs().filter { it.locked }.map { it.groupTitle },
            shows,
        )
    }

    @Test fun receiverWithPinAsksBeforeImport() {
        val (a, b) = pair(receiverPin = "1111")
        send(a, b)
        assertEquals(SetupState.AskPin("Living room"), b.await { it is SetupState.AskPin })
        assertEquals("nothing imported before the PIN", 0, b.importCalls.get())
        assertEquals(listOf("Old"), b.playlistNames())

        b.controller.enterCode("1111")
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(1, b.importCalls.get())
        assertEquals(listOf("Main"), b.playlistNames())
        assertEquals(Triple(listOf("ch1"), listOf("News"), listOf("show1")), lockedKeys(b))
        // The sender's PIN and options replaced the receiver's.
        val parental = runBlocking { b.settings.current().parental }
        assertEquals(runBlocking { a.settings.current().parental }, parental)
        assertEquals(30, parental.unlockMinutes)
        assertEquals(SettingsLock.ALL, parental.settingsLock)
        assertEquals(PinCheck.Ok, runBlocking { b.lock.check("4321") })
    }

    @Test fun receiverWrongPinThenCancelKeepsLocks() {
        val (a, b) = pair(receiverPin = "1111")
        val oldId = runBlocking { b.db.setupDao().playlists().single().id }
        runBlocking { b.edits.setGroupLocked(oldId, "Mine", true) }
        send(a, b)
        b.await { it is SetupState.AskPin }

        b.controller.enterCode("0000")
        val wrong = b.await { it is SetupState.AskPin && it.error != null } as SetupState.AskPin
        assertEquals(SetupController.WRONG_PIN, wrong.error)
        assertEquals(0, b.importCalls.get())

        b.controller.cancel()
        assertEquals(SetupState.Idle, b.state)
        assertEquals(SetupState.Done(SetupController.CANCELLED, copied = false), a.awaitDone())
        assertEquals(0, b.importCalls.get())
        assertEquals(listOf("Old"), b.playlistNames())
        assertEquals(Triple(emptyList<String>(), listOf("Mine"), emptyList<String>()), lockedKeys(b))
        assertEquals(PinCheck.Ok, runBlocking { b.lock.check("1111") })
    }

    @Test fun receiverWrongPinsFollowTheLimit() {
        val (a, b) = pair(receiverPin = "1111")
        send(a, b)
        b.await { it is SetupState.AskPin }
        var last: SetupState.AskPin? = null
        repeat(5) { i ->
            b.controller.enterCode("0000")
            last = b.await { it is SetupState.AskPin && it.error != null && it !== last && it != last } as SetupState.AskPin
            if (i < 4) assertEquals(SetupController.WRONG_PIN, last!!.error)
        }
        assertEquals(SetupController.waitPin(30), last!!.error)
        // Even the right PIN waits out the refusal.
        b.controller.enterCode("1111")
        Thread.sleep(300)
        assertTrue(b.state is SetupState.AskPin)
        assertEquals(0, b.importCalls.get())
    }

    @Test fun receiverWithoutPinImportsDirectly() {
        val (a, b) = pair(receiverPin = null)
        send(a, b)
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(1, b.importCalls.get())
        assertEquals(Triple(listOf("ch1"), listOf("News"), listOf("show1")), lockedKeys(b))
        assertEquals(PinCheck.Ok, runBlocking { b.lock.check("4321") })
    }

    @Test fun importStartsLocked() {
        val (a, b) = pair(receiverPin = "1111")
        runBlocking { b.lock.unlock() }
        assertTrue(b.lock.isUnlocked)
        send(a, b)
        b.await { it is SetupState.AskPin }
        b.controller.enterCode("1111")
        b.awaitDone()
        assertEquals(LockState.Locked, b.lock.state.value)
        assertFalse("the lock_state row is closed too", runBlocking { b.db.lockDao().unlocked().first() })
    }

    @Test fun importFromASenderWithoutAPinClearsTheReceiversPin() {
        val (a, b) = pair(receiverPin = "1111", senderPin = null)
        runBlocking { a.edits.clearAllLocks() }
        send(a, b)
        b.await { it is SetupState.AskPin }
        b.controller.enterCode("1111")
        b.awaitDone()
        assertEquals(ParentalSettings(), runBlocking { b.settings.current().parental })
        assertEquals(LockState.NoPin, b.lock.state.value)
        assertNull(runBlocking { b.settings.current().parental.pinHash })
        assertNotNull(b.state)
    }

    /** [b] asks for [a]'s setup; [a] shows [EnterCode] only after its PIN. */
    private fun pull(a: SetupTestTv, b: SetupTestTv): SetupState.ShowingCode {
        b.controller.copyFrom("a")
        return b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode
    }

    @Test fun askedSenderWithPinAsksBeforeSending() {
        val (a, b) = pair(receiverPin = null)
        runBlocking { a.lock.relock() }
        val showing = pull(a, b)
        // The random code can equal the PIN (1 in 10,000): typed at the PIN step it would then be right.
        org.junit.Assume.assumeTrue(showing.code != "4321")
        assertEquals(SetupState.AskPin("Bedroom", sending = true), a.await { it is SetupState.AskPin })
        // The other TV's code typed now is only a wrong PIN: nothing is sent.
        a.controller.enterCode(showing.code)
        val first = a.await { it is SetupState.AskPin && it.rejected == 1 } as SetupState.AskPin
        assertEquals(SetupController.WRONG_PIN, first.error)
        assertTrue(b.state is SetupState.ShowingCode)

        a.controller.enterCode("4321")
        assertEquals(SetupState.EnterCode("Bedroom", asked = true, triesLeft = null, error = null), a.await { it is SetupState.EnterCode })
        a.controller.enterCode(showing.code)
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(1, b.importCalls.get())
    }

    @Test fun askedSenderCancelAtThePinSendsNothing() {
        val (a, b) = pair(receiverPin = null)
        runBlocking { a.lock.relock() }
        pull(a, b)
        a.await { it is SetupState.AskPin }
        a.controller.enterCode("0000")
        a.await { it is SetupState.AskPin && it.error != null }
        a.controller.cancel()
        assertEquals(SetupState.Idle, a.state)
        assertEquals(SetupState.Done(SetupController.CANCELLED, copied = false), b.awaitDone())
        assertEquals(0, b.importCalls.get())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun askedSenderThatIsUnlockedDoesNotAsk() {
        val (a, b) = pair(receiverPin = null)
        runBlocking { a.lock.unlock() }
        pull(a, b)
        assertEquals(SetupState.EnterCode("Bedroom", asked = true, triesLeft = null, error = null), a.await { it is SetupState.EnterCode })
    }

    @Test fun importClosesTheStoredLockStateInItsCommitEvenIfRelockFails() {
        val b = tv("b", "Bedroom")
        runBlocking { b.lock.setPin("1111") }
        assertTrue(runBlocking { b.db.lockDao().unlocked().first() })
        val result = runBlocking {
            SetupImporter(b.db, b.settings, b.files, startLocked = { throw IllegalStateException("write failed") }).import(sampleBundle())
        }
        assertTrue(result is SetupImportResult.Ok)
        assertFalse(runBlocking { b.db.lockDao().unlocked().first() })
    }

    @Test fun aTvWithNoPinIsLockedInMemoryWhileAnImportCommitsLocks() {
        val b = tv("b", "Bedroom")
        runBlocking { b.lock.startLocked() }
        assertEquals(LockState.NoPin, b.lock.state.value)
        var during: LockState? = null
        val result = runBlocking {
            SetupImporter(
                b.db, b.settings, b.files,
                afterSettingsWritten = { during = b.lock.state.value },
                lockForImport = b.lock::lockForImport,
                startLocked = b.lock::startLocked,
            ).import(sampleBundle())
        }
        assertTrue(result is SetupImportResult.Ok)
        // No play could skip the lock lookup while the sender's PIN and locks were committing.
        assertEquals(LockState.Locked, during)
        assertEquals(LockState.Locked, b.lock.state.value)
    }

    @Test fun aFailedImportReturnsTheLockStateToWhatTheSettingsSay() {
        val b = tv("b", "Bedroom")
        runBlocking { b.lock.startLocked() }
        val result = runBlocking {
            SetupImporter(
                b.db, b.settings, b.files,
                afterSettingsWritten = { throw IllegalStateException("commit failed") },
                lockForImport = b.lock::lockForImport,
                startLocked = b.lock::startLocked,
            ).import(sampleBundle())
        }
        assertEquals(SetupImportResult.Failed, result)
        assertEquals(LockState.NoPin, b.lock.state.value)

        // A TV with a PIN that was unlocked ends up locked: the import was refused, not undone halfway open.
        runBlocking { b.lock.setPin("1111") }
        runBlocking {
            SetupImporter(
                b.db, b.settings, b.files,
                afterSettingsWritten = { throw IllegalStateException("commit failed") },
                lockForImport = b.lock::lockForImport,
                startLocked = b.lock::startLocked,
            ).import(sampleBundle())
        }
        assertEquals(LockState.Locked, b.lock.state.value)
        assertFalse(runBlocking { b.db.lockDao().unlocked().first() })
    }

    @Test fun senderWithPinAsksBeforeSending() {
        for (lock in SettingsLock.entries) {
            assertTrue("PIN, $lock", viewModel(ParentalSettings(pinHash = "h", pinSalt = "s", settingsLock = lock)).needsPin(TvAction.SEND_TO))
            assertFalse("no PIN, $lock", viewModel(ParentalSettings(settingsLock = lock)).needsPin(TvAction.SEND_TO))
        }
        assertFalse(viewModel(ParentalSettings(pinHash = "h", pinSalt = "s")).needsPin(TvAction.COPY_FROM))
        assertFalse(viewModel(ParentalSettings(pinHash = "h", pinSalt = "s")).needsPin(TvAction.JOIN))
    }

    private fun viewModel(parental: ParentalSettings) = TvsViewModel(
        io.github.loopholetv.cast.NoCast, flowOf(false), NoSetup, flowOf(parental),
    )
}
