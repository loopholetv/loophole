package io.github.loopholetv.cast

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CastClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
    private val client = CastClient(http)

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private fun peer(host: String = "127.0.0.1", port: Int = server.port) = CastPeer(
        "tv-a", "Living room", host, port, CastStatus.CASTING, true, "cast1", "tok", 1_000L, 0L,
    )

    private fun lines(vararg events: CastEvent) = events.joinToString("") { CastJson.event(it) + "\n" }

    @Test fun eventsReadsEachLineAndCompletesAfterStopped() = runBlocking {
        val after = CastEvent.Idle("cast1")
        server.enqueue(
            MockResponse().setBody(
                lines(CastEvent.Idle("cast1"), CastEvent.Playing("cast1", 1, "/c/tok/1.ts", false)) +
                    "not json\n\n" +
                    lines(CastEvent.Heartbeat("cast1", 1, 2, null), CastEvent.Stopped("cast1", StopReason.STOPPED), after),
            ),
        )

        val got = client.events(peer(), "self-id").toList()

        assertEquals(
            listOf(
                CastEvent.Idle("cast1"),
                CastEvent.Playing("cast1", 1, "/c/tok/1.ts", false),
                CastEvent.Heartbeat("cast1", 1, 2, null),
                CastEvent.Stopped("cast1", StopReason.STOPPED),
            ),
            got,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/c/tok/events?id=self-id", request.path)
    }

    @Test fun eachHeartbeatIsAnswered() = runBlocking {
        val events = lines(
            CastEvent.Idle("cast1"),
            CastEvent.Heartbeat("cast1", 0, 1, null),
            CastEvent.Playing("cast1", 1, "/c/tok/1.ts", false),
            CastEvent.Heartbeat("cast1", 1, 1, null),
            CastEvent.Stopped("cast1", StopReason.STOPPED),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.method == "POST") MockResponse().setResponseCode(204) else MockResponse().setBody(events)
        }

        assertEquals(5, client.events(peer(), "self-id").toList().size)

        val paths = List(3) { server.takeRequest(5, TimeUnit.SECONDS)!!.let { "${it.method} ${it.path}" } }
        assertEquals("GET /c/tok/events?id=self-id", paths[0])
        assertEquals(List(2) { "POST /c/tok/alive?id=self-id" }, paths.drop(1))
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun fullCastThrowsCastFull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            client.events(peer(), "self-id").toList()
            fail("expected CastFullException")
        } catch (_: CastFullException) {
        }
    }

    @Test fun otherRefusalsAreIoErrorsButNotFull() = runBlocking {
        for (code in listOf(401, 409, 404)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                client.events(peer(), "self-id").toList()
                fail("expected an IOException for $code")
            } catch (e: IOException) {
                assertFalse(e is CastFullException)
                assertEquals(code, (e as CastRefusedException).code)
            }
        }
    }

    @Test fun endWithoutStoppedThrows() = runBlocking {
        server.enqueue(MockResponse().setBody(lines(CastEvent.Idle("cast1"))))
        val got = mutableListOf<CastEvent>()
        try {
            client.events(peer(), "self-id").collect { got += it }
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals(listOf(CastEvent.Idle("cast1")), got)
    }

    @Test fun silenceThrowsOnReadTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        try {
            client.events(peer(), "self-id").toList()
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test fun overlongLineThrows() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(20_000) + "\n"))
        try {
            client.events(peer(), "self-id").toList()
            fail("expected an IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun leavePostsToTheCast() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.leave(peer(), "self-id")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/c/tok/leave?id=self-id", request.path)
    }

    @Test fun leaveSwallowsErrors() = runBlocking {
        val gone = peer(port = server.port)
        server.shutdown()
        client.leave(gone, "self-id")
    }

    @Test fun nonLocalPeerIsRefusedWithoutConnecting() = runBlocking {
        for (host in listOf("8.8.8.8", "example.test", "")) {
            try {
                client.events(peer(host = host), "self-id").toList()
                fail("expected a refusal for '$host'")
            } catch (_: IOException) {
            }
            client.leave(peer(host = host), "self-id")
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun mediaUrlIsBuiltOnlyForTheCastsOwnRelayPaths() {
        val p = peer(host = "192.168.1.20", port = 41_000)
        assertEquals("http://192.168.1.20:41000/c/tok/3.ts", CastClient.mediaUrl(p, "/c/tok/3.ts"))
        assertEquals("http://192.168.1.20:41000/c/tok/3/index.m3u8", CastClient.mediaUrl(p, "/c/tok/3/index.m3u8"))
        for (bad in listOf("/c/other/3.ts", "/c/tok/../x", "http://example.test/c/tok/3.ts", "/c/tok/3.ts?x=1", "c/tok/3.ts", "/c/tok/", "/c/tok/a b")) {
            assertNull(bad, CastClient.mediaUrl(p, bad))
        }
        assertEquals("http://192.168.1.20:41000/c/tok/3.ts?id=me", CastClient.mediaUrl(p, "/c/tok/3.ts", "me"))
        assertEquals("http://192.168.1.20:41000/c/tok/3/index.m3u8", CastClient.mediaUrl(p, "/c/tok/3/index.m3u8", "me"))
        assertNull(CastClient.mediaUrl(peer(host = "8.8.8.8"), "/c/tok/3.ts"))
        assertNull(CastClient.mediaUrl(p.copy(token = null), "/c/tok/3.ts"))
    }
}
