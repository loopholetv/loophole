package io.github.loopholetv.data.tmdb

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.vod.TitleKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/** A scripted TMDB: answers from the maps below and records every request as "path|language". */
private class FakeTmdb : TmdbDetailsSource {
    val calls = mutableListOf<String>()
    val movies = mutableMapOf<Pair<Long, String>, MovieDetails>()
    val shows = mutableMapOf<Pair<Long, String>, TvDetails>()
    val seasons = mutableMapOf<Pair<Long, Int>, List<EpisodeDetails>>()
    val movieSearch = mutableMapOf<String, List<SearchHit>>()
    val tvSearch = mutableMapOf<String, List<SearchHit>>()
    var badKey = false

    /** Requests whose path starts with this are refused as a bad key. */
    var badKeyFor: String? = null

    /** Requests whose path starts with one of these fail as network trouble (no Retry-After). */
    val failFor = mutableSetOf<String>()

    /** Called before each season request (e.g. to start a recording mid-show). */
    var beforeSeason: () -> Unit = {}

    /** Throws (as a killed process would stop) on the detail request after this many. */
    var killAfterDetails: Int? = null
    private var details = 0

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    private fun <T> answer(call: String, value: () -> T?): TmdbResult<T> {
        calls += call
        if (badKey || badKeyFor?.let { call.startsWith(it) } == true) return TmdbResult.BadKey
        if (failFor.any { call.startsWith(it) }) return TmdbResult.Failed(null)
        return value()?.let { TmdbResult.Ok(it) } ?: TmdbResult.NotFound
    }

    /** Called on each detail request (e.g. to move the clock on). */
    var onDetail: suspend () -> Unit = {}

    private suspend fun detail() {
        killAfterDetails?.let { if (details >= it) throw CancellationException("killed") }
        details++
        onDetail()
    }

    override suspend fun findImdb(id: String, language: String) = answer("find/$id|$language") { FindResult(emptyList(), emptyList()) }
    override suspend fun searchMovie(query: String, year: Int?, language: String) =
        answer("search/movie/$query|$language") { movieSearch[query].orEmpty() }
    override suspend fun searchTv(query: String, year: Int?, language: String) =
        answer("search/tv/$query|$language") { tvSearch[query].orEmpty() }
    override suspend fun movie(id: Long, language: String): TmdbResult<MovieDetails> {
        detail()
        return answer("movie/$id|$language") { movies[id to language] }
    }
    override suspend fun tv(id: Long, language: String): TmdbResult<TvDetails> {
        detail()
        return answer("tv/$id|$language") { shows[id to language] }
    }
    override suspend fun season(tvId: Long, season: Int, language: String) =
        beforeSeason().let { _ -> answer("season/$tvId/$season|$language") { seasons[tvId to season] } }
    override suspend fun genres(kind: TmdbKind, language: String) = answer("genres/$kind|$language") {
        if (kind == TmdbKind.MOVIE) listOf(Genre(28, "Action"), Genre(878, "Science Fiction")) else listOf(Genre(18, "Drama"))
    }
}

@RunWith(RobolectricTestRunner::class)
class TmdbSyncTest {
    private lateinit var db: AppDatabase
    private val tmdb = FakeTmdb()
    private var p = 0L
    private val t0 = Instant.parse("2026-11-01T12:00:00Z")
    private var now = t0
    private var key = true
    private var recording = false
    private var lang = "en-US"
    private var badKeyCalls = 0

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    /** The vod_matches row count after each batch write. */
    private val writes = mutableListOf<Int>()

    private fun sync() = TmdbSync(
        db = db, client = tmdb, matcher = TmdbMatcher(tmdb), keyPresent = { key }, recordingActive = { recording },
        language = { lang }, clock = { now }, onBadKey = { badKeyCalls++ },
        transaction = { block ->
            db.withTransaction { block() }
            writes += db.rowCount("vod_matches")
        },
    )

    private fun days(n: Long) = t0.plus(Duration.ofDays(n))

    private fun hit(id: Long, title: String, year: Int? = null) = SearchHit(id, title, null, year, 100)

    private fun movie(id: Long, title: String, overview: String? = "Plot of $title", year: Int? = 1999) = MovieDetails(
        id, title, title, year, overview, "/p$id.jpg", "/b$id.jpg", 8.2, 25000, 136, listOf(28, 878),
        listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss"),
    )

    private fun show(id: Long, title: String, seasons: List<Int> = listOf(1, 2)) = TvDetails(
        id, title, title, 2008, "Plot of $title", "/p$id.jpg", "/b$id.jpg", 9.0, 1000, seasons.size, seasons,
        listOf(18), listOf("Bryan Cranston"),
    )

    /** A film the fake can match by name: search hit plus details. */
    private fun knownFilm(id: Long, title: String) {
        tmdb.movieSearch[title] = listOf(hit(id, title))
        tmdb.movies[id to "en-US"] = movie(id, title)
    }

    private suspend fun addFilm(key: String, name: String, tvgId: String? = null, pl: Long = p) =
        db.vodWriteDao().upsertFilms(
            listOf(FilmEntity(pl, key, name, "Films", null, "http://s.example.test/$key", null, null, tvgId, 0, t0)),
        )

    private suspend fun addShow(key: String, name: String, tvgId: String? = null, episodes: Int = 2) {
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, key, name, "Series", null, null, t0, 0)))
        db.vodWriteDao().upsertEpisodes(
            (1..episodes).map { n ->
                EpisodeEntity(
                    p, "$key-e$n", key, "Series", "$name S01 E0$n", 1, n, null,
                    "http://s.example.test/$key/$n", null, null, tvgId, n, t0,
                )
            },
        )
    }

    private suspend fun match(key: String, kind: TitleKind = TitleKind.FILM, pl: Long = p) = db.tmdbDao().match(pl, kind, key)

    @Test fun noKeyMakesNoRequests() = runTest {
        key = false
        addFilm("f1", "The Matrix")
        assertEquals(SyncOutcome.Done, sync().run())
        assertTrue(tmdb.calls.isEmpty())
        assertNull(match("f1"))
    }

    @Test fun forgetsTheTitlesItSawWhenTheRunEnds() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "The Matrix")
        val sync = sync()
        assertEquals(SyncOutcome.Done, sync.run())
        assertEquals(0, sync.titlesHeld)
    }

    @Test fun matchesAndStoresFilmWithGenresAndCast() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "EN | The Matrix (1999) 4K")
        assertEquals(SyncOutcome.Done, sync().run())

        val m = match("f1")!!
        assertEquals(MatchState.MATCHED, m.state)
        assertEquals(TmdbKind.MOVIE, m.tmdbKind)
        assertEquals(603L, m.tmdbId)
        assertEquals("4K", m.badge)
        assertEquals("EN | The Matrix (1999) 4K", m.sourceName)
        val t = db.tmdbDao().title(TmdbKind.MOVIE, 603).first()!!
        assertEquals("Keanu Reeves, Laurence Fishburne, Carrie-Anne Moss", t.cast)
        assertEquals(136, t.runtimeMinutes)
        assertEquals("en-US", t.language)
        assertEquals(listOf("Action", "Science Fiction"), db.tmdbDao().genresOf(TmdbKind.MOVIE, 603).first())
    }

    @Test fun showFetchesEverySeason() = runTest {
        tmdb.tvSearch["Breaking Bad"] = listOf(hit(1396, "Breaking Bad"))
        tmdb.shows[1396L to "en-US"] = show(1396, "Breaking Bad")
        tmdb.seasons[1396L to 1] = listOf(EpisodeDetails(1, "Pilot", "Walt."), EpisodeDetails(2, "Cat's in the Bag", null))
        tmdb.seasons[1396L to 2] = listOf(EpisodeDetails(1, "Seven Thirty-Seven", null))
        addShow("g:BB", "Breaking Bad")
        assertEquals(SyncOutcome.Done, sync().run())

        assertEquals(1396L, match("g:BB", TitleKind.SHOW)!!.tmdbId)
        assertEquals(1, tmdb.count("season/1396/1|"))
        assertEquals(1, tmdb.count("season/1396/2|"))
        val eps = db.tmdbDao().episodes(1396).first()
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.episode })
        assertEquals("Pilot", eps.first().title)
        assertEquals(2, db.tmdbDao().title(TmdbKind.TV, 1396).first()!!.seasonCount)
    }

    @Test fun numericShowIdWithDifferentNameFallsBackToSearch() = runTest {
        tmdb.shows[1234L to "en-US"] = show(1234, "Some Other Show")
        tmdb.tvSearch["Doctor Who"] = listOf(hit(57243, "Doctor Who"))
        tmdb.shows[57243L to "en-US"] = show(57243, "Doctor Who", seasons = listOf(1))
        tmdb.seasons[57243L to 1] = listOf(EpisodeDetails(1, "Rose", null))
        addShow("g:DW", "Doctor Who", tvgId = "1234")
        assertEquals(SyncOutcome.Done, sync().run())

        assertEquals(57243L, match("g:DW", TitleKind.SHOW)!!.tmdbId)
        assertEquals(0, tmdb.count("season/1234/"))
        assertNull(db.tmdbDao().title(TmdbKind.TV, 1234).first())
    }

    @Test fun numericShowIdWithSameNameIsTrusted() = runTest {
        tmdb.shows[1234L to "en-US"] = show(1234, "Doctor Who", seasons = listOf(1))
        tmdb.seasons[1234L to 1] = emptyList()
        addShow("g:DW", "UK: Doctor Who HD", tvgId = "1234")
        assertEquals(SyncOutcome.Done, sync().run())

        assertEquals(1234L, match("g:DW", TitleKind.SHOW)!!.tmdbId)
        assertEquals(0, tmdb.count("search/"))
    }

    @Test fun resumesWithoutRefetchingCommittedTitles() = runTest {
        (1L..30L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }
        // Stopped after one full batch and two more titles: the batch was written as it filled,
        // the two on the way out.
        tmdb.killAfterDetails = 27
        try {
            sync().run()
            fail("expected the run to be stopped")
        } catch (_: CancellationException) {
        }
        assertEquals(27, tmdb.count("movie/"))
        assertEquals(listOf(25, 27), writes)

        tmdb.killAfterDetails = null
        assertEquals(SyncOutcome.Done, sync().run())
        assertEquals(30, tmdb.count("movie/"))
        (1L..30L).forEach { assertEquals(it, match("f$it")!!.tmdbId) }
    }

    @Test fun batchesWritesInto25TitleTransactions() = runTest {
        (1L..60L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }

        assertEquals(SyncOutcome.Done, sync().run())

        assertEquals(listOf(25, 50, 60), writes)
        assertEquals(60, tmdb.count("movie/"))
    }

    @Test fun batchIsWrittenAfterTenSeconds() = runTest {
        (1L..14L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }
        tmdb.onDetail = { now = now.plusSeconds(1) }

        assertEquals(SyncOutcome.Done, sync().run())

        // The first title waits from 1 s; the eleventh, at 11 s, sends the batch.
        assertEquals(listOf(11, 14), writes)
    }

    @Test fun lookedUpTitlesAreWrittenWhenTheRunStops() = runTest {
        (1L..3L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }
        tmdb.badKeyFor = "search/movie/Film 3"
        assertEquals(SyncOutcome.BadKey, sync().run())
        assertEquals(listOf(2), writes)
        assertEquals(2L, match("f2")!!.tmdbId)

        tmdb.badKeyFor = null
        knownFilm(4, "Film 4"); addFilm("f4", "Film 4")
        tmdb.onDetail = { recording = true }
        assertEquals(SyncOutcome.Paused, sync().run())
        assertEquals(3L, match("f3")!!.tmdbId)
        assertNull(match("f4"))
    }

    @Test fun matchedRefetchedAfter30Days() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "The Matrix")
        sync().run()
        assertEquals(1, tmdb.count("movie/"))

        now = days(29)
        sync().run()
        assertEquals(1, tmdb.count("movie/"))
        assertEquals("matched titles are not searched again", 1, tmdb.count("search/"))

        now = days(31)
        sync().run()
        assertEquals(2, tmdb.count("movie/"))
        assertEquals(1, tmdb.count("search/"))
        assertEquals(days(31), db.tmdbDao().title(TmdbKind.MOVIE, 603).first()!!.fetchedAt)
        assertEquals("genres are cached", 1, tmdb.count("genres/MOVIE"))
    }

    @Test fun languageChangeRefetchesDetailsAndGenres() = runTest {
        knownFilm(603, "The Matrix")
        tmdb.movies[603L to "de-DE"] = movie(603, "Matrix")
        addFilm("f1", "The Matrix")
        sync().run()

        lang = "de-DE"
        now = days(1)
        sync().run()
        assertEquals(1, tmdb.count("movie/603|de-DE"))
        assertEquals(1, tmdb.count("genres/MOVIE|de-DE"))
        assertEquals("Matrix", db.tmdbDao().title(TmdbKind.MOVIE, 603).first()!!.title)
    }

    @Test fun notFoundRetriedAfter7DaysOrWhenNameChanges() = runTest {
        addFilm("f1", "Obscure Film")
        sync().run()
        assertEquals(MatchState.NOT_FOUND, match("f1")!!.state)
        assertEquals(1, tmdb.count("search/"))

        now = days(6)
        sync().run()
        assertEquals(1, tmdb.count("search/"))

        now = days(8)
        sync().run()
        assertEquals(2, tmdb.count("search/"))

        now = days(9)
        knownFilm(42, "Obscure Film Two")
        addFilm("f1", "Obscure Film Two")
        sync().run()
        assertEquals(3, tmdb.count("search/"))
        assertEquals(42L, match("f1")!!.tmdbId)
    }

    @Test fun rejectionSurvivesKeyChangeViaFingerprint() = runTest {
        tmdb.tvSearch["Lost"] = listOf(hit(4607, "Lost"))
        tmdb.shows[4607L to "en-US"] = show(4607, "Lost", seasons = emptyList())
        addShow("g:Lost", "Lost")
        sync().run()
        db.tmdbDao().reject(p, TitleKind.SHOW, "g:Lost", now)
        val before = tmdb.calls.size

        // A refresh rebuilds the show under a new key.
        db.vodWriteDao().deleteShows(p)
        db.vodWriteDao().deleteEpisodes(p, listOf("g:Lost-e1", "g:Lost-e2"))
        addShow("n:Series␟Lost", "Lost")
        now = days(1)
        sync().run()

        assertEquals(before, tmdb.calls.size)
        val moved = match("n:Series␟Lost", TitleKind.SHOW)!!
        assertEquals(MatchState.REJECTED, moved.state)
        assertNull(moved.tmdbId)
    }

    @Test fun rejectedNotRetriedUntilNameChanges() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "The Matrix")
        sync().run()
        db.tmdbDao().reject(p, TitleKind.FILM, "f1", now)
        val before = tmdb.calls.size

        now = days(40)
        sync().run()
        assertEquals(before, tmdb.calls.size)
        assertEquals(MatchState.REJECTED, match("f1")!!.state)

        knownFilm(604, "The Matrix Reloaded")
        addFilm("f1", "The Matrix Reloaded")
        now = days(41)
        sync().run()
        assertEquals(MatchState.MATCHED, match("f1")!!.state)
        assertEquals(604L, match("f1")!!.tmdbId)
    }

    @Test fun rejectionDuringARunningRefetchStaysRejected() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "The Matrix")
        sync().run()
        assertEquals(MatchState.MATCHED, match("f1")!!.state)

        // Due again; the viewer rejects it while its refetch is queued, before the batch is written.
        now = days(31)
        tmdb.onDetail = { db.tmdbDao().reject(p, TitleKind.FILM, "f1", now) }
        sync().run()

        assertEquals(2, tmdb.count("movie/"))
        val m = match("f1")!!
        assertEquals(MatchState.REJECTED, m.state)
        assertNull(m.tmdbId)
    }

    @Test fun sameTmdbIdInTwoPlaylistsFetchedOnce() = runTest {
        knownFilm(603, "The Matrix")
        val other = db.addPlaylist("Q")
        addFilm("f1", "EN | The Matrix")
        addFilm("g1", "UK: The Matrix HD", pl = other)
        sync().run()

        assertEquals(1, tmdb.count("movie/"))
        assertEquals(603L, match("f1")!!.tmdbId)
        assertEquals(603L, match("g1", pl = other)!!.tmdbId)
        assertEquals("HD", match("g1", pl = other)!!.badge)
    }

    @Test fun pausesWhileRecording() = runTest {
        knownFilm(603, "The Matrix")
        addFilm("f1", "The Matrix")
        recording = true
        assertEquals(SyncOutcome.Paused, sync().run())
        assertTrue(tmdb.calls.isEmpty())

        recording = false
        assertEquals(SyncOutcome.Done, sync().run())
        assertEquals(603L, match("f1")!!.tmdbId)
    }

    @Test fun badKeyStopsRun() = runTest {
        knownFilm(1, "Film 1")
        knownFilm(2, "Film 2")
        addFilm("f1", "Film 1")
        addFilm("f2", "Film 2")
        sync().run()
        tmdb.calls.clear()

        // Language change makes both due; the first request is refused.
        lang = "fr-FR"
        tmdb.badKey = true
        assertEquals(SyncOutcome.BadKey, sync().run())
        assertEquals(1, tmdb.calls.size)
        assertEquals(1, badKeyCalls)
    }

    @Test fun failedRequestRetriesLater() = runTest {
        val failing = object : TmdbDetailsSource by tmdb {
            override suspend fun searchMovie(query: String, year: Int?, language: String) = TmdbResult.Failed(10)
        }
        addFilm("f1", "The Matrix")
        val outcome = TmdbSync(db, failing, TmdbMatcher(failing), { true }, { false }, { "en-US" }, { now }, {}).run()
        assertEquals(SyncOutcome.RetryLater(10), outcome)
        assertNull(match("f1"))
    }

    @Test fun tooManyRequestsStopsTheRunAtOnce() = runTest {
        var searches = 0
        val limited = object : TmdbDetailsSource by tmdb {
            override suspend fun searchMovie(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>> {
                searches++
                return TmdbResult.Failed(10)
            }
        }
        addFilm("f1", "Film 1")
        addFilm("f2", "Film 2")
        val outcome = TmdbSync(db, limited, TmdbMatcher(limited), { true }, { false }, { "en-US" }, { now }, {}).run()
        assertEquals(SyncOutcome.RetryLater(10), outcome)
        assertEquals(1, searches)
    }

    @Test fun aTitleThatAlwaysFailsDoesNotBlockLaterTitles() = runTest {
        (1L..3L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }
        tmdb.failFor += "search/movie/Film 1|"

        assertEquals(SyncOutcome.RetryLater(null), sync().run())
        assertNull(match("f1"))
        assertEquals(2L, match("f2")!!.tmdbId)
        assertEquals(3L, match("f3")!!.tmdbId)

        // The failed title is tried again next run; the others are not.
        assertEquals(SyncOutcome.RetryLater(null), sync().run())
        assertEquals(2, tmdb.count("search/movie/Film 1|"))
        assertEquals(1, tmdb.count("search/movie/Film 2|"))

        tmdb.failFor.clear()
        assertEquals(SyncOutcome.Done, sync().run())
        assertEquals(1L, match("f1")!!.tmdbId)
    }

    @Test fun threeFailuresInARowStopTheRun() = runTest {
        (1L..9L).forEach { knownFilm(it, "Film $it"); addFilm("f$it", "Film $it") }
        // Two failures, a success, then three failures: the run stops after the sixth.
        listOf(1, 2, 4, 5, 6).forEach { tmdb.failFor += "search/movie/Film $it|" }

        assertEquals(SyncOutcome.RetryLater(null), sync().run())
        assertEquals(3L, match("f3")!!.tmdbId)
        assertEquals(6, tmdb.count("search/movie/"))
        assertNull(match("f7"))
    }

    @Test fun uniformFailuresStopTheRunWithoutMarkingNotFound() = runTest {
        // A proxy answering every request with a 403 or an HTML page.
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            private var n = 0
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (n++ % 2 == 0) okhttp3.mockwebserver.MockResponse().setResponseCode(403)
                else okhttp3.mockwebserver.MockResponse().setBody("<html>Sign in</html>")
        }
        server.start()
        try {
            val client = TmdbClient(
                okhttp3.OkHttpClient(), server.url("/3/"), key = { "0123456789abcdef0123456789abcdef" },
            )
            // Genres are already cached, so the run goes straight to the titles.
            TmdbKind.entries.forEach { db.tmdbDao().replaceGenres(it, emptyList()) }
            TmdbKind.entries.forEach {
                db.tmdbDao().upsertGenres(listOf(io.github.loopholetv.data.db.TmdbGenreEntity(it, 1, "Drama", "en-US")))
            }
            (1L..6L).forEach { addFilm("f$it", "Film $it") }
            val outcome = TmdbSync(db, client, TmdbMatcher(client), { true }, { false }, { "en-US" }, { now }, {}).run()

            assertEquals(SyncOutcome.RetryLater(null), outcome)
            (1L..6L).forEach { assertNull(match("f$it")) }
            assertEquals(0, db.rowCount("vod_matches"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun emptyOverviewFallsBackToEnglish() = runTest {
        lang = "fr-FR"
        tmdb.movieSearch["Matrix"] = listOf(hit(603, "Matrix"))
        tmdb.movies[603L to "fr-FR"] = movie(603, "Matrix", overview = null)
        tmdb.movies[603L to "en-US"] = movie(603, "The Matrix", overview = "A hacker learns.")
        addFilm("f1", "FR | Matrix")
        sync().run()

        val t = db.tmdbDao().title(TmdbKind.MOVIE, 603).first()!!
        assertEquals("Matrix", t.title)
        assertEquals("A hacker learns.", t.overview)
        assertEquals("fr-FR", t.language)
    }

    @Test fun aRunSoonAfterTheLastLeavesSeenTimesAlone() = runTest {
        knownFilm(1, "Film 1")
        addFilm("f1", "Film 1")
        sync().run()
        writes.clear()

        // Nothing changed, so no row is rewritten (and the browse screens have nothing to re-read).
        now = t0.plus(Duration.ofHours(6))
        assertEquals(SyncOutcome.Done, sync().run())
        assertEquals(t0, match("f1")!!.lastSeenAt)
        assertTrue(writes.isEmpty())

        now = days(2)
        sync().run()
        assertEquals(days(2), match("f1")!!.lastSeenAt)
    }

    @Test fun prunesTitlesUnseenFor30Days() = runTest {
        knownFilm(1, "Film 1")
        knownFilm(2, "Film 2")
        addFilm("f1", "Film 1")
        addFilm("f2", "Film 2")
        sync().run()

        db.vodWriteDao().deleteFilms(p, listOf("f2"))
        now = days(20)
        sync().run()
        now = days(31)
        sync().run()

        assertEquals(1L, match("f1")!!.tmdbId)
        assertNull(match("f2"))
        assertNull(db.tmdbDao().title(TmdbKind.MOVIE, 2).first())
        assertEquals(1, db.rowCount("vod_matches"))
    }

    @Test fun badKeyOnDetailsStopsRun() = runTest {
        knownFilm(1, "Film 1")
        knownFilm(2, "Film 2")
        addFilm("f1", "Film 1")
        addFilm("f2", "Film 2")
        tmdb.badKeyFor = "movie/"
        assertEquals(SyncOutcome.BadKey, sync().run())
        assertEquals(1, badKeyCalls)
        assertEquals(1, tmdb.count("movie/"))
        assertEquals("the second title is not tried", 1, tmdb.count("search/"))
        assertNull(match("f1"))
    }

    @Test fun recordingStartedMidShowPausesBeforeTheNextSeason() = runTest {
        tmdb.tvSearch["Breaking Bad"] = listOf(hit(1396, "Breaking Bad"))
        tmdb.shows[1396L to "en-US"] = show(1396, "Breaking Bad")
        tmdb.seasons[1396L to 1] = emptyList()
        tmdb.seasons[1396L to 2] = emptyList()
        tmdb.beforeSeason = { recording = true }
        addShow("g:BB", "Breaking Bad")
        assertEquals(SyncOutcome.Paused, sync().run())
        assertEquals(1, tmdb.count("season/"))
        assertNull(match("g:BB", TitleKind.SHOW))
    }

    @Test fun titlesSharingATvgIdWithDifferentNamesAreLookedUpSeparately() = runTest {
        // Some providers reuse one tvg-id across unrelated entries.
        knownFilm(1, "Alpha")
        knownFilm(2, "Beta")
        addFilm("f1", "Alpha", tvgId = "shared")
        addFilm("f2", "Beta", tvgId = "shared")
        sync().run()
        assertEquals(1L, match("f1")!!.tmdbId)
        assertEquals(2L, match("f2")!!.tmdbId)
        assertEquals(2, tmdb.count("search/"))
    }

    @Test fun titlesWithTheSameCleanNameInOnePlaylistAreLookedUpSeparately() = runTest {
        addFilm("f1", "Hamlet")
        addFilm("f2", "EN | Hamlet")
        sync().run()
        assertEquals(2, tmdb.count("search/"))
    }
}
