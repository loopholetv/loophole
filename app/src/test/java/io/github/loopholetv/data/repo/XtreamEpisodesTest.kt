package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.ShowSource
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class XtreamEpisodesTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private var p = 0L
    private var clock = Instant.ofEpochSecond(2_000_000_000)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val http = OkHttpClient()

    @Volatile private var reply: () -> MockResponse = { MockResponse().setBody("{}") }

    private val show = "xs:7"

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return reply()
            }
        }
        server.start()
        p = db.addXtreamPlaylist(server = server.url("/").toString().trimEnd('/'))
        db.xtreamVodDao().upsertShows(listOf(showRow()))
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun showRow(key: String = show, fetchedAt: Instant? = null, firstSeen: Instant = Instant.EPOCH) = ShowEntity(
        playlistId = p, key = key, name = "Wire", groupTitle = "Drama", category = "Drama", logo = null,
        firstSeenAt = firstSeen, position = 1, source = ShowSource.XTREAM, lastModified = "100", episodesFetchedAt = fetchedAt,
    )

    private fun repo(ua: String = "UA/1", beforeWait: suspend () -> Unit = {}) =
        XtreamEpisodes(db, XtreamClient(http), userAgent = { ua }, clock = { clock }, beforeWait = beforeWait)

    private fun ep(id: Long, season: Int = 1, num: Int = id.toInt(), title: String? = "T$id", ext: String? = "mkv") =
        """{"id":"$id","season":$season,"episode_num":$num${title?.let { ""","title":"$it"""" } ?: ""}""" +
            """${ext?.let { ""","container_extension":"$it"""" } ?: ""},"info":{"movie_image":"http://img.test/$id.jpg"}}"""

    private fun answer(vararg episodes: String) {
        val body = """{"episodes":${episodes.joinToString(",", "[", "]")}}"""
        reply = { MockResponse().setBody(body) }
    }

    private suspend fun episodes(): List<EpisodeEntity> =
        db.vodBrowseDao().episodesOnce(p, show).sortedBy { it.position }

    private suspend fun showNow(): ShowEntity? = db.xtreamVodDao().showOnce(p, show)

    private fun later(d: Duration) { clock = clock.plus(d) }

    @Test fun firstFetchStoresEpisodesWithoutBumpingShow() = runTest {
        answer(ep(101, title = "Pilot"), ep(102, title = null, ext = null))

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))

        val rows = episodes()
        assertEquals(listOf("xe:101", "xe:102"), rows.map { it.key })
        val pilot = rows[0]
        assertEquals(show, pilot.showKey)
        assertEquals("Drama", pilot.groupTitle)
        assertEquals("Pilot", pilot.name)
        assertEquals(1, pilot.season)
        assertEquals(101, pilot.episode)
        assertEquals("http://img.test/101.jpg", pilot.logo)
        assertEquals("mkv", pilot.container)
        assertTrue(pilot.streamUrl, pilot.streamUrl.endsWith("/series/u/p/101.mkv"))
        assertEquals(1, pilot.position)
        assertEquals(Instant.EPOCH, pilot.firstSeenAt)
        assertEquals("Episode 102", rows[1].name)
        assertNull(rows[1].container)
        assertTrue(rows[1].streamUrl.endsWith("/series/u/p/102.mp4"))
        assertEquals(2, rows[1].position)
        assertEquals(Instant.EPOCH, rows[1].firstSeenAt)
        val s = showNow()!!
        assertEquals(clock, s.episodesFetchedAt)
        assertEquals(Instant.EPOCH, s.firstSeenAt)
    }

    @Test fun freshEpisodesAreNotRefetched() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()
        later(Duration.ofHours(23))

        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, show))
        assertEquals(0, requests.size)
    }

    @Test fun staleAfter24Hours() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()
        later(Duration.ofHours(24))

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))
        assertEquals(1, requests.size)
        assertEquals(clock, showNow()!!.episodesFetchedAt)
    }

    @Test fun forceFetchesFreshEpisodes() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show, force = true))
        assertEquals(1, requests.size)
    }

    @Test fun staleAfterLastModifiedChanged() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()
        // A playlist refresh that sees a new last_modified clears the fetch time.
        XtreamVodWriter(db, p, clock).run {
            show(showRow().copy(lastModified = "200"))
            flush()
        }
        assertNull(showNow()!!.episodesFetchedAt)

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))
        assertEquals(1, requests.size)
    }

    @Test fun newEpisodeKeyBumpsShowFirstSeen() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        val firstAt = clock
        later(Duration.ofHours(25))
        answer(ep(101), ep(102))

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))

        assertEquals(clock, showNow()!!.firstSeenAt)
        val rows = episodes()
        assertEquals(Instant.EPOCH, rows[0].firstSeenAt)
        assertEquals(clock, rows[1].firstSeenAt)
        assertTrue(clock.isAfter(firstAt))
    }

    @Test fun sameKeysDontBumpShow() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        later(Duration.ofHours(25))

        repo().refreshIfStale(p, show)

        assertEquals(Instant.EPOCH, showNow()!!.firstSeenAt)
    }

    @Test fun keysStableAcrossFetchesAndProgressKept() = runTest {
        answer(ep(101, title = "Old"), ep(102))
        repo().refreshIfStale(p, show)
        val progress = VodProgressEntity(VodKind.EPISODE, p, "xe:101", 60_000, 3_600_000, false, clock)
        db.vodProgressDao().upsert(progress)
        later(Duration.ofHours(25))
        answer(ep(102), ep(101, title = "New"))

        repo().refreshIfStale(p, show)

        val rows = episodes()
        assertEquals(listOf("xe:102", "xe:101"), rows.map { it.key })
        assertEquals("New", rows[1].name)
        assertEquals(Instant.EPOCH, rows[1].firstSeenAt)
        assertEquals(progress, db.vodProgressDao().get(VodKind.EPISODE, p, "xe:101"))
    }

    @Test fun episodeMissingFromReplyIsRemoved() = runTest {
        answer(ep(101), ep(102))
        repo().refreshIfStale(p, show)
        later(Duration.ofHours(25))
        answer(ep(102))

        repo().refreshIfStale(p, show)

        assertEquals(listOf("xe:102"), episodes().map { it.key })
    }

    @Test fun emptyReplyIsSuccessfulEmptyFetch() = runTest {
        answer()

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))

        assertTrue(episodes().isEmpty())
        assertEquals(clock, showNow()!!.episodesFetchedAt)
        requests.clear()
        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, show))
        assertEquals(0, requests.size)
    }

    @Test fun emptyReplyKeepsStoredEpisodes() = runTest {
        answer(ep(101), ep(102))
        repo().refreshIfStale(p, show)
        later(Duration.ofHours(25))
        answer()

        assertEquals(EpisodeFetch.FETCHED, repo().refreshIfStale(p, show))

        assertEquals(listOf("xe:101", "xe:102"), episodes().map { it.key })
        assertEquals(clock, showNow()!!.episodesFetchedAt)
    }

    @Test fun failureKeepsStoredEpisodes() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        val fetchedAt = clock
        later(Duration.ofHours(25))
        reply = { MockResponse().setResponseCode(500) }

        assertEquals(EpisodeFetch.FAILED, repo().refreshIfStale(p, show))

        assertEquals(listOf("xe:101"), episodes().map { it.key })
        assertEquals(fetchedAt, showNow()!!.episodesFetchedAt)
        reply = { MockResponse().setBody("not json") }
        assertEquals(EpisodeFetch.FAILED, repo().refreshIfStale(p, show))
    }

    @Test fun refusalStoresNothing() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        val fetchedAt = clock
        later(Duration.ofHours(25))

        // A login answer instead of the show, or a reply that isn't an object: failed, not "no episodes".
        for (body in listOf("""{"user_info":{"auth":0}}""", "[]")) {
            reply = { MockResponse().setBody(body) }
            assertEquals(body, EpisodeFetch.FAILED, repo().refreshIfStale(p, show))
        }
        assertEquals(listOf("xe:101"), episodes().map { it.key })
        assertEquals(fetchedAt, showNow()!!.episodesFetchedAt)
    }

    @Test fun refusalOnFirstFetchLeavesShowUnfetched() = runTest {
        reply = { MockResponse().setBody("""{"user_info":{"auth":0}}""") }

        assertEquals(EpisodeFetch.FAILED, repo().refreshIfStale(p, show))

        assertTrue(episodes().isEmpty())
        assertNull(showNow()!!.episodesFetchedAt)
    }

    @Test fun clearedWhileWaitingStillFetches() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()
        // Fresh when asked; a playlist refresh with a new last_modified clears the fetch time meanwhile.
        val episodes = repo(beforeWait = {
            XtreamVodWriter(db, p, clock).run {
                show(showRow().copy(lastModified = "200"))
                flush()
            }
        })

        assertEquals(EpisodeFetch.FETCHED, episodes.refreshIfStale(p, show))
        assertEquals(1, requests.size)
    }

    @Test fun concurrentCallsMakeOneRequest() = concurrentCalls(force = false)

    @Test fun concurrentTryAgainMakesOneRequest() = concurrentCalls(force = true)

    private fun concurrentCalls(force: Boolean) = runTest {
        // The reply waits until every caller has looked at the show and gone on to the lock.
        val waiting = CountDownLatch(3)
        val body = """{"episodes":[${ep(101)}]}"""
        reply = {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            MockResponse().setBody(body)
        }
        val episodes = repo(beforeWait = { waiting.countDown() })

        val results = List(3) { async(kotlinx.coroutines.Dispatchers.IO) { episodes.refreshIfStale(p, show, force) } }

        val outcomes = results.awaitAll()
        assertEquals(1, outcomes.count { it == EpisodeFetch.FETCHED })
        assertEquals(2, outcomes.count { it == EpisodeFetch.NOT_NEEDED })
        assertEquals(1, requests.size)
    }

    @Test fun fetchForRemovedShowWritesNothing() = runTest {
        val gate = CountDownLatch(1)
        val arrived = CountDownLatch(1)
        val body = """{"episodes":[${ep(101)}]}"""
        reply = {
            arrived.countDown()
            gate.await(5, TimeUnit.SECONDS)
            MockResponse().setBody(body)
        }
        val fetch = async(kotlinx.coroutines.Dispatchers.IO) { repo().refreshIfStale(p, show) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        // The playlist refresh drops the show while its episodes are downloading.
        db.xtreamVodDao().deleteShows(p, listOf(show))
        gate.countDown()

        assertEquals(EpisodeFetch.NOT_NEEDED, fetch.await())
        assertTrue(db.vodBrowseDao().episodesOnce(p, show).isEmpty())
        assertNull(showNow())
    }

    @Test fun missingShowMakesNoRequest() = runTest {
        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, "xs:99"))
        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, "show/7"))
        assertEquals(0, requests.size)
    }

    @Test fun usesCurrentLoginAndUserAgent() = runTest {
        val playlist = db.playlistDao().byId(p)!!
        db.playlistDao().update(playlist.copy(xtreamUser = "new", xtreamPass = "secret"))
        answer(ep(101))

        repo(ua = "Agent/2").refreshIfStale(p, show)

        val r = requests.single()
        assertEquals("new", r.requestUrl!!.queryParameter("username"))
        assertEquals("secret", r.requestUrl!!.queryParameter("password"))
        assertEquals("get_series_info", r.requestUrl!!.queryParameter("action"))
        assertEquals("7", r.requestUrl!!.queryParameter("series_id"))
        assertEquals("Agent/2", r.getHeader("User-Agent"))
        assertTrue(episodes().single().streamUrl.endsWith("/series/new/secret/101.mkv"))
    }

    @Test fun vodOffMakesNoRequest() = runTest {
        db.playlistDao().setXtreamVod(p, false)
        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, show, force = true))
        repo().fetchForNext(VodRef(VodKind.EPISODE, p, "xe:101"))
        db.playlistDao().setXtreamVod(p, true)
        val playlist = db.playlistDao().byId(p)!!
        db.playlistDao().update(playlist.copy(type = io.github.loopholetv.data.db.PlaylistType.M3U))
        assertEquals(EpisodeFetch.NOT_NEEDED, repo().refreshIfStale(p, show, force = true))
        assertEquals(0, requests.size)
    }

    @Test fun fetchForNextSkipsWhenFetchedMinutesAgo() = runTest {
        answer(ep(101))
        repo().refreshIfStale(p, show)
        requests.clear()
        val ref = VodRef(VodKind.EPISODE, p, "xe:101")
        answer(ep(101), ep(102))

        later(Duration.ofMinutes(9))
        repo().fetchForNext(ref)
        assertEquals(0, requests.size)

        // Under 24 hours, but over the gap: the player fetches so a new episode is found.
        later(Duration.ofMinutes(2))
        repo().fetchForNext(ref)
        assertEquals(1, requests.size)
        assertEquals(listOf("xe:101", "xe:102"), episodes().map { it.key })
    }

    @Test fun fetchForNextIgnoresOtherItems() = runTest {
        repo().fetchForNext(VodRef(VodKind.EPISODE, p, "xe:999"))
        repo().fetchForNext(VodRef(VodKind.EPISODE, p, "series/1"))
        repo().fetchForNext(VodRef(VodKind.MOVIE, p, "xm:1"))
        assertEquals(0, requests.size)
    }

    @Test fun recognisesXtreamShows() {
        assertTrue(XtreamEpisodes.isXtreamShow("xs:7"))
        assertFalse(XtreamEpisodes.isXtreamShow("show/7"))
        assertNotNull(EpisodeFetch.valueOf("FAILED"))
    }
}
