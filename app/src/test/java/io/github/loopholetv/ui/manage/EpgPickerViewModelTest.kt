package io.github.loopholetv.ui.manage

import io.github.loopholetv.data.db.EpgChannelEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpgPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(i: Int) = EpgChannelEntity(1, "id$i", "Channel $i")

    @Test fun filtersDebouncedLatestWins() = runTest(dispatcher) {
        val calls = mutableListOf<String?>()
        val slow = CompletableDeferred<Unit>()
        val vm = EpgPickerViewModel(backgroundScope) { p ->
            calls += p
            if (p == "%bb%") slow.await()
            listOf(EpgChannelEntity(1, "x", "for $p"))
        }
        advanceUntilIdle()
        calls.clear()

        vm.setQuery("b")
        advanceTimeBy(100)
        vm.setQuery("bb")
        advanceTimeBy(EPG_PICKER_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf<String?>("%bb%"), calls) // "b" never searched: debounced away

        // "bb" is still running when "bbc" arrives: its late answer must not win.
        vm.setQuery("bbc")
        assertEquals("bbc", vm.query.value)
        advanceTimeBy(EPG_PICKER_DEBOUNCE_MS + 1)
        runCurrent()
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<String?>("%bb%", "%bbc%"), calls)
        assertEquals(listOf("for %bbc%"), vm.results.value.map { it.displayName })
    }

    @Test fun emptyQueryListsFirstRows() = runTest(dispatcher) {
        val calls = mutableListOf<String?>()
        val vm = EpgPickerViewModel(this) { p -> calls += p; (1..500).map(::row) }
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), calls)
        assertEquals(EPG_PICKER_LIMIT, vm.results.value.size)
        assertEquals("id1", vm.results.value.first().epgId)

        vm.setQuery("ab")
        advanceUntilIdle()
        vm.setQuery("   ")
        advanceUntilIdle()
        assertEquals(listOf(null, "%ab%", null), calls)
    }

    @Test fun patternEscapesAndTrims() {
        assertNull(epgPickerPattern(""))
        assertNull(epgPickerPattern("  "))
        assertEquals("%a%", epgPickerPattern(" a "))
        assertEquals("%50\\%\\_x\\\\%", epgPickerPattern("50%_x\\"))
    }

    @Test fun failedSearchKeepsEmptyList() = runTest(dispatcher) {
        val vm = EpgPickerViewModel(this) { throw IllegalStateException("db closed") }
        advanceUntilIdle()
        assertTrue(vm.results.value.isEmpty())
        assertTrue(vm.loaded.value)
    }

    /** M-1: the empty-state copy follows the filter the results belong to, not the live one. */
    @Test fun resultsQueryLagsTheLiveQuery() = runTest(dispatcher) {
        val vm = EpgPickerViewModel(this) { p -> if (p == null) listOf(row(1)) else emptyList() }
        advanceUntilIdle()
        vm.setQuery("zz")
        advanceUntilIdle()
        assertEquals("zz", vm.resultsQuery.value)
        assertTrue(vm.results.value.isEmpty())
        vm.setQuery("")
        assertEquals("zz", vm.resultsQuery.value) // still the old (no-match) results
        advanceUntilIdle()
        assertEquals("", vm.resultsQuery.value)
        assertEquals(1, vm.results.value.size)
    }
}
