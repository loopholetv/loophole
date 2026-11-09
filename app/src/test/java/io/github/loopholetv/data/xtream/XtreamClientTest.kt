package io.github.loopholetv.data.xtream

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class XtreamClientTest {
    private lateinit var server: MockWebServer
    private val client = XtreamClient(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/xtream/$name")!!.readBytes().decodeToString()

    private fun creds() = XtreamCredentials("http://${server.hostName}:${server.port}/", "user1", "pass1")

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test fun authenticateSuccess() = runTest {
        enqueue(res("auth_ok.json"))
        val a = client.authenticate(creds())
        assertEquals("Active", a.status)
        assertEquals(1, a.maxConnections)
        assertEquals(Instant.ofEpochSecond(1893456000), a.expiresAt)
        val req = server.takeRequest()
        assertEquals("/player_api.php?username=user1&password=pass1", req.path)
    }

    @Test fun serverWithoutSchemeDefaultsToHttp() = runTest {
        enqueue(res("auth_ok.json"))
        val bare = XtreamCredentials("${server.hostName}:${server.port}", "user1", "pass1")
        assertEquals("Active", client.authenticate(bare).status)
        assertEquals("http://${server.hostName}:${server.port}/live/user1/pass1/5.ts", client.liveUrl(bare, 5))
    }

    @Test fun authenticateLenientTypes() = runTest {
        enqueue(res("auth_string_flags.json"))
        val a = client.authenticate(creds())
        assertEquals(2, a.maxConnections)
        assertNull(a.expiresAt)
    }

    @Test fun authenticateRejected() = runTest {
        enqueue(res("auth_rejected.json"))
        try { client.authenticate(creds()); fail() } catch (_: XtreamAuthException) {}
    }

    @Test fun http403IsAuthFailure() = runTest {
        enqueue("Forbidden", 403)
        try { client.authenticate(creds()); fail() } catch (_: XtreamAuthException) {}
    }

    @Test fun http401IsAuthFailure() = runTest {
        enqueue("nope", 401)
        try { client.authenticate(creds()); fail() } catch (_: XtreamAuthException) {}
    }

    @Test fun htmlResponseIsProtocolError() = runTest {
        enqueue("<html><body>Welcome</body></html>")
        try { client.authenticate(creds()); fail() } catch (e: XtreamProtocolException) {
            assertFalse(e.message.orEmpty().contains("pass1"))
        }
    }

    @Test fun errorMessagesNeverLeakPassword() = runTest {
        enqueue("oops", 500)
        try { client.authenticate(creds()); fail() } catch (e: Exception) {
            assertFalse(e.message.orEmpty().contains("pass1"))
        }
    }

    @Test fun categoriesParsed() = runTest {
        enqueue(res("categories.json"))
        val cats = client.liveCategories(creds())
        assertEquals(listOf(XtreamCategory("1", "News"), XtreamCategory("2", "Sports"), XtreamCategory("3", "Movies")), cats)
        assertEquals("/player_api.php?username=user1&password=pass1&action=get_live_categories", server.takeRequest().path)
    }

    @Test fun liveStreamsStreamed() = runTest {
        enqueue(res("live_streams.json"))
        val out = mutableListOf<XtreamLiveStream>()
        client.liveStreams(creds()) { out += it }
        assertEquals(4, out.size)
        assertEquals(
            XtreamLiveStream(101, "Channel One", 5, "http://img.example.test/1.png", "one.test", "1", true, 3),
            out[0],
        )
        assertEquals(XtreamLiveStream(102, "Channel Two", 6, null, null, "2", true, 7), out[1])
        assertEquals(XtreamLiveStream(103, "Channel Three", 7, null, null, null, false, 0), out[2])
        assertEquals(104L, out[3].streamId)
        assertFalse(out[3].tvArchive)
        assertTrue(server.takeRequest().path!!.endsWith("action=get_live_streams"))
    }

    @Test fun serverNormalisation() {
        assertEquals("http://host.test:8080", XtreamCredentials("host.test:8080/", "u", "p").server)
        assertEquals("http://host.test", XtreamCredentials("host.test", "u", "p").server)
        assertEquals("https://host.test:8443", XtreamCredentials("https://host.test:8443/player_api.php?x=1", "u", "p").server)
        assertEquals("http://host.test:80", XtreamCredentials(" http://host.test:80/get.php ", "u", "p").server)
    }

    @Test fun liveUrlAndEpgUrlFormat() {
        val c = XtreamCredentials("host.test:8080/", "user1", "pass1")
        assertEquals("http://host.test:8080/live/user1/pass1/42.ts", client.liveUrl(c, 42))
        assertEquals("http://host.test:8080/xmltv.php?username=user1&password=pass1", client.epgUrl(c))
    }

    @Test fun liveAndEpgUrlsEncodeReservedCharacters() {
        val c = XtreamCredentials("host.test:8080", "us er", "p@ss/w&rd#1")
        val live = client.liveUrl(c, 7)
        assertTrue(live, live.contains("%2F") && live.contains("%23") && !live.contains("w&rd#1"))
        assertEquals(listOf("live", "us er", "p@ss/w&rd#1", "7.ts"), live.toHttpUrl().pathSegments)
        val epg = client.epgUrl(c).toHttpUrl()
        assertTrue(epg.toString(), epg.toString().contains("%26") && epg.toString().contains("%23"))
        assertEquals("p@ss/w&rd#1", epg.queryParameter("password"))
        assertEquals("us er", epg.queryParameter("username"))
    }

    @Test fun networkFailureIsRedactedAndHasNoRawCause() = runTest {
        val c = creds()
        server.shutdown()
        try { client.authenticate(c); fail() } catch (e: XtreamNetworkException) {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message.orEmpty()
                assertFalse(m, m.contains("user1") || m.contains("pass1") || m.contains("/live/"))
                t = t.cause
            }
        }
    }

    @Test fun disconnectMidRequestIsNetworkException() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try { client.authenticate(creds()); fail() } catch (e: XtreamNetworkException) {
            // coroutines stack-trace recovery may chain a copy of this same exception; never a raw IOException
            var t: Throwable? = e.cause
            while (t != null) { assertTrue(t is XtreamNetworkException); t = t.cause }
            assertFalse(e.message.orEmpty().contains("pass1"))
        }
    }

    @Test fun cancellingStopsLiveStreams() = runTest {
        val body = buildString {
            append("[")
            for (i in 1..2000) { if (i > 1) append(","); append("""{"stream_id":$i,"name":"C$i"}""") }
            append("]")
        }
        enqueue(body)
        var count = 0
        lateinit var job: Job
        job = launch(kotlinx.coroutines.Dispatchers.Default) {
            client.liveStreams(creds()) { count++; if (count == 1) job.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, count)
    }

    @Test fun emptyObjectOrNullMeansEmptyList() = runTest {
        enqueue("{}")
        assertEquals(emptyList<XtreamCategory>(), client.liveCategories(creds()))
        enqueue("null")
        assertEquals(emptyList<XtreamCategory>(), client.liveCategories(creds()))
        enqueue("{}")
        val out = mutableListOf<XtreamLiveStream>()
        client.liveStreams(creds()) { out += it }
        enqueue("null")
        client.liveStreams(creds()) { out += it }
        assertTrue(out.isEmpty())
    }

    @Test fun htmlFromListEndpointIsProtocolError() = runTest {
        enqueue("<html>nope</html>")
        try { client.liveCategories(creds()); fail() } catch (_: XtreamProtocolException) {}
    }

    private fun cancelWhileStalled(call: suspend (XtreamCredentials) -> Unit) {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { call(creds()) }
            assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)
            val started = System.nanoTime()
            job.cancel()
            kotlinx.coroutines.withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue((System.nanoTime() - started) < 2_000_000_000L)
        }
    }

    @Test fun cancelStalledLiveStreamsCancelsCall() {
        val slow = slowClient()
        cancelWhileStalled { slow.liveStreams(it) {} }
    }

    @Test fun cancelStalledAuthenticateCancelsCall() {
        val slow = slowClient()
        cancelWhileStalled { slow.authenticate(it) }
    }

    private fun slowClient() = XtreamClient(
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )
}
