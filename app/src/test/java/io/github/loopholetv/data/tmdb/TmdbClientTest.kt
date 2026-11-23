package io.github.loopholetv.data.tmdb

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TmdbClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val v3 = "0123456789abcdef0123456789abcdef"
    private val v4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl"

    private fun client(key: String? = v3, nanoClock: () -> Long = System::nanoTime) = TmdbClient(
        http = OkHttpClient(),
        baseUrl = server.url("/3/"),
        key = { key },
        nanoClock = nanoClock,
    )

    private fun enqueue(body: String = "{}", code: Int = 200, vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        })
    }

    private val movieJson = """
        {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30",
         "overview":"A hacker learns.","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
         "vote_average":8.2,"vote_count":25000,"runtime":136,
         "genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}],
         "credits":{"cast":[{"name":"Keanu Reeves"},{"name":"Laurence Fishburne"},
           {"name":"Carrie-Anne Moss"},{"name":"Hugo Weaving"}]}}
    """.trimIndent()

    @Test fun keyTypeTrimsWhitespace() {
        assertEquals(TmdbKeyType.V3, tmdbKeyType("  0123456789abcdef0123456789abcdef\n"))
        assertEquals(TmdbKeyType.V4, tmdbKeyType(v4))
        assertNull(tmdbKeyType("nope"))
        assertNull(tmdbKeyType("0123456789abcdef0123456789abcdeg"))
        assertNull(tmdbKeyType("eyJonly.onedot"))
    }

    @Test fun v3SendsQueryKey() = runTest {
        enqueue()
        assertTrue(client().configuration() is TmdbResult.Ok)
        val req = server.takeRequest()
        assertTrue(req.path!!, req.path!!.startsWith("/3/configuration"))
        assertEquals(v3, req.requestUrl!!.queryParameter("api_key"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun v4SendsBearer() = runTest {
        enqueue()
        assertTrue(client(key = " $v4 ").configuration() is TmdbResult.Ok)
        val req = server.takeRequest()
        assertEquals("Bearer $v4", req.getHeader("Authorization"))
        assertNull(req.requestUrl!!.queryParameter("api_key"))
    }

    @Test fun blankKeyIsBadKeyWithoutRequest() = runTest {
        assertEquals(TmdbResult.BadKey, client(key = null).configuration())
        assertEquals(TmdbResult.BadKey, client(key = "  ").configuration())
        assertEquals(0, server.requestCount)
    }

    @Test fun mapsStatusCodes() = runTest {
        val c = client()
        enqueue(code = 401)
        assertEquals(TmdbResult.BadKey, c.movie(1, "en-US"))
        enqueue(code = 404)
        assertEquals(TmdbResult.NotFound, c.movie(1, "en-US"))
        enqueue(code = 429, headers = arrayOf("Retry-After" to "3"))
        assertEquals(TmdbResult.Failed(3), c.movie(1, "en-US"))
        enqueue(code = 500)
        assertEquals(TmdbResult.Failed(null), c.movie(1, "en-US"))
    }

    @Test fun parsesMovieDetailsWithCast() = runTest {
        enqueue(movieJson)
        val r = client().movie(603, "en-US") as TmdbResult.Ok
        val m = r.value
        assertEquals("The Matrix", m.title)
        assertEquals(1999, m.year)
        assertEquals(136, m.runtime)
        assertEquals(listOf(28, 878), m.genreIds)
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss"), m.cast)
        assertEquals("/p.jpg", m.posterPath)
        assertEquals("/b.jpg", m.backdropPath)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/movie/603", url.encodedPath)
        assertEquals("credits", url.queryParameter("append_to_response"))
        assertEquals("en-US", url.queryParameter("language"))
    }

    @Test fun parsesTvDetails() = runTest {
        enqueue(
            """{"id":1,"name":"Show","original_name":"Orig","first_air_date":"2008-01-20",
               "number_of_seasons":5,"seasons":[{"season_number":0},{"season_number":1}],
               "genres":[{"id":18}],"credits":{"cast":[]}}""",
        )
        val t = (client().tv(1, "en-US") as TmdbResult.Ok).value
        assertEquals("Show", t.title)
        assertEquals("Orig", t.originalTitle)
        assertEquals(2008, t.year)
        assertEquals(5, t.numberOfSeasons)
        assertEquals(listOf(0, 1), t.seasonNumbers)
    }

    @Test fun parsesSeason() = runTest {
        enqueue(
            """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"Start"},
               {"episode_number":2,"name":"Two","overview":null}]}""",
        )
        val eps = (client().season(7, 2, "en-US") as TmdbResult.Ok).value
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals("Pilot", eps[0].name)
        assertNull(eps[1].overview)
        assertEquals("/3/tv/7/season/2", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun parsesFindSearchAndGenres() = runTest {
        val c = client()
        enqueue("""{"movie_results":[{"id":5}],"tv_results":[{"id":6},{"id":7}]}""")
        val f = (c.findImdb("tt0133093", "en-US") as TmdbResult.Ok).value
        assertEquals(listOf(5L), f.movieIds)
        assertEquals(listOf(6L, 7L), f.tvIds)
        assertEquals("imdb_id", server.takeRequest().requestUrl!!.queryParameter("external_source"))

        enqueue("""{"results":[{"id":9,"title":"A","original_title":"B","release_date":"2001-05-05","vote_count":3}]}""")
        val hit = (c.searchMovie("A", 2001, "en-US") as TmdbResult.Ok).value.single()
        assertEquals(SearchHit(9, "A", "B", 2001, 3), hit)
        val su = server.takeRequest().requestUrl!!
        assertEquals("2001", su.queryParameter("year"))
        assertEquals("A", su.queryParameter("query"))

        enqueue("""{"results":[{"id":9,"name":"S","first_air_date":"2010-01-01"}]}""")
        assertEquals(2010, (c.searchTv("S", 2010, "en-US") as TmdbResult.Ok).value.single().year)
        assertEquals("2010", server.takeRequest().requestUrl!!.queryParameter("first_air_date_year"))

        enqueue("""{"genres":[{"id":28,"name":"Action"}]}""")
        assertEquals(listOf(Genre(28, "Action")), (c.genres(TmdbKind.MOVIE, "en-US") as TmdbResult.Ok).value)
        assertEquals("/3/genre/movie/list", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun malformedBodyIsFailed() = runTest {
        // Not JSON at all (e.g. a network login page): treated as a network problem.
        enqueue("not json")
        assertEquals(TmdbResult.Failed(null), client().movie(1, "en-US"))
    }

    @Test fun malformed200BodyIsNotFound() = runTest {
        val c = client()
        enqueue("""{"id":603,"original_title":"No title"}""")
        assertEquals(TmdbResult.NotFound, c.movie(603, "en-US"))
        enqueue("""{"id":1,"name":"  "}""")
        assertEquals(TmdbResult.NotFound, c.tv(1, "en-US"))
    }

    @Test fun html200BodyIsFailedNotNotFound() = runTest {
        val c = client()
        // A network login page or proxy answers 200 with HTML: nothing is learnt about the title.
        enqueue("<!DOCTYPE html><html><body>Sign in to the Wi-Fi</body></html>")
        assertEquals(TmdbResult.Failed(null), c.movie(1, "en-US"))
        enqueue("""{"id":1,"title":""")
        assertEquals(TmdbResult.Failed(null), c.movie(1, "en-US"))
        // Valid JSON that is not an object is no answer about the title either.
        enqueue("[]")
        assertEquals(TmdbResult.Failed(null), c.movie(1, "en-US"))
    }

    @Test fun http422IsNotFound() = runTest {
        val c = client()
        for (code in listOf(404, 422)) {
            enqueue(code = code)
            assertEquals("HTTP $code", TmdbResult.NotFound, c.movie(1, "en-US"))
        }
    }

    @Test fun http403IsFailed() = runTest {
        val c = client()
        // Any other refusal may come from a proxy or outage, not TMDB's verdict on the title.
        for (code in listOf(400, 403, 405, 408, 410, 451, 500, 503)) {
            enqueue(code = code)
            assertEquals("HTTP $code", TmdbResult.Failed(null), c.movie(1, "en-US"))
        }
    }

    private fun TestScope.fakeClock(): () -> Long = { currentTime * 1_000_000L }

    @Test fun pacesRequests() = runTest {
        val c = client(nanoClock = fakeClock())
        repeat(3) { enqueue() }
        val start = currentTime
        repeat(3) { c.configuration() }
        assertTrue("virtual time ${currentTime - start}", currentTime - start >= 100)
    }

    @Test fun failureCarriesNoKeyText() = runTest {
        val dead = TmdbClient(OkHttpClient(), "http://127.0.0.1:1/3/".toHttpUrl(), { v3 })
        val r1 = dead.movie(1, "en-US")
        assertEquals(TmdbResult.Failed(null), r1)
        assertFalse(r1.toString().contains(v3))
        val throwing = TmdbClient(OkHttpClient(), server.url("/3/"), { error("boom $v3") })
        val r2 = throwing.movie(1, "en-US")
        assertEquals(TmdbResult.Failed(null), r2)
        assertFalse(r2.toString().contains(v3))
        assertFalse(dead.toString().contains(v3))
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedKeysNeverThrow() = runTest {
        for (bad in listOf("eyJab.cd ef.gh", "eyJab.cd\u0007.gh", "eyJa\nb.c.d")) {
            val r = client(key = bad).movie(1, "en-US")
            assertTrue(r is TmdbResult.BadKey || r is TmdbResult.Failed)
            assertFalse(r.toString().contains(bad))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun retryAfterDefaultsAndClamps() = runTest {
        val c = client()
        enqueue(code = 429)
        assertEquals(TmdbResult.Failed(10), c.movie(1, "en-US"))
        enqueue(code = 429, headers = arrayOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(TmdbResult.Failed(10), c.movie(1, "en-US"))
        enqueue(code = 429, headers = arrayOf("Retry-After" to "99999"))
        assertEquals(TmdbResult.Failed(3600), c.movie(1, "en-US"))
    }

    @Test fun languageTagFallbacks() {
        assertEquals("en-US", tmdbLanguage(Locale("en")))
        assertEquals("de-AT", tmdbLanguage(Locale("de", "AT")))
        assertEquals("zh-TW", tmdbLanguage(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("fr-FR", tmdbLanguage(Locale("fr")))
    }
}
