package io.github.loopholetv.ui.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.repo.XtreamFilmDetails
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.TmdbGenreEntity
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
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
class FilmViewModelTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private lateinit var db: AppDatabase
    private lateinit var vm: FilmViewModel
    private lateinit var library: VodLibraryRepository
    private var playlist = 0L

    @get:Rule val main = MainDispatcherRule()

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        playlist = db.addPlaylist()
        db.vodWriteDao().upsertFilms(
            listOf(FilmEntity(playlist, "f1", "Heat", "Crime", "http://logo.test/h.png", "http://s.test/f1.mp4", null, null, null, 0, now)),
        )
        val repo = VodRepository(db) { now }
        library = VodLibraryRepository(db, repo) { now }
        vm = main.keep(FilmViewModel(playlist, "f1", repo, VodProgressRepository(db.vodProgressDao()) { now }, library))
    }

    @After fun tearDown() {
        main.clear()
        db.close()
        server?.shutdown()
    }

    private suspend fun saveProgress(positionMs: Long, durationMs: Long? = null) = db.vodProgressDao().upsert(
        VodProgressEntity(VodKind.MOVIE, playlist, "f1", positionMs, durationMs, false, now),
    )

    @Test fun notStartedShowsPlay() = runTest {
        val ui = vm.ui.first { it != null }!!

        assertEquals("Heat", ui.title)
        assertEquals("Crime", ui.category)
        assertEquals("http://logo.test/h.png", ui.logo)
        assertNull(ui.resumeMs)
        assertNull(ui.fraction)
        assertEquals(listOf("Play"), filmButtons(ui).map { it.label })
    }

    @Test fun startedShowsResumeAndStartOver() = runTest {
        saveProgress(2_530_000, durationMs = 5_060_000)
        val ui = vm.ui.first { it?.resumeMs != null }!!

        assertEquals(2_530_000L, ui.resumeMs)
        assertEquals(0.5f, ui.fraction!!, 0.001f)
        val buttons = filmButtons(ui)
        assertEquals(listOf("Resume from 42:10", "Start over"), buttons.map { it.label })
        assertEquals(listOf(2_530_000L, 0L), buttons.map { it.startMs })
    }

    @Test fun resumeLabelFormat() {
        assertEquals("Resume from 42:10", resumeLabel(2_530_000))
        assertEquals("Resume from 1:02:03", resumeLabel(3_723_000))
        assertEquals("Resume from 0:45", resumeLabel(45_000))
    }

    @Test fun positionBelowTheThresholdStillPlaysFromTheStart() = runTest {
        saveProgress(10_000, durationMs = 5_000_000)
        val ui = vm.ui.first { it != null }!!

        assertNull(ui.resumeMs)
        assertEquals(listOf("Play"), filmButtons(ui).map { it.label })
    }

    @Test fun filmUiReflectsMyList() = runTest {
        assertFalse(vm.ui.first { it != null }!!.inMyList)

        library.setInMyList(TitleKind.FILM, playlist, "f1", true)

        assertTrue(vm.ui.first { it?.inMyList == true }!!.inMyList)
    }

    @Test fun toggleMyListAddsAndRemoves() = runTest {
        vm.ui.first { it != null }

        vm.toggleMyList()
        vm.ui.first { it?.inMyList == true }
        assertTrue(library.inMyList(TitleKind.FILM, playlist, "f1").first())

        vm.toggleMyList()
        vm.ui.first { it?.inMyList == false }
        assertFalse(library.inMyList(TitleKind.FILM, playlist, "f1").first())
    }

    @Test fun myListLabelShowsTheState() {
        assertEquals("+ My list", myListLabel(false))
        assertEquals("✓ In My list", myListLabel(true))
    }

    private suspend fun matchHeat(badge: String? = "4K") {
        val tmdb = db.tmdbDao()
        tmdb.upsertGenres(listOf(TmdbGenreEntity(TmdbKind.MOVIE, 28, "Action", "en-US"), TmdbGenreEntity(TmdbKind.MOVIE, 80, "Crime", "en-US")))
        tmdb.upsertTitle(
            TmdbTitleEntity(
                TmdbKind.MOVIE, 949, "Heat (TMDB)", "Heat", 1995, "A thief and a detective.", "/heat.jpg", "/heat-bd.jpg",
                8.26, 7000, 170, null, "Al Pacino, Robert De Niro, Val Kilmer", "en-US", now,
            ),
            listOf(80, 28),
        )
        tmdb.upsertMatch(
            VodMatchEntity(playlist, TitleKind.FILM, "f1", "fp", MatchState.MATCHED, TmdbKind.MOVIE, 949, badge, "Heat", null, now, now),
        )
    }

    @Test fun filmUiCarriesDetailsWhenMatched() = runTest {
        matchHeat()
        val ui = vm.ui.first { it?.details != null }!!

        assertEquals("Heat (TMDB)", ui.title)
        assertEquals("https://image.tmdb.org/t/p/w342/heat.jpg", ui.logo)
        val d = ui.details!!
        assertEquals(1995, d.year)
        assertEquals(170, d.runtimeMinutes)
        assertNull(d.seasonCount)
        assertEquals(8.26, d.rating!!, 0.0001)
        assertEquals(listOf("Action", "Crime"), d.genres)
        assertEquals("A thief and a detective.", d.overview)
        assertEquals(listOf("Al Pacino", "Robert De Niro", "Val Kilmer"), d.cast)
        assertEquals("https://image.tmdb.org/t/p/w1280/heat-bd.jpg", d.backdropUrl)
        assertEquals("4K", d.badge)
        assertEquals("1995 · 2 h 50 min · ★ 8.3 · Action, Crime", formatMeta(d))
    }

    @Test fun filmUiNullDetailsWhenUnmatched() = runTest {
        db.tmdbDao().upsertMatch(
            VodMatchEntity(playlist, TitleKind.FILM, "f1", "fp", MatchState.NOT_FOUND, null, null, "4K", "Heat", null, now, now),
        )
        val ui = vm.ui.first { it != null }!!

        assertNull(ui.details)
        assertEquals("Heat", ui.title)
        assertEquals("http://logo.test/h.png", ui.logo)
    }

    @Test fun rejectDetailsRevertsToPlaylistData() = runTest {
        matchHeat()
        assertNotNull(vm.ui.first { it?.details != null })

        vm.rejectDetails()
        val ui = vm.ui.first { it != null && it.details == null }!!

        assertEquals("Heat", ui.title)
        assertEquals("Crime", ui.category)
        assertEquals("http://logo.test/h.png", ui.logo)
        val m = db.tmdbDao().match(playlist, TitleKind.FILM, "f1")!!
        assertEquals(MatchState.REJECTED, m.state)
        assertNull(m.tmdbId)
        assertEquals(now, m.lookedUpAt)
    }

    // Xtream films: the provider's details, fetched when the page opens.

    private var server: MockWebServer? = null
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var reply: () -> MockResponse = { MockResponse().setBody(MATRIX_INFO) }

    private val xm = "xm:42"

    /** An Xtream playlist on a local server holding the film [xm], with the list's year and rating. */
    private suspend fun xtreamFilm(): Long {
        val s = MockWebServer().also { server = it }
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return reply()
            }
        }
        s.start()
        val p = db.addXtreamPlaylist(server = s.url("/").toString().trimEnd('/'))
        db.xtreamVodDao().upsertFilms(
            listOf(FilmEntity(p, xm, "Matrix", "Action", null, "http://s.test/42.mkv", null, null, null, 1, now, "mkv", 1998, 5.0)),
        )
        return p
    }

    private fun xvm(p: Long, key: String = xm): FilmViewModel {
        val repo = VodRepository(db) { now }
        val info = XtreamFilmDetails(db, XtreamClient(OkHttpClient()), userAgent = { "UA/1" }, clock = { now })
        return main.keep(
            FilmViewModel(p, key, repo, VodProgressRepository(db.vodProgressDao()) { now }, VodLibraryRepository(db, repo) { now }, info),
        )
    }

    @Test fun providerDetailsWhenUnmatched() = runTest {
        val p = xtreamFilm()
        val model = xvm(p)
        model.providerFetch!!.join()

        val ui = model.ui.first { it?.details?.overview != null }!!
        val d = ui.details!!

        assertEquals("1999 · 2 h 16 min · ★ 7.9 · Action", formatMeta(d))
        assertEquals("Neo wakes up.", d.overview)
        assertEquals(listOf("Keanu Reeves", "Carrie-Anne Moss"), d.cast)
        assertEquals("https://img.test/bd.jpg", d.backdropUrl)
        // Provider details have no match to reject, so the page shows no "Wrong details?".
        assertFalse(d.fromTmdb)
        assertEquals("Matrix", ui.title)
        assertEquals(1, requests.size)
        assertEquals("get_vod_info", requests[0].requestUrl!!.queryParameter("action"))
    }

    @Test fun yearAndRatingFromListBeforeDetailsArrive() = runTest {
        val p = xtreamFilm()
        val gate = CountDownLatch(1)
        reply = {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody(MATRIX_INFO)
        }
        val model = xvm(p)

        val before = model.ui.first { it?.details != null }!!.details!!
        assertEquals("1998 · ★ 5.0", formatMeta(before))
        assertNull(before.overview)
        assertFalse(before.fromTmdb)

        gate.countDown()
        model.providerFetch!!.join()
        val after = model.ui.first { it?.details?.overview != null }!!.details!!
        assertEquals("1999 · 2 h 16 min · ★ 7.9 · Action", formatMeta(after))
    }

    @Test fun tmdbWinsOverProvider() = runTest {
        val p = xtreamFilm()
        db.xtreamVodDao().upsertProviderDetails(
            ProviderDetailsEntity(p, xm, "Provider plot.", "Someone", "Western", 3.0, 1990, 90, "https://img.test/p.jpg", now),
        )
        val tmdb = db.tmdbDao()
        tmdb.upsertTitle(
            TmdbTitleEntity(TmdbKind.MOVIE, 603, "The Matrix", "The Matrix", 1999, "A hacker learns.", "/m.jpg", "/m-bd.jpg", 8.2, 9000, 136, null, null, "en-US", now),
            emptyList(),
        )
        tmdb.upsertMatch(VodMatchEntity(p, TitleKind.FILM, xm, "fp", MatchState.MATCHED, TmdbKind.MOVIE, 603, null, "Matrix", null, now, now))
        val model = xvm(p)

        val d = model.ui.first { it?.details?.fromTmdb == true }!!.details!!

        assertEquals("1999 · 2 h 16 min · ★ 8.2", formatMeta(d))
        assertEquals("A hacker learns.", d.overview)
        assertEquals("https://image.tmdb.org/t/p/w1280/m-bd.jpg", d.backdropUrl)
        // Fetched just now, so the page doesn't ask again.
        model.providerFetch?.join()
        assertTrue(requests.isEmpty())
    }

    @Test fun matchedFilmSkipsTheProviderFetch() = runTest {
        val p = xtreamFilm()
        val tmdb = db.tmdbDao()
        tmdb.upsertTitle(
            TmdbTitleEntity(TmdbKind.MOVIE, 603, "The Matrix", "The Matrix", 1999, "A hacker learns.", "/m.jpg", "/m-bd.jpg", 8.2, 9000, 136, null, null, "en-US", now),
            emptyList(),
        )
        tmdb.upsertMatch(VodMatchEntity(p, TitleKind.FILM, xm, "fp", MatchState.MATCHED, TmdbKind.MOVIE, 603, null, "Matrix", null, now, now))
        val model = xvm(p)

        model.providerFetch!!.join()

        // TMDB's details are shown, so the provider isn't asked for its own.
        assertTrue(requests.isEmpty())
        assertNull(db.xtreamVodDao().providerDetailsNow(p, xm))
    }

    @Test fun m3uFilmNeverRequests() = runTest {
        xtreamFilm()
        val model = xvm(playlist, key = "f1")

        model.ui.first { it != null }

        assertNull(model.providerFetch)
        assertTrue(requests.isEmpty())
    }

    @Test fun xtreamFilmIsAskedOncePerOpen() = runTest {
        val p = xtreamFilm()
        reply = { MockResponse().setResponseCode(500) }
        val model = xvm(p)
        model.providerFetch!!.join()

        // A failure leaves the page on the list's year and rating, and doesn't retry on its own.
        assertEquals("1998 · ★ 5.0", formatMeta(model.ui.first { it?.details != null }!!.details!!))
        assertEquals(1, requests.size)
    }

    private companion object {
        const val MATRIX_INFO = """{"info":{"plot":"Neo wakes up.","cast":"Keanu Reeves, Carrie-Anne Moss","genre":"Action",""" +
            """"rating":"7.9","releasedate":"1999-03-31","duration_secs":8160,"backdrop_path":["https://img.test/bd.jpg"]}}"""
    }
}
