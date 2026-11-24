package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.addXtreamPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
class XtreamFilmDetailsTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private var p = 0L
    private var clock = Instant.ofEpochSecond(2_000_000_000)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var reply: () -> MockResponse = { MockResponse().setBody(INFO) }

    private val film = "xm:42"

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
        addFilm(p, film)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun addFilm(playlist: Long, key: String) = db.xtreamVodDao().upsertFilms(
        listOf(FilmEntity(playlist, key, "Matrix", "Action", null, "http://s.test/$key", null, null, null, 1, Instant.EPOCH)),
    )

    private fun info() = XtreamFilmDetails(db, XtreamClient(OkHttpClient()), userAgent = { "UA/1" }, clock = { clock })

    private suspend fun row(key: String = film): ProviderDetailsEntity? = db.xtreamVodDao().providerDetailsNow(p, key)

    private fun later(d: Duration) { clock = clock.plus(d) }

    @Test fun fetchesOnceThenCachesSevenDays() = runTest {
        info().refreshIfStale(p, film)

        val r = row()!!
        assertEquals("Neo wakes up.", r.plot)
        assertEquals("Keanu Reeves, Carrie-Anne Moss", r.cast)
        assertEquals("Action", r.genre)
        assertEquals(7.9, r.rating!!, 0.0001)
        assertEquals(1999, r.year)
        assertEquals(136, r.runtimeMinutes)
        assertEquals("https://img.test/bd.jpg", r.backdrop)
        assertEquals(clock, r.fetchedAt)
        assertEquals(1, requests.size)
        val url = requests[0].requestUrl!!
        assertEquals("get_vod_info", url.queryParameter("action"))
        assertEquals("42", url.queryParameter("vod_id"))
        assertEquals("UA/1", requests[0].getHeader("User-Agent"))

        later(Duration.ofDays(7).minusMinutes(1))
        info().refreshIfStale(p, film)

        assertEquals(1, requests.size)
    }

    @Test fun refetchesAfterSevenDays() = runTest {
        info().refreshIfStale(p, film)
        later(Duration.ofDays(7))
        reply = { MockResponse().setBody(INFO.replace("Neo wakes up.", "Neo wakes up again.")) }

        info().refreshIfStale(p, film)

        assertEquals(2, requests.size)
        val r = row()!!
        assertEquals("Neo wakes up again.", r.plot)
        assertEquals(clock, r.fetchedAt)
    }

    @Test fun failureWritesNothingAndRetriesNextOpen() = runTest {
        reply = { MockResponse().setResponseCode(500) }

        info().refreshIfStale(p, film)

        assertNull(row())
        reply = { MockResponse().setBody(INFO) }
        info().refreshIfStale(p, film)

        assertEquals(2, requests.size)
        assertNotNull(row())
    }

    @Test fun failureKeepsTheStaleRow() = runTest {
        info().refreshIfStale(p, film)
        later(Duration.ofDays(8))
        reply = { MockResponse().setResponseCode(500) }

        info().refreshIfStale(p, film)

        assertEquals("Neo wakes up.", row()!!.plot)
        assertEquals(clock.minus(Duration.ofDays(8)), row()!!.fetchedAt)
    }

    @Test fun noInfoIsCachedNotRefetched() = runTest {
        reply = { MockResponse().setBody("""{"info":[],"movie_data":{}}""") }

        info().refreshIfStale(p, film)

        val r = row()!!
        assertNull(r.plot)
        assertNull(r.cast)
        assertNull(r.genre)
        assertNull(r.rating)
        assertNull(r.year)
        assertNull(r.runtimeMinutes)
        assertNull(r.backdrop)
        assertEquals(clock, r.fetchedAt)

        later(Duration.ofDays(6))
        info().refreshIfStale(p, film)
        assertEquals(1, requests.size)
    }

    @Test fun refusalOrOddReplyCachesNothing() = runTest {
        for (body in listOf("""{"user_info":{"auth":0}}""", "[]")) {
            reply = { MockResponse().setBody(body) }
            info().refreshIfStale(p, film)
            assertNull(body, row())
        }
        // Not cached, so the next open asks again.
        assertEquals(2, requests.size)
    }

    @Test fun refusalKeepsTheStaleRow() = runTest {
        info().refreshIfStale(p, film)
        later(Duration.ofDays(8))
        reply = { MockResponse().setBody("""{"user_info":{"auth":0}}""") }

        info().refreshIfStale(p, film)

        assertEquals("Neo wakes up.", row()!!.plot)
        assertEquals(clock.minus(Duration.ofDays(8)), row()!!.fetchedAt)
    }

    @Test fun emptyInfoObjectIsCached() = runTest {
        reply = { MockResponse().setBody("""{"info":{}}""") }

        info().refreshIfStale(p, film)

        assertEquals(clock, row()!!.fetchedAt)
        assertNull(row()!!.plot)
    }

    @Test fun m3uFilmNeverRequests() = runTest {
        val m3u = db.addPlaylist()
        addFilm(m3u, "http://s.test/matrix.mp4")
        // An M3U film on an M3U playlist, a non-Xtream key on an Xtream playlist, and an `xm:`-like key on M3U.
        addFilm(p, "http://s.test/other.mp4")
        addFilm(m3u, "xm:42")

        info().refreshIfStale(m3u, "http://s.test/matrix.mp4")
        info().refreshIfStale(p, "http://s.test/other.mp4")
        info().refreshIfStale(m3u, "xm:42")

        assertTrue(requests.isEmpty())
        assertNull(db.xtreamVodDao().providerDetailsNow(m3u, "xm:42"))
    }

    @Test fun filmsAndSeriesOffNeverRequests() = runTest {
        db.playlistDao().setXtreamVod(p, false)

        info().refreshIfStale(p, film)

        assertTrue(requests.isEmpty())
        assertNull(row())
    }

    @Test fun filmRemovedWhileFetchingGetsNoRow() = runTest {
        val gate = CountDownLatch(1)
        val asked = CountDownLatch(1)
        reply = {
            asked.countDown()
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody(INFO)
        }

        val fetch = async(Dispatchers.IO) { info().refreshIfStale(p, film) }
        withContext(Dispatchers.IO) { assertTrue(asked.await(10, TimeUnit.SECONDS)) }
        db.xtreamVodDao().deleteFilms(p, listOf(film))
        gate.countDown()
        fetch.await()

        assertNull(row())
    }

    @Test fun secondOpenDuringAFetchDoesNotAskAgain() = runTest {
        val gate = CountDownLatch(1)
        val asked = CountDownLatch(1)
        reply = {
            asked.countDown()
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody(INFO)
        }
        val repo = info()

        val first = async(Dispatchers.IO) { repo.refreshIfStale(p, film) }
        withContext(Dispatchers.IO) { assertTrue(asked.await(10, TimeUnit.SECONDS)) }
        // Whether or not the second open reaches the lock before the first ends, it must not ask again.
        val second = async(Dispatchers.IO) { repo.refreshIfStale(p, film) }
        gate.countDown()
        listOf(first, second).awaitAll()

        assertEquals(1, requests.size)
        assertNotNull(row())
    }

    private companion object {
        const val INFO = """{"info":{"plot":"Neo wakes up.","cast":"Keanu Reeves, Carrie-Anne Moss","genre":"Action",""" +
            """"rating":"7.9","releasedate":"1999-03-31","duration_secs":8160,"backdrop_path":["https://img.test/bd.jpg"]},""" +
            """"movie_data":{"stream_id":42}}"""
    }
}
