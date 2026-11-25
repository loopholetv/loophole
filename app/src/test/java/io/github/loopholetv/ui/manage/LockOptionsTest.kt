package io.github.loopholetv.ui.manage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.db.loadAll
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.db.ref
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LockOptionsTest {
    private lateinit var db: AppDatabase
    private lateinit var overlay: ChannelOverlay
    private lateinit var edits: ChannelEdits

    @Before fun setUp() {
        db = inMemoryDb()
        overlay = ChannelOverlay(db)
        edits = ChannelEdits(db, overlay)
    }

    @After fun tearDown() = db.close()

    private fun channelLabels(locked: Boolean = false, available: Boolean = true, hidden: Boolean = false) =
        channelEditOptions(testChannel(), hidden = hidden, canMove = false, locked = locked, lockAvailable = available) {}
            .map { it.label }

    private fun groupLabels(locked: Boolean = false, available: Boolean = true, hidden: Boolean = false) =
        groupEditOptions(hidden = hidden, sort = GroupSort.PLAYLIST, locked = locked, lockAvailable = available) {}
            .map { it.label }

    @Test fun lockRowFollowsHideRow() {
        assertEquals(listOf("Hide channel", "Lock channel", "Rename"), channelLabels().take(3))
        assertEquals(listOf("Show channel", "Lock channel"), channelLabels(hidden = true).take(2))
        assertEquals(listOf("Hide group", "Lock group", "Rename group"), groupLabels().take(3))
        assertEquals(listOf("Show group", "Lock group"), groupLabels(hidden = true).take(2))
    }

    @Test fun lockHiddenWhenLockedWithPin() {
        assertTrue(channelLabels(available = false).none { "ock" in it })
        assertTrue(groupLabels(available = false).none { "ock" in it })
        // The default offers nothing either: callers opt in.
        assertEquals(
            channelLabels(available = false),
            channelEditOptions(testChannel(), hidden = false, canMove = false) {}.map { it.label },
        )
    }

    @Test fun unlockRowWhenLocked() {
        assertEquals("Unlock channel", channelLabels(locked = true)[1])
        assertEquals("Unlock group", groupLabels(locked = true)[1])
    }

    @Test fun pickingTheLockRowsReportsTheEdit() {
        val picked = mutableListOf<Any>()
        channelEditOptions(testChannel(), hidden = false, canMove = false, locked = false, lockAvailable = true) { picked += it }
            .first { it.label == "Lock channel" }.onClick()
        channelEditOptions(testChannel(), hidden = false, canMove = false, locked = true, lockAvailable = true) { picked += it }
            .first { it.label == "Unlock channel" }.onClick()
        groupEditOptions(hidden = false, sort = GroupSort.NAME, locked = false, lockAvailable = true) { picked += it }
            .first { it.label == "Lock group" }.onClick()
        groupEditOptions(hidden = false, sort = GroupSort.NAME, locked = true, lockAvailable = true) { picked += it }
            .first { it.label == "Unlock group" }.onClick()
        assertEquals(listOf(ChannelEdit.Lock, ChannelEdit.Unlock, GroupEdit.Lock, GroupEdit.Unlock), picked)
    }

    @Test fun firstLockRunsSetPin() = runTest {
        val fake = FakeEditActions()
        val actions = ChannelEditActions(fake, this) {}
        val order = mutableListOf<String>()
        var done = 0
        val pinSet: suspend () -> Boolean = { order += "pin"; fake.calls.size.let { order += "writes=$it" }; true }

        actions.setLocked(testChannel(), true, pinSet) { done++ }
        actions.setGroupLocked(1, "News", true, pinSet) { done++ }
        advanceUntilIdle()

        // The PIN step comes first each time: no write has happened when it runs the first time.
        assertEquals(listOf("pin", "writes=0", "pin", "writes=1"), order)
        assertEquals(listOf("setLocked(1:k1, true)", "setGroupLocked(1, News, true)"), fake.calls)
        assertEquals(2, done)
    }

    @Test fun lockedNothingWhenThePinStepIsCancelled() = runTest {
        val fake = FakeEditActions()
        val actions = ChannelEditActions(fake, this) {}
        var done = 0

        actions.setLocked(testChannel(), true, { false }) { done++ }
        actions.setGroupLocked(1, "News", true, { false }) { done++ }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fake.calls)
        // The dialog still closes.
        assertEquals(2, done)
    }

    @Test fun unlockAsksThePinToo() = runTest {
        val fake = FakeEditActions()
        val actions = ChannelEditActions(fake, this) {}
        var asked = 0

        // A menu left open across a relock: the PIN step is cancelled, nothing is unlocked.
        actions.setLocked(testChannel(), false, { asked++; false }) {}
        actions.setGroupLocked(1, "News", false, { asked++; false }) {}
        advanceUntilIdle()
        assertEquals(2, asked)
        assertEquals(emptyList<String>(), fake.calls)

        // While unlocked the step answers at once.
        actions.setLocked(testChannel(), false, { asked++; true }) {}
        actions.setGroupLocked(1, "News", false, { asked++; true }) {}
        advanceUntilIdle()
        assertEquals(4, asked)
        assertEquals(listOf("setLocked(1:k1, false)", "setGroupLocked(1, News, false)"), fake.calls)
    }

    @Test fun editorLeavesOutLockedWhileLocked() = runTest {
        val p = db.addPlaylist()
        val stored = db.insertChannels(
            channel(p, "n1", 1, groupTitle = "News"), channel(p, "n2", 2, groupTitle = "News"),
            channel(p, "s1", 3, groupTitle = "Sport"), channel(p, "s2", 4, groupTitle = "Sport"),
            channel(p, "k1", 5, groupTitle = "Kids"),
        )
        edits.setGroupLocked(p, "Kids", true)
        edits.setLocked(stored.getValue("n2").ref, true)
        val dao = db.channelDao()

        // Unlocked: everything is listed, with the lock flags.
        overlay.setLockState(unlocked = true)
        val open = dao.editorGroups(p).first()
        assertEquals(listOf("Kids", "News", "Sport"), open.map { it.title })
        assertEquals(listOf(true, false, false), open.map { it.locked })
        assertEquals(listOf(1, 2, 2), open.map { it.count })
        val news = dao.editorChannels(p, "News").loadAll()
        assertEquals(listOf("n1", "n2"), news.map { it.channel.key })
        assertEquals(listOf(false, true), news.map { it.locked })

        // Locked: the locked group, the locked channel, and the locked group's channels are gone.
        overlay.setLockState(unlocked = false)
        val closed = dao.editorGroups(p).first()
        assertEquals(listOf("News", "Sport"), closed.map { it.title })
        assertEquals(listOf(1, 2), closed.map { it.count })
        assertEquals(listOf("n1"), dao.editorChannels(p, "News").loadAll().map { it.channel.key })
        assertEquals(emptyList<String>(), dao.editorChannels(p, "Kids").loadAll().map { it.channel.key })
        assertEquals(listOf("s1", "s2"), dao.editorChannels(p, "Sport").loadAll().map { it.channel.key })
    }

    @Test fun editorFlagsAreFalseWithoutLocks() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "a", 1, groupTitle = "G"))
        assertFalse(db.channelDao().editorGroups(p).first().single().locked)
        assertFalse(db.channelDao().editorChannels(p, "G").loadAll().single().locked)
    }
}
