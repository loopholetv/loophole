package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.ShowSource
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class XtreamVodImportTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var creds: XtreamCredentials
    private var p = 0L
    private var clock = Instant.ofEpochMilli(1_000_000)
    private val bodies = ConcurrentHashMap<String, () -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val http = OkHttpClient()

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val action = request.requestUrl?.queryParameter("action")
                return bodies[action]?.invoke() ?: MockResponse().setBody("[]")
            }
        }
        server.start()
        val address = server.url("/").toString().trimEnd('/')
        creds = XtreamCredentials(address, "u", "p")
        p = db.addXtreamPlaylist(server = address)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun answer(action: String, body: String) {
        bodies[action] = { MockResponse().setBody(body) }
    }

    private fun answer(action: String, response: () -> MockResponse) {
        bodies[action] = response
    }

    private fun films(vararg json: String) = answer("get_vod_streams", json.joinToString(",", "[", "]"))
    private fun shows(vararg json: String) = answer("get_series", json.joinToString(",", "[", "]"))

    private fun film(id: Long, name: String = "Film $id", cat: String? = "1", extra: String = "") =
        """{"stream_id":"$id","name":"$name"${cat?.let { ""","category_id":"$it"""" } ?: ""}$extra}"""

    private fun show(id: Long, name: String = "Show $id", cat: String? = "2", modified: String? = "100", extra: String = "") =
        """{"series_id":$id,"name":"$name"${cat?.let { ""","category_id":"$it"""" } ?: ""}""" +
            """${modified?.let { ""","last_modified":"$it"""" } ?: ""}$extra}"""

    private suspend fun run(live: Int = 1, userAgent: String? = null): XtreamVodResult =
        XtreamVodImport(XtreamClient(http), db, now = { clock }).run(p, creds, userAgent, live)

    private suspend fun filmRow(key: String): FilmEntity? = db.vodBrowseDao().filmRow(p, key)
    private suspend fun showRow(key: String): ShowEntity? = db.vodBrowseDao().show(p, key).first()
    private suspend fun filmKeys() = db.xtreamVodDao().xtreamFilmKeys(p).toSet()
    private suspend fun showKeys() = db.xtreamVodDao().xtreamShowKeys(p).toSet()

    private fun episode(key: String, showKey: String) =
        EpisodeEntity(p, key, showKey, "G", key, 1, 1, null, "http://s.test/$key", null, null, null, 1, Instant.EPOCH)

    private fun details(key: String) =
        ProviderDetailsEntity(p, key, "Plot", null, null, null, null, null, null, Instant.EPOCH)

    @Test fun importsFilmsAndShowsWithKeysAndCategories() = runTest {
        answer("get_vod_categories", """[{"category_id":"1","category_name":"Action"}]""")
        films(
            film(
                10, "Heat", extra = ""","container_extension":"mkv","tmdb":"949","year":"1995",""" +
                    """"rating":"7.9","stream_icon":"http://img.test/heat.jpg"""",
            ),
            film(11, "Lost", cat = "9"),
        )
        answer("get_series_categories", """[{"category_id":"2","category_name":"Drama"}]""")
        shows(
            show(
                20, "Wire", extra = ""","cover":"http://img.test/w.jpg","plot":"Baltimore","cast":"Idris",""" +
                    """"genre":"Crime","rating":"9.3","year":"2002","tmdb":"1438","backdrop_path":["http://img.test/b.jpg"]""",
            ),
            show(21, "Loose", cat = null),
        )

        val result = run()

        assertEquals(XtreamVodResult(films = 2, shows = 2, failed = false), result)
        val heat = filmRow("xm:10")!!
        assertEquals("Heat", heat.name)
        assertEquals("Action", heat.category)
        assertEquals("http://img.test/heat.jpg", heat.logo)
        assertTrue(heat.streamUrl, heat.streamUrl.endsWith("/movie/u/p/10.mkv"))
        assertNull(heat.userAgent)
        assertNull(heat.referrer)
        assertEquals("tmdb:949", heat.tvgId)
        assertEquals(1, heat.position)
        assertEquals("mkv", heat.container)
        assertEquals(1995, heat.providerYear)
        assertEquals(7.9, heat.providerRating!!, 0.001)
        val lost = filmRow("xm:11")!!
        assertEquals(UNCATEGORISED, lost.category)
        assertEquals(2, lost.position)
        assertNull(lost.tvgId)
        assertTrue(lost.streamUrl.endsWith("/movie/u/p/11.mp4"))

        val wire = showRow("xs:20")!!
        assertEquals("Wire", wire.name)
        assertEquals("Drama", wire.groupTitle)
        assertEquals("Drama", wire.category)
        assertEquals("http://img.test/w.jpg", wire.logo)
        assertEquals(ShowSource.XTREAM, wire.source)
        assertEquals("1438", wire.tvgId)
        assertEquals("100", wire.lastModified)
        assertEquals("Baltimore", wire.plot)
        assertEquals("Idris", wire.cast)
        assertEquals("Crime", wire.genre)
        assertEquals(9.3, wire.rating!!, 0.001)
        assertEquals(2002, wire.year)
        assertEquals("http://img.test/b.jpg", wire.backdrop)
        assertEquals(1, wire.position)
        assertNull(wire.episodesFetchedAt)
        val loose = showRow("xs:21")!!
        assertEquals(UNCATEGORISED, loose.groupTitle)
        assertNull(loose.category)
        assertEquals(2, loose.position)
    }

    @Test fun firstImportCountsAsOldLaterOnesAsNew() = runTest {
        films(film(1))
        shows(show(1))
        run()
        assertEquals(Instant.EPOCH, filmRow("xm:1")!!.firstSeenAt)
        assertEquals(Instant.EPOCH, showRow("xs:1")!!.firstSeenAt)

        clock = Instant.ofEpochMilli(2_000_000)
        films(film(1), film(2))
        shows(show(1), show(2))
        run()

        assertEquals(Instant.EPOCH, filmRow("xm:1")!!.firstSeenAt)
        assertEquals(clock, filmRow("xm:2")!!.firstSeenAt)
        assertEquals(Instant.EPOCH, showRow("xs:1")!!.firstSeenAt)
        assertEquals(clock, showRow("xs:2")!!.firstSeenAt)
    }

    @Test fun refreshKeepsFirstSeenAndFetchedEpisodes() = runTest {
        films(film(1))
        shows(show(1, modified = "100"))
        run()
        val fetched = Instant.ofEpochMilli(1_500_000)
        val dao = db.xtreamVodDao()
        dao.upsertShows(listOf(showRow("xs:1")!!.copy(episodesFetchedAt = fetched, firstSeenAt = Instant.ofEpochMilli(7))))
        db.vodWriteDao().upsertEpisodes(listOf(episode("xe:5", "xs:1")))

        clock = Instant.ofEpochMilli(3_000_000)
        shows(show(1, name = "Renamed", modified = "100"))
        run()

        val row = showRow("xs:1")!!
        assertEquals("Renamed", row.name)
        assertEquals(Instant.ofEpochMilli(7), row.firstSeenAt)
        assertEquals(fetched, row.episodesFetchedAt)
        assertEquals(listOf("xe:5"), db.vodBrowseDao().episodesOnce(p, "xs:1").map { it.key })
    }

    @Test fun changedLastModifiedMarksEpisodesStale() = runTest {
        shows(show(1, modified = "100"))
        run()
        db.xtreamVodDao().upsertShows(listOf(showRow("xs:1")!!.copy(episodesFetchedAt = Instant.ofEpochMilli(5))))
        db.vodWriteDao().upsertEpisodes(listOf(episode("xe:5", "xs:1")))

        shows(show(1, modified = "200"))
        run()

        val row = showRow("xs:1")!!
        assertNull(row.episodesFetchedAt)
        assertEquals("200", row.lastModified)
        assertEquals(listOf("xe:5"), db.vodBrowseDao().episodesOnce(p, "xs:1").map { it.key })
    }

    @Test fun vanishedTitlesDeletedWithEpisodesAndDetails() = runTest {
        films(film(1), film(2))
        shows(show(1), show(2))
        run()
        db.xtreamVodDao().upsertProviderDetails(details("xm:2"))
        db.xtreamVodDao().upsertProviderDetails(details("xm:1"))
        db.vodWriteDao().upsertEpisodes(listOf(episode("xe:5", "xs:2"), episode("xe:6", "xs:1")))

        films(film(1))
        shows(show(1))
        run()

        assertEquals(setOf("xm:1"), filmKeys())
        assertEquals(setOf("xs:1"), showKeys())
        assertNull(db.xtreamVodDao().providerDetailsNow(p, "xm:2"))
        assertNotNull(db.xtreamVodDao().providerDetailsNow(p, "xm:1"))
        assertNull(db.vodBrowseDao().episodeRow(p, "xe:5"))
        assertNotNull(db.vodBrowseDao().episodeRow(p, "xe:6"))
    }

    @Test fun failedListKeepsRowsAndDeletesNothing() = runTest {
        films(film(1), film(2))
        shows(show(1))
        run()

        answer("get_vod_streams") { MockResponse().setResponseCode(500) }
        shows(show(2, name = "Fresh"))
        val result = run()

        assertTrue(result.failed)
        assertEquals(setOf("xm:1", "xm:2"), filmKeys())
        assertEquals(setOf("xs:2"), showKeys())
        assertEquals(1, result.shows)
    }

    @Test fun dropMidListUpsertsButDeletesNothing() = runTest {
        films(film(1), film(2), film(3))
        run()

        clock = Instant.ofEpochMilli(4_000_000)
        val cut = "[" + film(4, "New") + "," + film(1, "Renamed") + "," + """{"stream_id":"2","na"""
        answer("get_vod_streams") { MockResponse().setBody(cut) }
        val result = run()

        assertTrue(result.failed)
        assertEquals(setOf("xm:1", "xm:2", "xm:3", "xm:4"), filmKeys())
        assertEquals("Renamed", filmRow("xm:1")!!.name)
        assertEquals(clock, filmRow("xm:4")!!.firstSeenAt)
    }

    @Test fun disconnectMidBodyDeletesNothing() = runTest {
        films(film(1), film(2))
        run()

        val big = (100L..3000L).joinToString(",", "[", "]") { film(it) }
        answer("get_vod_streams") {
            MockResponse().setBody(big).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        val result = run()

        assertTrue(result.failed)
        assertTrue(filmKeys().containsAll(setOf("xm:1", "xm:2")))
    }

    @Test fun bothEmptyWithNoLiveDeletesNothing() = runTest {
        films(film(1))
        shows(show(1))
        run()

        films()
        shows()
        val result = run(live = 0)

        assertEquals(XtreamVodResult(0, 0, false), result)
        assertEquals(setOf("xm:1"), filmKeys())
        assertEquals(setOf("xs:1"), showKeys())

        // With live channels, an empty list is believed.
        run(live = 5)
        assertEquals(emptySet<String>(), filmKeys())
        assertEquals(emptySet<String>(), showKeys())
    }

    @Test fun duplicateIdsFirstWins() = runTest {
        answer("get_vod_categories", """[{"category_id":"1","category_name":"Action"},{"category_id":"3","category_name":"Comedy"}]""")
        films(film(1, "First", cat = "1"), film(2), film(1, "Second", cat = "3"))
        answer("get_series_categories", """[{"category_id":"2","category_name":"Drama"},{"category_id":"4","category_name":"Kids"}]""")
        shows(show(7, "First", cat = "2"), show(7, "Second", cat = "4"))

        val result = run()

        assertEquals(XtreamVodResult(films = 2, shows = 1, failed = false), result)
        val f = filmRow("xm:1")!!
        assertEquals("First", f.name)
        assertEquals("Action", f.category)
        assertEquals(1, f.position)
        assertEquals(2, filmRow("xm:2")!!.position)
        val s = showRow("xs:7")!!
        assertEquals("First", s.name)
        assertEquals("Drama", s.groupTitle)
    }

    @Test fun largeListWrittenInBatches() = runTest {
        films(*(1L..1200L).map { film(it) }.toTypedArray())

        val result = run()

        assertEquals(1200, result.films)
        val keys = filmKeys()
        assertEquals(1200, keys.size)
        for (id in listOf(1L, 499L, 500L, 501L, 999L, 1000L, 1001L, 1200L)) assertTrue("xm:$id", "xm:$id" in keys)
        assertEquals(1000, filmRow("xm:1000")!!.position)
        assertEquals(1001, filmRow("xm:1001")!!.position)
    }

    @Test fun requestsUseUserAgent() = runTest {
        films(film(1))
        shows(show(1))

        run(userAgent = "Loophole-Test/1")

        val actions = requests.map { it.requestUrl?.queryParameter("action") }
        assertEquals(listOf("get_vod_categories", "get_vod_streams", "get_series_categories", "get_series"), actions)
        assertTrue(requests.all { it.getHeader("User-Agent") == "Loophole-Test/1" })
        assertFalse(requests.isEmpty())
    }
}
