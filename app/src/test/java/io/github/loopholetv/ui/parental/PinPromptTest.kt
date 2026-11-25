package io.github.loopholetv.ui.parental

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.data.parental.ParentalLock
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class PinPromptTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var lock: ParentalLock
    private lateinit var prompter: PinPrompter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var edits: ChannelEdits

    @Before fun setUp() {
        db = inMemoryDb()
        val overlay = ChannelOverlay(db)
        edits = ChannelEdits(db, overlay)
        store = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
            ) { File(tmp.root, "settings.preferences_pb") },
        )
        lock = ParentalLock(store, edits, overlay, scope) { System.currentTimeMillis() }
        prompter = PinPrompter(lock)
        compose.setContent { LoopholeTheme { PinPromptHost(prompter) } }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A PIN of 1234, locked again, as after a restart. */
    private fun withLockedPin() = runBlocking {
        lock.setPin("1234")
        lock.relock()
    }

    private fun ask(purpose: PinPurpose = PinPurpose.UNLOCK): Deferred<Boolean> {
        val d = scope.async { prompter.ask(purpose) }
        compose.waitUntil(TIMEOUT) { onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty() }
        return d
    }

    private fun press(keys: List<Key>) {
        compose.onNodeWithTag(PIN_OVERLAY_TAG).performKeyInput { keys.forEach { pressKey(it) } }
    }

    private fun type(pin: String) = press(pin.map(::digitKey))

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String) = compose.waitUntil(TIMEOUT) { shows(text) }

    private fun dots() = compose.onAllNodesWithText(DOT).fetchSemanticsNodes().size

    private fun awaitDotsGone() = compose.waitUntil(TIMEOUT) { dots() == 0 }

    private fun assertResult(expected: Boolean, d: Deferred<Boolean>) {
        compose.waitUntil(TIMEOUT) { d.isCompleted }
        assertEquals(expected, runBlocking { d.await() })
        compose.waitUntil(TIMEOUT) { onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().isEmpty() }
    }

    private fun onAllNodesWithTag(tag: String) = compose.onAllNodesWithTag(tag)

    /** Down from the first key to Forgot PIN? (three rows, then 0's row, Cancel, Forgot). */
    private fun toForgot() = press(List(5) { Key.DirectionDown })

    @Test fun unlockRightPinResolvesTrue() {
        withLockedPin()
        val d = ask()
        awaitText(TITLE_ENTER)
        type("1234")
        assertResult(true, d)
        assertTrue(lock.isUnlocked)
    }

    @Test fun unlockWrongPinShowsWrongPin() {
        withLockedPin()
        val d = ask()
        type("0000")
        awaitText(ERROR_WRONG)
        awaitDotsGone()
        assertFalse(d.isCompleted)
        assertFalse(lock.isUnlocked)
        type("1234")
        assertResult(true, d)
    }

    @Test fun waitShowsCountdownAndBlocksDigits() {
        withLockedPin()
        val d = ask()
        repeat(5) {
            type("0000")
            compose.waitUntil(TIMEOUT) { dots() == 0 }
        }
        awaitText(waitText(30))
        // Even the right PIN is not taken while waiting.
        type("1234")
        assertEquals(0, dots())
        assertFalse(d.isCompleted)
        assertFalse(lock.isUnlocked)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        awaitText(waitText(29))
    }

    @Test fun countdownEndsAndTypingWorksAgain() {
        val model = PinPromptModel(PinPurpose.UNLOCK, lock, scope) {}
        // A wait of three seconds, as the lock would report it.
        model.startWait(3)
        assertEquals(waitText(3), model.error)
        repeat(3) { model.tick() }
        assertNull(model.error)
        assertEquals(0, model.waitLeft)
    }

    @Test fun setNeedsTwoMatchingEntries() {
        val d = ask(PinPurpose.SET)
        awaitText(TITLE_CHOOSE)
        assertFalse(shows(FORGOT_LABEL))
        type("1111")
        awaitText(TITLE_AGAIN)
        type("2222")
        awaitText(ERROR_MISMATCH)
        awaitText(TITLE_CHOOSE)
        type("3333")
        awaitText(TITLE_AGAIN)
        type("3333")
        assertResult(true, d)
        assertTrue(runBlocking { lock.hasPin() })
        assertTrue(runBlocking { lock.matches("3333") })
    }

    @Test fun changeAsksCurrentFirst() {
        withLockedPin()
        val d = ask(PinPurpose.CHANGE)
        awaitText(TITLE_CURRENT)
        type("0000")
        awaitText(ERROR_WRONG)
        awaitDotsGone()
        type("1234")
        awaitText(TITLE_CHOOSE)
        // Choosing the new PIN is the set flow: no way to reset from there.
        assertFalse(shows(FORGOT_LABEL))
        type("5555")
        awaitText(TITLE_AGAIN)
        type("5555")
        assertResult(true, d)
        assertTrue(runBlocking { lock.matches("5555") })
        assertFalse(runBlocking { lock.matches("1234") })
    }

    @Test fun forgotPinConfirmRemovesAll() {
        val p = runBlocking {
            val p = db.addPlaylist()
            db.insertChannels(channel(p, "a1", 1, groupTitle = "A"), channel(p, "b1", 2, groupTitle = "B"))
            ChannelOverlay(db).refreshAll()
            lock.setPin("1234")
            edits.setGroupLocked(p, "A", true)
            lock.relock()
            p
        }
        assertEquals(listOf("b1"), runBlocking { db.channelDao().channelsOf(p).filter { it.visible }.map { it.key } })
        val d = ask()
        toForgot()
        press(listOf(Key.DirectionCenter))
        awaitText(CONFIRM_TITLE)
        assertTrue(shows(CONFIRM_BODY))
        // Cancel has the highlight first; Left moves to Remove.
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))
        assertResult(true, d)
        assertEquals(LockState.NoPin, lock.state.value)
        assertEquals(
            listOf("a1", "b1"),
            runBlocking { db.channelDao().channelsOf(p).filter { it.visible }.map { it.key }.sorted() },
        )
    }

    @Test fun forgotPinLabelIsNotTruncated() {
        withLockedPin()
        val d = ask()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(FORGOT_LABEL).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        assertFalse(results[0].hasVisualOverflow)
        assertTrue(results[0].multiParagraph.maxIntrinsicWidth <= results[0].size.width)
        press(listOf(Key.Back))
        assertResult(false, d)
    }

    @Test fun cancellingAShowingAskClosesItAndTheNextAskShows() {
        withLockedPin()
        val first = ask()
        first.cancel()
        compose.waitUntil(TIMEOUT) { onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().isEmpty() }
        val next = ask()
        awaitText(TITLE_ENTER)
        type("1234")
        assertResult(true, next)
    }

    @Test fun cancellingAQueuedAskLeavesTheFirstAlone() {
        withLockedPin()
        val first = ask()
        val queued = scope.async { prompter.ask() }
        compose.waitForIdle()
        queued.cancel()
        compose.waitUntil(TIMEOUT) { queued.isCancelled }
        compose.waitForIdle()
        assertEquals(1, onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().size)
        assertFalse(first.isCompleted)
        type("1234")
        assertResult(true, first)
    }

    @Test fun forgotPinCancelReturnsToPad() {
        withLockedPin()
        val d = ask()
        toForgot()
        press(listOf(Key.DirectionCenter))
        awaitText(CONFIRM_TITLE)
        press(listOf(Key.DirectionCenter))
        awaitText(TITLE_ENTER)
        assertFalse(shows(CONFIRM_TITLE))
        assertFalse(d.isCompleted)
        assertTrue(runBlocking { lock.hasPin() })
        type("1234")
        assertResult(true, d)
    }

    @Test fun backWithNoDigitsCancelsFalse() {
        withLockedPin()
        val d = ask()
        type("12")
        assertEquals(2, dots())
        press(listOf(Key.Back))
        assertEquals(1, dots())
        press(listOf(Key.Back, Key.Back))
        assertResult(false, d)
        assertFalse(lock.isUnlocked)
    }

    @Test fun remoteDigitKeysType() {
        withLockedPin()
        val d = ask()
        press(listOf(Key.Seven, Key.NumPad2))
        assertEquals(2, dots())
        // Each typed digit is a dot in its box (the pad's own keys carry the digit labels, so no digit text is checked).
        press(listOf(Key.Back, Key.Back, Key.Back))
        assertResult(false, d)
    }

    @Test fun unlockWithNothingLockedIsTrueAtOnce() {
        runBlocking { lock.startLocked() }
        assertEquals(LockState.NoPin, lock.state.value)
        assertTrue(runBlocking { prompter.ask() })
        withLockedPin()
        runBlocking { lock.unlock() }
        assertTrue(runBlocking { prompter.ask() })
        assertEquals(0, onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().size)
    }

    @Test fun aSecondAskWaitsForTheFirst() {
        withLockedPin()
        val first = ask()
        val second = scope.async { prompter.ask() }
        compose.waitForIdle()
        // One screen only; the second is not answered while the first shows.
        assertEquals(1, onAllNodesWithTag(PIN_OVERLAY_TAG).fetchSemanticsNodes().size)
        assertFalse(second.isCompleted)
        type("1234")
        assertResult(true, first)
        // The first unlocked, so the second has nothing left to ask.
        compose.waitUntil(TIMEOUT) { second.isCompleted }
        assertTrue(runBlocking { second.await() })
    }

    @Test fun ensurePinThenSetsWhenThereIsNoPin() {
        val d = scope.async { prompter.ensurePinThen() }
        awaitText(TITLE_CHOOSE)
        press(listOf(Key.Back))
        assertResult(false, d)
        withLockedPin()
        val e = scope.async { prompter.ensurePinThen() }
        awaitText(TITLE_ENTER)
        press(listOf(Key.Back))
        assertResult(false, e)
    }

    private companion object {
        const val TIMEOUT = 10_000L

        fun digitKey(c: Char): Key = listOf(
            Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
        )[c - '0']
    }
}
