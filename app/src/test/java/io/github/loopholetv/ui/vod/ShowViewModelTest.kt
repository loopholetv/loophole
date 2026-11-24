package io.github.loopholetv.ui.vod

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.ShowSource
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.repo.XtreamEpisodes
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.TmdbEpisodeEntity
import io.github.loopholetv.data.db.TmdbTitleEntity
import io.github.loopholetv.data.db.VodMatchEntity
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.ui.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShowViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private var playlist = 0L

    @get:Rule val main = MainDispatcherRule()

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        playlist = db.addPlaylist()
    }

    @After fun tearDown() = runTest {
        // A page fetch may still be finishing on another thread; let it land before Main is reset.
        realTime { fetching.forEach { it.episodeFetch?.join() } }
        main.clear()
        // A cancelled page can still be resuming a query on Room's thread; let it end before Main is reset.
        realTime { pages.forEach { it.viewModelScope.coroutineContext.job.join() } }
        db.close()
        server?.shutdown()
    }

    private val repo get() = VodRepository(db) { now }
    private val library get() = VodLibraryRepository(db, repo) { now }

    /** Every page a test built. */
    private val pages = CopyOnWriteArrayList<ShowViewModel>()

    private fun vm(key: String = "s") =
        main.keep(ShowViewModel(playlist, key, repo, VodProgressRepository(db.vodProgressDao()) { now }, library))
            .also { pages += it }

    private fun ep(key: String, season: Int?, episode: Int?, show: String = "s", position: Int = 0) =
        EpisodeEntity(playlist, key, show, "Drama", "Name $key", season, episode, null, "http://s.test/$key.mp4", null, null, null, position, now)

    private suspend fun insertShow(key: String = "s", episodes: List<EpisodeEntity>) {
        db.vodWriteDao().insertShows(listOf(ShowEntity(playlist, key, "The Show", "Drama", null, "http://logo.test/s.png", now, 0)))
        db.vodWriteDao().upsertEpisodes(episodes)
    }

    private suspend fun progress(key: String, positionMs: Long, watched: Boolean = false, durationMs: Long? = 1_000_000, at: Long = 1) =
        db.vodProgressDao().upsert(
            VodProgressEntity(VodKind.EPISODE, playlist, key, positionMs, durationMs, watched, Instant.ofEpochSecond(at)),
        )

    private suspend fun ShowViewModel.loaded() = ui.first { it != null }!!

    @Test fun summaryPluralisation() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        insertShow("t", (1..24).map { ep("t$it", 1 + it / 9, it, show = "t", position = it) })

        assertEquals("1 season · 1 episode", vm("s").loaded().summary)
        assertEquals("3 seasons · 24 episodes", vm("t").loaded().summary)
    }

    @Test fun summaryLeavesOutSeasonsWhenThereAreNoneNumbered() = runTest {
        insertShow("s", listOf(ep("a", null, null), ep("b", null, null, position = 1)))

        assertEquals("2 episodes", vm().loaded().summary)
    }

    @Test fun tabsSortedOtherLast() = runTest {
        insertShow("s", listOf(ep("x", null, null), ep("b", 2, 1), ep("a", 1, 1)))
        val ui = vm().loaded()

        assertEquals(listOf("Season 1", "Season 2", "Other"), ui.tabs)
    }

    @Test fun opensOnContinueSeason() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 2, 1), ep("c", 2, 2, position = 1)))
        progress("b", positionMs = 120_000)
        val ui = vm().loaded()

        assertEquals(1, ui.selectedTab)
        assertEquals(listOf("b", "c"), ui.episodes.map { it.key })
        assertEquals("b", ui.continueKey)
        assertEquals(120_000L, ui.continueStartMs)
    }

    @Test fun selectingATabShowsItsEpisodesAndSurvivesProgressUpdates() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 2, 1), ep("x", null, null)))
        val model = vm()
        model.loaded()

        model.selectTab(2)
        progress("a", positionMs = 90_000)
        val ui = model.ui.first { it?.continueKey == "a" }!!

        assertEquals(2, ui.selectedTab)
        assertEquals(listOf("x"), ui.episodes.map { it.key })
        assertEquals("", ui.episodes.single().label)
    }

    @Test fun continueLabelForStartedAndFresh() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("n", null, null, position = 2)))

        assertEquals("Play S1 E1", vm().loaded().continueLabel)

        progress("b", positionMs = 120_000)
        val started = vm().ui.first { it?.continueKey == "b" }!!
        assertEquals("Continue S1 E2", started.continueLabel)

        progress("n", positionMs = 60_000, at = 5)
        val unnumbered = vm().ui.first { it?.continueKey == "n" }!!
        assertEquals("Continue Name n", unnumbered.continueLabel)
    }

    @Test fun unnumberedFreshEpisodeSaysPlayAndItsName() = runTest {
        insertShow("s", listOf(ep("n", null, null)))

        assertEquals("Play Name n", vm().loaded().continueLabel)
    }

    @Test fun episodeRowsShowProgress() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("c", 1, 3, position = 2)))
        progress("a", positionMs = 0, watched = true, at = 1)
        progress("b", positionMs = 250_000, durationMs = 1_000_000, at = 2)
        val rows = vm().ui.first { it?.episodes?.any { r -> r.fraction != null } == true }!!.episodes

        assertEquals(listOf("E1", "E2", "E3"), rows.map { it.label })
        assertTrue(rows[0].watched)
        assertNull(rows[0].fraction)
        assertEquals(0.25f, rows[1].fraction!!, 0.001f)
        assertEquals(250_000L, rows[1].resumeMs)
        assertFalse(rows[2].watched)
        assertNull(rows[2].resumeMs)
    }

    @Test fun toggleMyListAddsAndRemoves() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        val vm = vm()
        assertFalse(vm.loaded().inMyList)

        vm.toggleMyList()
        assertTrue(vm.ui.first { it?.inMyList == true }!!.inMyList)
        assertTrue(library.inMyList(TitleKind.SHOW, playlist, "s").first())

        vm.toggleMyList()
        assertFalse(vm.ui.first { it?.inMyList == false }!!.inMyList)
    }

    @Test fun showingAnEpisodeOpensItsSeasonAtOnce() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("c", 2, 1, position = 2)))
        val model = vm()
        model.loaded()
        model.selectTab(0)
        model.ui.first { it?.selectedTab == 0 }

        // An auto-play run went on into season 2: the page must already be on it when it is drawn.
        model.showEpisode("c")

        assertEquals(1, model.ui.value!!.selectedTab)
        assertEquals(listOf("c"), model.ui.value!!.episodes.map { it.key })
    }

    @Test fun showingAnUnknownEpisodeKeepsTheContinueSeason() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 2, 1, position = 1)))
        progress("b", positionMs = 120_000)
        val model = vm()
        model.loaded()

        model.showEpisode("gone")

        assertEquals(1, model.ui.first { it != null }!!.selectedTab)
    }

    private suspend fun matchShow(episodes: List<TmdbEpisodeEntity>) {
        val tmdb = db.tmdbDao()
        tmdb.upsertTitle(
            TmdbTitleEntity(
                TmdbKind.TV, 1396, "The Show (TMDB)", null, 2008, "A teacher turns.", "/s.jpg", "/s-bd.jpg",
                9.0, 100, null, 5, "A, B", "en-US", now,
            ),
            emptyList(),
        )
        tmdb.upsertEpisodes(1396, episodes)
        tmdb.upsertMatch(
            VodMatchEntity(playlist, TitleKind.SHOW, "s", "fp", MatchState.MATCHED, TmdbKind.TV, 1396, null, "The Show", null, now, now),
        )
    }

    @Test fun showUiCarriesDetailsWhenMatched() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        matchShow(emptyList())
        val ui = vm().ui.first { it?.details != null }!!

        assertEquals("The Show (TMDB)", ui.name)
        assertEquals("https://image.tmdb.org/t/p/w342/s.jpg", ui.logo)
        assertEquals("2008 · 5 seasons · ★ 9.0", formatMeta(ui.details!!))
        assertEquals(listOf("A", "B"), ui.details!!.cast)
    }

    @Test fun episodeRowsUseTmdbEpisodeTitleWhenNumberMatches() = runTest {
        insertShow("s", listOf(ep("a", 1, 1), ep("b", 1, 2, position = 1), ep("c", 1, 3, position = 2)))
        matchShow(
            listOf(
                TmdbEpisodeEntity(1396, 1, 1, "Pilot", "It begins."),
                TmdbEpisodeEntity(1396, 1, 2, "  ", null),
            ),
        )
        val rows = vm().ui.first { it?.details != null }!!.episodes

        assertEquals("Pilot", rows[0].tmdbTitle)
        assertEquals("It begins.", rows[0].plot)
        assertEquals("S1 E1 · Pilot", rows[0].heading)
        // A blank TMDB title and an episode number TMDB lacks keep the playlist's name.
        assertNull(rows[1].tmdbTitle)
        assertEquals("Name b", rows[1].heading)
        assertNull(rows[2].tmdbTitle)
        assertNull(rows[2].plot)
        assertEquals("Name c", rows[2].heading)
    }

    @Test fun episodeWithoutTmdbRowKeepsRawName() = runTest {
        insertShow("s", listOf(ep("x", null, null)))
        matchShow(listOf(TmdbEpisodeEntity(1396, 1, 1, "Pilot", "It begins.")))
        val row = vm().ui.first { it?.details != null }!!.episodes.single()

        assertNull(row.tmdbTitle)
        assertNull(row.plot)
        assertEquals("Name x", row.heading)
    }

    @Test fun showRejectDetailsRevertsToPlaylistData() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        matchShow(listOf(TmdbEpisodeEntity(1396, 1, 1, "Pilot", "It begins.")))
        val model = vm()
        model.ui.first { it?.details != null }

        model.rejectDetails()
        val ui = model.ui.first { it != null && it.details == null }!!

        assertEquals("The Show", ui.name)
        assertEquals("1 season · 1 episode", ui.summary)
        assertNull(ui.episodes.single().tmdbTitle)
        assertEquals(MatchState.REJECTED, db.tmdbDao().match(playlist, TitleKind.SHOW, "s")!!.state)
    }

    @Test fun unmatchedShowHasNoDetails() = runTest {
        insertShow("s", listOf(ep("a", 1, 1)))
        val ui = vm().loaded()

        assertNull(ui.details)
        assertEquals("The Show", ui.name)
        assertEquals("Name a", ui.episodes.single().heading)
    }

    // --- Xtream shows: episodes fetched when the page opens ---

    private var server: MockWebServer? = null
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var reply: () -> MockResponse = { MockResponse().setBody("""{"episodes":[]}""") }

    private val xs = "xs:7"

    /** An Xtream playlist on a local server, holding the show [xs] (episodes never fetched unless [fetchedAt]). */
    private suspend fun xtreamShow(
        fetchedAt: Instant? = null,
        plot: String? = null,
        cast: String? = null,
        genre: String? = null,
        rating: Double? = null,
        year: Int? = null,
        backdrop: String? = null,
    ): Long {
        val s = MockWebServer().also { server = it }
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return reply()
            }
        }
        s.start()
        val p = db.addXtreamPlaylist(server = s.url("/").toString().trimEnd('/'))
        db.xtreamVodDao().upsertShows(
            listOf(
                ShowEntity(
                    playlistId = p, key = xs, name = "Breaking", groupTitle = "Drama", category = "Drama", logo = null,
                    firstSeenAt = now, position = 1, source = ShowSource.XTREAM, episodesFetchedAt = fetchedAt,
                    plot = plot, cast = cast, genre = genre, rating = rating, year = year, backdrop = backdrop,
                ),
            ),
        )
        return p
    }

    private fun episodesRepo() = XtreamEpisodes(db, XtreamClient(OkHttpClient()), userAgent = { "UA/1" }, clock = { now })

    /** The pages built with an episode fetcher, whose fetches tear-down waits for. */
    private val fetching = CopyOnWriteArrayList<ShowViewModel>()

    private fun xvm(p: Long, key: String = xs) = main.keep(
        ShowViewModel(p, key, repo, VodProgressRepository(db.vodProgressDao()) { now }, library, episodesRepo()),
    ).also { fetching += it; pages += it }

    private fun epJson(id: Long, num: Int) = """{"id":"$id","season":1,"episode_num":$num,"title":"T$id"}"""

    private fun answer(vararg episodes: String) {
        val body = """{"episodes":${episodes.joinToString(",", "[", "]")}}"""
        reply = { MockResponse().setBody(body) }
    }

    private fun fail() {
        reply = { MockResponse().setResponseCode(500) }
    }

    /** Runs [block] off the test's virtual clock, for the local server's thread, with a time limit. */
    private suspend fun realTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private suspend fun ShowViewModel.state(s: EpisodesState) = ui.first { it?.episodesState == s }!!

    @Test fun xtreamShowWithoutEpisodesLoadsThenLists() = runTest {
        val p = xtreamShow()
        val gate = CountDownLatch(1)
        reply = {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody("""{"episodes":[${epJson(101, 1)},${epJson(102, 2)}]}""")
        }
        val model = xvm(p)

        val loading = model.state(EpisodesState.LOADING)
        assertTrue(loading.episodes.isEmpty())
        assertEquals("Breaking", loading.name)

        gate.countDown()
        val ready = model.ui.first { it?.episodes?.isNotEmpty() == true }!!
        assertEquals(EpisodesState.READY, ready.episodesState)
        assertEquals(listOf("xe:101", "xe:102"), ready.episodes.map { it.key })
        assertEquals(1, requests.size)
    }

    @Test fun failedFetchWithoutEpisodesOffersTryAgain() = runTest {
        val p = xtreamShow()
        fail()

        val ui = xvm(p).state(EpisodesState.FAILED)

        assertTrue(ui.episodes.isEmpty())
        assertNull(ui.continueKey)
    }

    @Test fun retryFetchesAgain() = runTest {
        val p = xtreamShow()
        fail()
        val model = xvm(p)
        model.state(EpisodesState.FAILED)

        answer(epJson(101, 1))
        model.retryEpisodes()

        val ui = model.ui.first { it?.episodes?.isNotEmpty() == true }!!
        assertEquals(EpisodesState.READY, ui.episodesState)
        assertEquals(2, requests.size)
    }

    @Test fun failedFetchWithStoredEpisodesStaysReady() = runTest {
        // Fetched long ago, so the page asks again; the provider fails.
        val p = xtreamShow(fetchedAt = Instant.EPOCH)
        db.vodWriteDao().upsertEpisodes(
            listOf(EpisodeEntity(p, "xe:101", xs, "Drama", "Pilot", 1, 1, null, "http://s.test/101.mp4", null, null, null, 1, now)),
        )
        fail()
        val model = xvm(p)

        model.ui.first { it != null }
        // Let the request and its failure land; the page must still list the stored episode.
        realTime { model.episodeFetch!!.join() }
        assertEquals(1, requests.size)
        val ui = model.ui.first { it != null }!!
        assertEquals(EpisodesState.READY, ui.episodesState)
        assertEquals(listOf("xe:101"), ui.episodes.map { it.key })
        assertEquals("xe:101", ui.continueKey)
    }

    @Test fun refusedFetchOffersTryAgain() = runTest {
        val p = xtreamShow()
        reply = { MockResponse().setBody("""{"user_info":{"auth":0}}""") }

        val ui = xvm(p).state(EpisodesState.FAILED)

        assertTrue(ui.episodes.isEmpty())
        assertNull(db.xtreamVodDao().showOnce(p, xs)!!.episodesFetchedAt)
    }

    @Test fun emptyFetchSaysNoEpisodesYet() = runTest {
        val p = xtreamShow()
        answer()

        val ui = xvm(p).state(EpisodesState.EMPTY)

        assertTrue(ui.episodes.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test fun providerDetailsShownWithoutTmdbMatch() = runTest {
        val p = xtreamShow(
            fetchedAt = now, plot = "A teacher turns.", cast = "Bryan Cranston, Aaron Paul", genre = "Drama, Crime",
            rating = 8.9, year = 2008, backdrop = "https://img.test/bd.jpg",
        )

        val ui = xvm(p).ui.first { it?.details != null }!!
        val d = ui.details!!

        assertEquals("2008 · ★ 8.9 · Drama, Crime", formatMeta(d))
        assertEquals("A teacher turns.", d.overview)
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), d.cast)
        assertEquals("https://img.test/bd.jpg", d.backdropUrl)
        assertFalse(d.fromTmdb)
        assertEquals("Breaking", ui.name)
        // Fetched just now, with nothing in it: no request, and nothing to list yet.
        assertEquals(EpisodesState.EMPTY, ui.episodesState)
        assertTrue(requests.isEmpty())
    }

    @Test fun tmdbDetailsWinOverProvider() = runTest {
        db.vodWriteDao().insertShows(
            listOf(
                ShowEntity(
                    playlist, "s", "The Show", "Drama", null, null, now, 0,
                    plot = "Provider plot.", genre = "Western", rating = 3.0, year = 1990,
                ),
            ),
        )
        db.vodWriteDao().upsertEpisodes(listOf(ep("a", 1, 1)))
        matchShow(emptyList())

        val d = vm().ui.first { it?.details?.fromTmdb == true }!!.details!!

        assertEquals("2008 · 5 seasons · ★ 9.0", formatMeta(d))
        assertEquals("A teacher turns.", d.overview)
    }

    @Test fun m3uShowNeverFetches() = runTest {
        val p = xtreamShow()
        // An M3U show (its key is not an Xtream one) on the same playlist, with no episodes.
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, "m3u-show", "Plain", "Drama", null, null, now, 0)))

        val model = xvm(p, key = "m3u-show")
        val ui = model.ui.first { it != null }!!

        assertNull(model.episodeFetch)
        assertEquals(EpisodesState.READY, ui.episodesState)
        assertTrue(requests.isEmpty())
    }
}
