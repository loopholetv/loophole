package io.github.loopholetv.ui.manage

import androidx.paging.PagingData
import io.github.loopholetv.data.db.EditorChannelRow
import io.github.loopholetv.data.db.EditorGroupRow
import io.github.loopholetv.data.db.GroupSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val edits = FakeEditActions()
    private val refreshed = mutableListOf<Long>()
    private val opened = mutableListOf<String>()
    private val groups = MutableStateFlow<List<EditorGroupRow>>(emptyList())

    private fun vm() = ManageViewModel(
        playlistId = 1,
        groups = groups,
        channels = { title: String -> opened += title; flowOf(PagingData.empty<EditorChannelRow>()) as Flow<PagingData<EditorChannelRow>> },
        edits = edits,
        refreshEpg = { refreshed += it },
    )

    private fun group(title: String, label: String = title, hidden: Boolean = false, renamed: Boolean = false, order: Int = 0) =
        EditorGroupRow(title, label, hidden, renamed, count = 3, order = order, sortMode = GroupSort.PLAYLIST)

    @Test fun groupsIncludeHiddenWithTags() = runTest(dispatcher) {
        // The DAO's list order is kept even though `order` disagrees (mixed scales).
        groups.value = listOf(
            group("Sport", order = 9),
            group("News", label = "Headlines", hidden = true, renamed = true, order = 1),
            group("Kids", hidden = true, order = 5),
            group("Films", renamed = true, order = 0),
        )
        val vm = vm()
        val sub = backgroundScope.launch { vm.groups.collect {} }
        advanceUntilIdle()
        val items = vm.groups.value
        assertEquals(listOf("Sport", "News", "Kids", "Films"), items.map { it.title })
        assertEquals(listOf("Sport", "Headlines", "Kids", "Films"), items.map { it.label })
        assertEquals(listOf(emptyList(), listOf("Hidden", "Renamed"), listOf("Hidden"), listOf("Renamed")), items.map { it.tags })
        assertEquals(listOf(null, "Hidden, Renamed", "Hidden", "Renamed"), items.map { it.tagText })
        sub.cancel()
    }

    @Test fun showingHiddenGroupCallsEdits() = runTest(dispatcher) {
        val vm = vm()
        var done = false
        vm.actions.setGroupHidden(1, "News", false) { done = true }
        advanceUntilIdle()
        assertEquals(listOf("setGroupHidden(1, News, false)"), edits.calls)
        assertEquals(true, done)
    }

    @Test fun renameBlankRestoresOriginal() = runTest(dispatcher) {
        val vm = vm()
        vm.actions.rename(testChannel(customName = "Beeb"), "  ") {}
        vm.actions.renameGroup(1, "News", "") {}
        advanceUntilIdle()
        assertEquals(listOf("rename(1:k1, null)", "renameGroup(1, News, null)"), edits.calls)
    }

    @Test fun openGroupSelectsItsChannels() = runTest(dispatcher) {
        val vm = vm()
        assertNull(vm.openGroup.value)
        val sub = backgroundScope.launch { vm.groupChannels.collect {} }
        vm.open("News")
        advanceUntilIdle()
        assertEquals("News", vm.openGroup.value)
        assertEquals(listOf("News"), opened)
        vm.close()
        assertNull(vm.openGroup.value)
        sub.cancel()
    }

    @Test fun movesGoThroughEditsAndEpgPickRefreshes() = runTest(dispatcher) {
        val vm = vm()
        vm.moveGroup("News", -1)
        vm.moveChannel(testChannel(), 1)
        vm.actions.pickEpg(testChannel(epgChannelId = "a"), "b") {}
        advanceUntilIdle()
        assertEquals(listOf("moveGroup(1, News, -1)", "move(k1, 1)", "setEpgOverride(1:k1, b)"), edits.calls)
        assertEquals(listOf(1L), refreshed)
    }

    @Test fun groupItemTagsForChannels() {
        assertEquals(emptyList<String>(), editorTags(hidden = false, renamed = false))
        assertEquals(listOf("Hidden", "Renamed"), editorTags(hidden = true, renamed = true))
        val row = EditorChannelRow(testChannel(visible = false, customName = "Beeb"), hidden = false)
        // The tag follows the channel's own hidden flag, not `visible`.
        assertEquals(listOf("Renamed"), row.tags)
        assertEquals(listOf("Hidden"), EditorChannelRow(testChannel(visible = false), hidden = true).tags)
    }

    /** I-1: the moved row is kept fully visible; the scroll target follows its index. */
    @Test fun keepInViewFollowsTheMovedRow() {
        // Rows 10..19 fully visible.
        assertNull(keepInViewScrollTarget(12, 10, 19))
        assertNull(keepInViewScrollTarget(10, 10, 19))
        assertNull(keepInViewScrollTarget(19, 10, 19))
        assertEquals(11, keepInViewScrollTarget(20, 10, 19)) // moved below: last visible row
        assertEquals(9, keepInViewScrollTarget(9, 10, 19))   // moved above: first visible row
        assertEquals(3, keepInViewScrollTarget(3, 5, 9))
        assertEquals(2, keepInViewScrollTarget(4, 0, 2))
        assertNull(keepInViewScrollTarget(3, null, null))     // nothing laid out yet
        // A run of moves down past the edge scrolls one row per move.
        var first = 10
        var last = 19
        for (index in 20..25) {
            val to = keepInViewScrollTarget(index, first, last) ?: continue
            first = to; last = to + 9
            assertEquals(index, last)
        }
        assertEquals(16, first)
    }
}
