package io.github.loopholetv.ui.search

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.ProgrammeHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = Instant.parse("2026-11-09T20:00:00Z")
    private val channelCalls = mutableListOf<String>()
    private val programmeCalls = mutableListOf<Pair<String, Instant>>()
    private var channelRows: suspend (String) -> List<ChannelEntity> = { emptyList() }
    private var programmeRows: suspend (String) -> List<ProgrammeHit> = { emptyList() }

    private fun vm() = SearchViewModel(
        searchChannels = { p -> channelCalls += p; channelRows(p) },
        searchProgrammes = { p, t -> programmeCalls += p to t; programmeRows(p) },
        clock = { now },
        io = dispatcher,
    )

    private fun channel(id: Long, name: String) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = name, streamUrl = "http://x/$id",
        logo = null, groupTitle = "G", groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = "e$id",
    )

    private fun hit(id: Long, title: String, start: Instant, stop: Instant) = ProgrammeHit(
        programme = ProgrammeEntity(
            id = id, epgChannelId = "e1", start = start, stop = stop, title = title,
            subTitle = null, description = null, category = null, generation = 1,
        ),
        channelId = 1, channelName = "BBC One", channelNumber = 1, channelLogo = null, shiftMinutes = 0,
    )

    private fun TestScope.settle() { advanceTimeBy(SEARCH_DEBOUNCE_MS + 1); advanceUntilIdle() }

    @Test fun shortQueryShowsHint() = runTest(dispatcher) {
        val vm = vm()
        for (q in listOf("", "a", "  a ")) {
            vm.setQuery(q)
            settle()
            assertEquals(SearchUi.Hint, vm.results.value)
        }
        assertTrue(channelCalls.isEmpty())
        assertTrue(programmeCalls.isEmpty())
    }

    @Test fun debouncesAndKeepsLatest() = runTest(dispatcher) {
        val vm = vm()
        vm.setQuery("ne")
        advanceTimeBy(100)
        vm.setQuery("new")
        advanceTimeBy(100)
        vm.setQuery("news")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue("no call before the debounce elapses", channelCalls.isEmpty())
        settle()
        assertEquals(listOf("%news%"), channelCalls)
        assertEquals(listOf("%news%"), programmeCalls.map { it.first })
        assertEquals("news", vm.query.value)
    }

    @Test fun resultsCombineBothLists() = runTest(dispatcher) {
        val c = channel(1, "News 24")
        val p = hit(7, "News at Ten", now.plusSeconds(3600), now.plusSeconds(5400))
        channelRows = { listOf(c) }
        programmeRows = { listOf(p) }
        val vm = vm()
        vm.setQuery("news")
        settle()
        assertEquals(SearchUi.Results(listOf(c), listOf(p)), vm.results.value)
    }

    @Test fun channelsAppearBeforeProgrammesFinish() = runTest(dispatcher) {
        val c = channel(1, "News 24")
        val p = hit(7, "News at Ten", now.plusSeconds(3600), now.plusSeconds(5400))
        val gate = CompletableDeferred<Unit>()
        channelRows = { listOf(c) }
        programmeRows = { gate.await(); listOf(p) }
        val vm = vm()
        vm.setQuery("news")
        settle()
        assertEquals(SearchUi.Results(listOf(c), emptyList(), programmesLoading = true), vm.results.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SearchUi.Results(listOf(c), listOf(p)), vm.results.value)
    }

    @Test fun newQueryCancelsSlowProgrammeSearch() = runTest(dispatcher) {
        val never = CompletableDeferred<List<ProgrammeHit>>()
        programmeRows = { if (it == "%news%") never.await() else emptyList() }
        channelRows = { if (it == "%bbc%") listOf(channel(2, "BBC")) else emptyList() }
        val vm = vm()
        vm.setQuery("news")
        settle()
        vm.setQuery("bbc")
        settle()
        assertEquals(SearchUi.Results(listOf(channel(2, "BBC")), emptyList()), vm.results.value)
    }

    @Test fun noMatchesGivesEmptyWithQuery() = runTest(dispatcher) {
        val vm = vm()
        vm.setQuery(" zzz ")
        settle()
        assertEquals(SearchUi.Empty("zzz"), vm.results.value)
    }

    @Test fun daoFailureGivesEmpty() = runTest(dispatcher) {
        programmeRows = { error("disk I/O") }
        val vm = vm()
        vm.setQuery("news")
        settle()
        assertEquals(SearchUi.Empty("news"), vm.results.value)
        channelRows = { error("disk I/O") }
        vm.setQuery("bbc")
        settle()
        assertEquals(SearchUi.Empty("bbc"), vm.results.value)
    }

    @Test fun programmeSearchUsesClock() = runTest(dispatcher) {
        val vm = vm()
        vm.setQuery("news")
        settle()
        assertEquals(listOf("%news%" to now), programmeCalls)
    }

    @Test fun searchingShownWhileWorking() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        channelRows = { gate.await(); emptyList() }
        val vm = vm()
        vm.setQuery("news")
        settle()
        assertEquals(SearchUi.Searching, vm.results.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SearchUi.Empty("news"), vm.results.value)
    }

    @Test fun onNowMeansAiring() {
        val h = hit(1, "x", now.minusSeconds(60), now.plusSeconds(60))
        assertTrue(h.isOnNow(now))
        assertFalse(h.isOnNow(now.plusSeconds(60)))
        assertFalse(hit(2, "y", now.plusSeconds(60), now.plusSeconds(120)).isOnNow(now))
    }

    @Test fun programmeHitShiftedForNowFlag() {
        // Stored 18:30–19:30, so not airing at 20:00 as stored; a +60 min shift shows it 19:30–20:30.
        val stored = hit(1, "Late", now.minusSeconds(90 * 60), now.minusSeconds(30 * 60))
        assertFalse(stored.isOnNow(now))
        val plusHour = stored.copy(shiftMinutes = 60)
        assertTrue(plusHour.isOnNow(now))
        assertEquals(now.minusSeconds(30 * 60), plusHour.shown.start)
        assertEquals(now.plusSeconds(30 * 60), plusHour.shown.stop)
        assertEquals("the stored row is untouched", now.minusSeconds(90 * 60), plusHour.programme.start)
        // Stored 20:30–21:00, not airing yet; shifted earlier it can be.
        val later = hit(2, "Next", now.plusSeconds(30 * 60), now.plusSeconds(60 * 60))
        assertFalse(later.isOnNow(now))
        assertTrue(later.copy(shiftMinutes = -45).isOnNow(now)) // 19:45–20:15
        assertFalse(later.copy(shiftMinutes = -60).isOnNow(now)) // 19:30–20:00: the stop is exclusive
    }
}
