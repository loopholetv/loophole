package io.github.loopholetv.data.xtream

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class XtreamVodClientTest {
    private lateinit var server: MockWebServer
    private val client = XtreamClient(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/xtream/$name")!!.readBytes().decodeToString()

    private fun creds() = XtreamCredentials("http://${server.hostName}:${server.port}/", "user1", "pass1")

    private fun enqueue(body: String) { server.enqueue(MockResponse().setBody(body)) }

    private suspend fun films(): List<XtreamFilm> {
        val out = mutableListOf<XtreamFilm>()
        client.vodStreams(creds()) { out += it }
        return out
    }

    private suspend fun shows(): List<XtreamShow> {
        val out = mutableListOf<XtreamShow>()
        client.series(creds()) { out += it }
        return out
    }

    @Test fun vodCategoriesParsed() = runTest {
        enqueue(res("vod_categories.json"))
        assertEquals(listOf(XtreamCategory("10", "Action"), XtreamCategory("11", "Drama")), client.vodCategories(creds()))
        assertEquals(
            "/player_api.php?username=user1&password=pass1&action=get_vod_categories",
            server.takeRequest().path,
        )
    }

    @Test fun seriesCategoriesParsed() = runTest {
        enqueue(res("series_categories.json"))
        assertEquals(listOf(XtreamCategory("20", "Crime"), XtreamCategory("21", "Comedy")), client.seriesCategories(creds()))
        assertEquals(
            "/player_api.php?username=user1&password=pass1&action=get_series_categories",
            server.takeRequest().path,
        )
    }

    @Test fun vodStreamsParsesFilms() = runTest {
        enqueue(res("vod_streams.json"))
        val list = films()
        assertEquals(2, list.size)
        assertEquals(
            XtreamFilm(12, "The Matrix", "http://img/matrix.jpg", "10", "mkv", 603, 1999, 7.9),
            list[0],
        )
        assertEquals(XtreamFilm(13, "Plain", null, "11", "mp4", null, null, null), list[1])
        assertEquals(
            "/player_api.php?username=user1&password=pass1&action=get_vod_streams",
            server.takeRequest().path,
        )
    }

    @Test fun vodStreamsLenientShapes() = runTest {
        enqueue(res("vod_streams_lenient.json"))
        val list = films()
        assertEquals(2, list.size)
        assertEquals(XtreamFilm(21, "Stringly", null, "22", null, null, 2010, 6.5), list[0])
        assertEquals(XtreamFilm(24, "Titled", null, null, null, 77, 2001, null), list[1])
    }

    @Test fun vodStreamsEmptyShapesGiveNothing() = runTest {
        for (body in listOf("[]", "null", "{}")) {
            enqueue(body)
            assertEquals(body, emptyList<XtreamFilm>(), films())
        }
    }

    @Test fun authStyleObjectIsAFailedListNotAnEmptyOne() = runTest {
        val refusal = """{"user_info":{"auth":0}}"""
        enqueue(refusal)
        try { films(); fail("films") } catch (_: XtreamProtocolException) {}
        enqueue(refusal)
        try { shows(); fail("shows") } catch (_: XtreamProtocolException) {}
        enqueue(refusal)
        try { client.vodCategories(creds()); fail("categories") } catch (_: XtreamProtocolException) {}
    }

    @Test fun seriesParsesShowsWithBackdropArray() = runTest {
        enqueue(res("series.json"))
        val list = shows()
        assertEquals(2, list.size)
        assertEquals(
            XtreamShow(
                7, "Breaking Bad", "http://img/bb.jpg", "20", "A teacher.", "Bryan Cranston", "Crime",
                9.5, 2008, "http://img/bd1.jpg", 1396, "1700000123",
            ),
            list[0],
        )
        assertEquals("http://img/one.jpg", list[1].backdrop)
        assertEquals(8L, list[1].seriesId)
        assertNull(list[1].plot)
        assertNull(list[1].lastModified)
        assertEquals(
            "/player_api.php?username=user1&password=pass1&action=get_series",
            server.takeRequest().path,
        )
    }

    @Test fun seriesInfoEpisodesKeyedBySeason() = runTest {
        enqueue(res("series_info_by_season.json"))
        assertEquals(
            listOf(
                XtreamEpisode(101, 1, 1, "Pilot", "mkv", "http://img/e1.jpg"),
                XtreamEpisode(102, 1, 2, "Cat's in the Bag", "mp4", null),
                XtreamEpisode(201, 2, 1, "Seven Thirty-Seven", "avi", "http://img/e3.jpg"),
            ),
            client.seriesInfo(creds(), 7),
        )
    }

    @Test fun seriesInfoSeasonFallsBackToKey() = runTest {
        enqueue("""{"episodes":{"3":{"x":{"id":"9","episode_num":2,"title":"T"}}}}""")
        assertEquals(listOf(XtreamEpisode(9, 3, 2, "T", null, null)), client.seriesInfo(creds(), 7))
    }

    @Test fun seriesInfoFlatArray() = runTest {
        enqueue(res("series_info_flat.json"))
        assertEquals(
            listOf(
                XtreamEpisode(301, 1, 1, "One", "mp4", null),
                XtreamEpisode(302, 2, 3, null, null, null),
            ),
            client.seriesInfo(creds(), 7),
        )
    }

    @Test fun seriesInfoEmptyIsEmptyList() = runTest {
        for (body in listOf("""{"episodes":[]}""", """{"episodes":{}}""", "{}")) {
            enqueue(body)
            assertEquals(body, emptyList<XtreamEpisode>(), client.seriesInfo(creds(), 7))
        }
    }

    @Test fun seriesInfoRefusalOrOddReplyIsAFailure() = runTest {
        // A login answer instead of the show, or a reply that isn't an object, must never read as "no episodes".
        for (body in listOf("""{"user_info":{"auth":0}}""", "[]", "null", "\"x\"")) {
            enqueue(body)
            try { client.seriesInfo(creds(), 7); fail(body) } catch (_: XtreamProtocolException) {}
        }
    }

    @Test fun seriesInfoWithAnyUserInfoIsAFailure() = runTest {
        // A login object never belongs in a show's reply, whatever comes with it.
        for (body in listOf(
            """{"user_info":{"auth":0},"episodes":null}""",
            """{"user_info":{"auth":0},"episodes":[]}""",
            """{"episodes":{},"user_info":{"auth":1}}""",
        )) {
            enqueue(body)
            try { client.seriesInfo(creds(), 7); fail(body) } catch (_: XtreamProtocolException) {}
        }
    }

    @Test fun vodInfoRefusalOrOddReplyIsAFailure() = runTest {
        for (body in listOf("""{"user_info":{"auth":0}}""", "[]", "null", "\"x\"")) {
            enqueue(body)
            try { client.vodInfo(creds(), 1); fail(body) } catch (_: XtreamProtocolException) {}
        }
    }

    @Test fun vodInfoWithAnyUserInfoIsAFailure() = runTest {
        for (body in listOf(
            """{"user_info":{"auth":0},"info":[]}""",
            """{"user_info":{"auth":0},"info":null}""",
            """{"info":{},"user_info":{"auth":1}}""",
        )) {
            enqueue(body)
            try { client.vodInfo(creds(), 1); fail(body) } catch (_: XtreamProtocolException) {}
        }
    }

    @Test fun vodInfoGenuinelyEmptyIsAllNull() = runTest {
        for (body in listOf("""{"info":[]}""", """{"info":{}}""", "{}")) {
            enqueue(body)
            assertEquals(body, XtreamFilmInfo(null, null, null, null, null, null, null), client.vodInfo(creds(), 1))
        }
    }

    @Test fun vodInfoReadsDetails() = runTest {
        enqueue(res("vod_info.json"))
        assertEquals(
            XtreamFilmInfo("A hacker.", "Keanu Reeves", "Action", 7.9, 1999, 104, "http://img/mbd.jpg"),
            client.vodInfo(creds(), 12),
        )
    }

    @Test fun vodInfoRuntimeFromSecsOrClock() = runTest {
        enqueue("""{"info":{"duration_secs":6240}}""")
        assertEquals(104, client.vodInfo(creds(), 1).runtimeMinutes)
        enqueue("""{"info":{"duration":"01:45:00"}}""")
        assertEquals(105, client.vodInfo(creds(), 1).runtimeMinutes)
        enqueue("""{"info":{"episode_run_time":"92"}}""")
        assertEquals(92, client.vodInfo(creds(), 1).runtimeMinutes)
        enqueue("""{"movie_data":{"stream_id":1}}""")
        assertEquals(XtreamFilmInfo(null, null, null, null, null, null, null), client.vodInfo(creds(), 1))
    }

    @Test fun vodInfoFallbackFieldNames() = runTest {
        enqueue("""{"info":{"description":"D","actors":"A","release_date":"2005-02-02","backdrop_path":"http://b"}}""")
        assertEquals(XtreamFilmInfo("D", "A", null, null, 2005, null, "http://b"), client.vodInfo(creds(), 1))
    }

    @Test fun vodRequestsCarryActionIdAndUserAgent() = runTest {
        enqueue("{}")
        client.seriesInfo(creds(), 7, userAgent = "UA/1")
        val info = server.takeRequest()
        assertEquals("/player_api.php?username=user1&password=pass1&action=get_series_info&series_id=7", info.path)
        assertEquals("UA/1", info.getHeader("User-Agent"))

        enqueue("{}")
        client.vodInfo(creds(), 12, userAgent = "UA/1")
        val vod = server.takeRequest()
        assertEquals("/player_api.php?username=user1&password=pass1&action=get_vod_info&vod_id=12", vod.path)
        assertEquals("UA/1", vod.getHeader("User-Agent"))
    }

    @Test fun vodUrlBuildsMoviePath() {
        val c = creds()
        val base = "http://${server.hostName}:${server.port}"
        assertEquals("$base/movie/user1/pass1/12.mkv", xtreamVodUrl(c, XtreamVodKind.MOVIE, 12, "mkv"))
        assertEquals("$base/series/user1/pass1/9.mp4", xtreamVodUrl(c, XtreamVodKind.SERIES, 9, null))
    }

    @Test fun vodUrlSanitisesContainer() {
        assertEquals("mkv", xtreamExtension(".MKV"))
        assertEquals("mkv", xtreamExtension(" mkv "))
        assertEquals("mp4", xtreamExtension("mp4?x=1"))
        assertEquals("mp4", xtreamExtension("../x"))
        assertEquals("mp4", xtreamExtension(""))
        assertEquals("mp4", xtreamExtension(null))
        assertEquals("mp4", xtreamExtension("verylongext1"))
    }

    @Test fun keysRoundTrip() {
        assertEquals("xm:5", xtreamFilmKey(5))
        assertEquals("xs:6", xtreamShowKey(6))
        assertEquals("xe:7", xtreamEpisodeKey(7))
        assertEquals(5L, xtreamIdOf("xm:5", XTREAM_FILM_PREFIX))
        assertNull(xtreamIdOf("xs:5", XTREAM_FILM_PREFIX))
        assertNull(xtreamIdOf("xm:0", XTREAM_FILM_PREFIX))
        assertNull(xtreamIdOf("xm:-3", XTREAM_FILM_PREFIX))
        assertNull(xtreamIdOf("xm:abc", XTREAM_FILM_PREFIX))
        assertNull(xtreamIdOf("xm:", XTREAM_FILM_PREFIX))
    }

    @Test fun vodFailureTextHasNoPassword() = runTest {
        val c = creds()
        server.shutdown()
        try { client.vodStreams(c) { }; fail() } catch (e: XtreamNetworkException) {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message.orEmpty()
                assertFalse(m, m.contains("pass1"))
                t = t.cause
            }
        }
    }
}
