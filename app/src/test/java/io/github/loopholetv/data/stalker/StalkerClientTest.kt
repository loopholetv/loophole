package io.github.loopholetv.data.stalker

import io.github.loopholetv.data.xmltv.XmltvChannel
import io.github.loopholetv.data.xmltv.XmltvProgramme
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.net.ConnectException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class StalkerClientTest {
    private lateinit var server: MockWebServer
    private val portal = FakePortal()
    private val client = StalkerClient(testHttp(), timeZone = { "Europe/London" }, nowMillis = { 1_762_696_800_000 })

    @Before fun setUp() { server = MockWebServer().also { it.dispatcher = portal; it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val base get() = "http://${server.hostName}:${server.port}"
    private val endpoint get() = "$base/stalker_portal/server/load.php"
    private val mac = "00:1A:79:00:00:01"
    private fun cfg(login: String? = null, password: String? = null) =
        PortalConfig("$base/stalker_portal/c/", mac, login = login, password = password)

    private suspend fun session() = client.authenticate(endpoint, cfg(), savedToken = null)

    @Test fun discoverTriesPortalPhpThenLoadPhp() = runTest {
        portal.path = "/server/load.php"
        val found = client.discover("$base/c/", mac)
        assertEquals("$base/server/load.php", found)
        val paths = portal.requests.map { it.requestUrl!!.encodedPath }
        assertEquals(listOf("/portal.php", "/server/load.php"), paths)
        val probe = portal.requests.last()
        assertEquals("get_genres", probe.requestUrl!!.queryParameter("action"))
        assertNull(probe.getHeader("Authorization"))
    }

    @Test fun discoverSkipsHtmlAndFindsStalkerPortalPath() = runTest {
        portal.htmlPaths += "/server/load.php"
        val found = client.discover("$base/", mac)
        assertEquals("$base/stalker_portal/server/load.php", found)
        assertEquals(listOf("/portal.php", "/server/load.php", "/stalker_portal/server/load.php"),
            portal.requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun discoverUsesPastedPhpFirstAndAcceptsSimplePanel() = runTest {
        portal.path = "/portal.php"
        portal.requireToken = false
        assertEquals("$base/portal.php", client.discover("$base/portal.php", mac))
        assertEquals(1, portal.requests.size)
    }

    @Test fun discoverStopsOnConnectionRefused() = runTest {
        val calls = AtomicInteger()
        val refusing = StalkerClient(refusingHttp(calls))
        try { refusing.discover("$base/c/", mac); fail() } catch (e: StalkerUnreachableException) {
            assertEquals(StalkerUnreachableException.Kind.REFUSED, e.kind)
        }
        assertEquals(1, calls.get())
    }

    @Test fun discoverStopsOnUnknownHost() = runTest {
        val calls = AtomicInteger()
        val noDns = StalkerClient(testHttp().newBuilder()
            .dns { calls.incrementAndGet(); throw java.net.UnknownHostException("portal.example.test") }.build())
        try { noDns.discover("http://portal.example.test/c/", mac); fail() } catch (e: StalkerUnreachableException) {
            assertEquals(StalkerUnreachableException.Kind.REFUSED, e.kind)
        }
        assertEquals(1, calls.get())
    }

    @Test fun discoverWithNoPortalIsProtocolError() = runTest {
        portal.path = "/nowhere.php"
        try { client.discover("$base/c/", mac); fail() } catch (_: StalkerProtocolException) {}
    }

    @Test fun handshakeReusesSavedTokenAndSendsMacCookie() = runTest {
        val s = client.authenticate(endpoint, cfg(), savedToken = "TOKEN-PLACEHOLDER-1")
        assertEquals("TOKEN-PLACEHOLDER-1", s.token)
        assertEquals("0123abcdef", s.random)
        assertEquals(60, s.watchdogSeconds)
        assertEquals(endpoint, s.endpoint)
        val hs = portal.ofAction("handshake").single()
        assertEquals("TOKEN-PLACEHOLDER-1", hs.requestUrl!!.queryParameter("token"))
        assertEquals("0", hs.requestUrl!!.queryParameter("prehash"))
        assertEquals("1-xml", hs.requestUrl!!.queryParameter("JsHttpRequest"))
        assertNull(hs.getHeader("Authorization"))
        for (r in portal.requests) {
            val cookie = r.getHeader("Cookie")!!
            assertTrue(cookie, cookie.contains("mac=00:1A:79:00:00:01"))
            assertTrue(cookie, cookie.contains("timezone=Europe/London"))
            assertTrue(cookie.contains("stb_lang=en"))
            assertTrue(r.getHeader("User-Agent")!!.contains("MAG200"))
            assertEquals("Model: MAG250; Link: WiFi", r.getHeader("X-User-Agent"))
        }
        val later = portal.requests.filter { it.requestUrl!!.queryParameter("action") != "handshake" }
        assertEquals(listOf("get_profile", "get_events"), later.map { it.requestUrl!!.queryParameter("action") })
        for (r in later) assertEquals("Bearer TOKEN-PLACEHOLDER-1", r.getHeader("Authorization"))
        assertEquals("1", portal.ofAction("get_events").single().requestUrl!!.queryParameter("init"))
    }

    @Test fun macTypedLowercaseIsSentNormalised() = runTest {
        client.authenticate(endpoint, PortalConfig("$base/stalker_portal/c/", "00-1a-79-00-00-01"), null)
        assertTrue(portal.requests.first().getHeader("Cookie")!!.contains("mac=00:1A:79:00:00:01"))
    }

    @Test fun tokenKeyCaseInsensitive() = runTest {
        portal.tokenKey = "Token"
        portal.token = "T"
        assertEquals("T", session().token)
    }

    @Test fun identityFieldsSentOnlyWhenTyped() = runTest {
        session()
        val p = portal.ofAction("get_profile").single().requestUrl!!
        for (k in listOf("device_id", "device_id2", "sn", "signature")) assertNull(k, p.queryParameter(k))
        assertEquals("0", p.queryParameter("auth_second_step"))
        assertEquals("MAG250", p.queryParameter("stb_type"))
        assertTrue(p.queryParameter("metrics")!!.contains("\"random\":\"0123abcdef\""))
        portal.requests.clear()
        val typed = cfg().copy(serial = "SN00000000001", deviceId = "D1", deviceId2 = "D2", signature = "S1")
        client.authenticate(endpoint, typed, null)
        val q = portal.ofAction("get_profile").single().requestUrl!!
        assertEquals("SN00000000001", q.queryParameter("sn"))
        assertEquals("D1", q.queryParameter("device_id"))
        assertEquals("D2", q.queryParameter("device_id2"))
        assertEquals("S1", q.queryParameter("signature"))
    }

    @Test fun status2RunsDoAuthThenProfile() = runTest {
        portal.loginRequired = true
        client.authenticate(endpoint, cfg(login = "user1", password = "pass1"), null)
        val actions = portal.requests.map { it.requestUrl!!.queryParameter("action") }
        assertEquals(listOf("handshake", "get_profile", "do_auth", "get_profile", "get_events"), actions)
        val second = portal.ofAction("get_profile")[1].requestUrl!!
        assertEquals("1", second.queryParameter("auth_second_step"))
        val auth = portal.ofAction("do_auth").single().requestUrl!!
        assertEquals("user1", auth.queryParameter("login"))
        assertEquals("pass1", auth.queryParameter("password"))
    }

    @Test fun status2WithoutLoginOrWrongLoginIsAuthException() = runTest {
        portal.loginRequired = true
        try { session(); fail() } catch (_: StalkerAuthException) {}
        try { client.authenticate(endpoint, cfg(login = "user1", password = "nope"), null); fail() } catch (_: StalkerAuthException) {}
    }

    @Test fun accessDeniedIsAuthException() = runTest {
        portal.accessDenied = true
        try { session(); fail() } catch (_: StalkerAuthException) {}
        portal.accessDenied = false
        portal.profileStatus = 1
        try { session(); fail() } catch (_: StalkerAuthException) {}
    }

    @Test fun authFailedBodyReHandshakesOnceThenRetries() = runTest {
        val s = session()
        portal.expireToken()
        portal.requests.clear()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals(4, got.size)
        assertEquals(portal.token, s.token) // in-memory session updated in place
        val actions = portal.requests.map { it.requestUrl!!.queryParameter("action") }
        assertEquals(listOf("get_genres", "handshake", "get_profile", "get_events", "get_genres", "get_all_channels"), actions)
        assertEquals("TOKEN-PLACEHOLDER-1", portal.ofAction("handshake").single().requestUrl!!.queryParameter("token"))
    }

    @Test fun authFailedTwiceIsAuthException() = runTest {
        val s = session()
        portal.authFailAlways += "create_link"
        try { client.createLink(s, cfg(), "ffmpeg http://localhost/ch/101_"); fail() } catch (_: StalkerAuthException) {}
        assertEquals(2, portal.count("create_link"))
        assertEquals(2, portal.count("handshake"))
    }

    @Test fun allChannelsParsedWithBooleansAndRelativeLogo() = runTest {
        val s = session()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals(listOf("101", "102", "103", "105"), got.map { it.id })
        val root = "$base/stalker_portal/"
        assertEquals(
            StalkerChannel("101", "News One", 1, "ffmpeg http://localhost/ch/101_", "News",
                "${root}misc/logos/320/a.png", "news.one.test", needsLink = true),
            got[0],
        )
        assertEquals(
            StalkerChannel("102", "Sport Two", 2, "ffmpeg http://cdn.example.test/live/102.ts", "Sports",
                "http://img.example.test/b.png", null, needsLink = false),
            got[1],
        )
        assertEquals("ffrt http://localhost/ch/103", got[2].cmd)
        assertEquals("$base/stalker_portal/misc/logos/320/c.png", got[2].logo)
        assertTrue(got[2].needsLink)
        assertNull(got[3].genre)
        assertNull(got[3].logo)
        assertTrue(got[3].needsLink)
        assertEquals(0, portal.count("get_ordered_list"))
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun pagedFallbackStopsAtTotalAndNeverSendsPageZero() = runTest {
        portal.allChannelsJson = null
        portal.pagedChannels = (1..30).map { FakePortal.row(it) }
        val s = session()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals((1..30).map { "$it" }, got.map { it.id })
        val pages = portal.ofAction("get_ordered_list").map { it.requestUrl!!.queryParameter("p") }
        assertEquals(listOf("1", "2", "3"), pages)
        val first = portal.ofAction("get_ordered_list").first().requestUrl!!
        assertEquals("*", first.queryParameter("genre"))
        assertEquals("number", first.queryParameter("sortby"))
    }

    @Test fun pagedFallbackStopsOnEmptyPageWhenTotalLies() = runTest {
        portal.allChannelsJson = """{"js":{"total_items":0,"data":[]}}"""
        portal.pagedChannels = (1..20).map { FakePortal.row(it) }
        portal.pagedTotal = 5000
        val s = session()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals(20, got.size)
        assertEquals(listOf("1", "2", "3"), portal.ofAction("get_ordered_list").map { it.requestUrl!!.queryParameter("p") })
    }

    @Test fun genresAsObjectNormalised() = runTest {
        portal.genresJson = FakePortal.fixture("genres_object.json")
        val s = session()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals(listOf("News", "Sports", "News", null), got.map { it.genre })
    }

    @Test fun createLinkStripsSolutionAndResolvesRelative() = runTest {
        val s = session()
        val url = client.createLink(s, cfg(), "ffmpeg http://localhost/ch/101_&x=1#y")
        assertEquals("http://edge.example.test/live/abc/101.ts?play_token=PLAYTOKEN1", url)
        val req = portal.ofAction("create_link").single()
        assertTrue(req.path!!, req.path!!.contains("cmd=ffmpeg%20http://localhost/ch/101_%26x=1%23y"))
        assertEquals("", req.requestUrl!!.queryParameter("series"))
        assertEquals("undefined", req.requestUrl!!.queryParameter("forced_storage"))
        portal.createLinkJson = """{"js":{"cmd":"auto /media/101.ts?t=1","error":""}}"""
        assertEquals("$base/media/101.ts?t=1", client.createLink(s, cfg(), "ffmpeg http://localhost/ch/101_"))
        portal.createLinkJson = """{"js":{"cmd":"ffmpeg file:///etc/x","error":""}}"""
        try { client.createLink(s, cfg(), "x"); fail() } catch (e: StalkerLinkException) { assertEquals("link_fault", e.code) }
    }

    @Test fun createLinkRepairsEmptyStreamParameter() = runTest {
        val s = session()
        portal.createLinkJson = """{"js":{"cmd":"ffmpeg http://edge.example.test/play/live.php?mac=x&stream=&extension=ts","error":""}}"""
        val url = client.createLink(s, cfg(), "ffmpeg http://localhost/ch/590_")
        assertEquals("http://edge.example.test/play/live.php?mac=x&stream=590&extension=ts", url)
    }

    @Test fun createLinkLimitIsLinkException() = runTest {
        val s = session()
        for ((body, code) in listOf(
            """{"js":{"cmd":"","error":"limit"}}""" to "limit",
            """{"js":{"cmd":"","error":"nothing_to_play"}}""" to "nothing_to_play",
            """{"js":{"cmd":"","error":"link_fault"}}""" to "link_fault",
            """{"js":{"cmd":"","error":""}}""" to "empty",
        )) {
            portal.createLinkJson = body
            try { client.createLink(s, cfg(), "ffmpeg http://localhost/ch/101_"); fail(code) } catch (e: StalkerLinkException) {
                assertEquals(code, e.code)
            }
        }
    }

    @Test fun epgEmitsProgrammesFromTimestampsBothShapes() = runTest {
        val s = session()
        for (fixture in listOf("epg_keyed.json", "epg_list.json")) {
            portal.epgJson = FakePortal.fixture(fixture)
            val out = mutableListOf<Any>()
            client.epg(s, cfg(), 72, mapOf("101" to StalkerChannelMeta("News One", "news.one.test"))) { out += it }
            val channels = out.filterIsInstance<XmltvChannel>()
            assertEquals(fixture, listOf("news.one.test", "stalker-102"), channels.map { it.id })
            assertEquals(listOf("News One"), channels[0].displayNames)
            val progs = out.filterIsInstance<XmltvProgramme>()
            assertEquals(fixture, 3, progs.size)
            val morning = progs.first { it.title == "Morning News" }
            assertEquals("news.one.test", morning.channelId)
            assertEquals(Instant.ofEpochSecond(1762696800), morning.start)
            assertEquals(Instant.ofEpochSecond(1762698600), morning.stop)
            assertEquals("Headlines", morning.description)
            val noon = progs.first { it.title == "Noon News" }
            assertEquals(Instant.ofEpochSecond(1762700400), noon.stop)
            assertNull(noon.description)
            assertEquals("Sport", progs.first { it.title == "Match" }.category)
            // Each channel is announced before its programmes.
            assertTrue(out.indexOf(channels[0]) < out.indexOf(morning))
        }
        assertEquals("72", portal.ofAction("get_epg_info").first().requestUrl!!.queryParameter("period"))
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun watchdogPingsAndCutOffIsAuth() = runTest {
        val s = session()
        client.watchdog(s, cfg())
        val ping = portal.ofAction("get_events").last().requestUrl!!
        assertEquals("0", ping.queryParameter("init"))
        assertEquals("watchdog", ping.queryParameter("type"))
        portal.watchdogJson = """{"js":{"data":{"event":"cut_off","msgs":1}}}"""
        try { client.watchdog(s, cfg()); fail() } catch (_: StalkerAuthException) {}
    }

    @Test fun failureMessagesNeverContainSecrets() = runTest {
        val secretCfg = PortalConfig("$base/stalker_portal/c/", mac, login = "user1", password = "pass1",
            serial = "SN00000000001", deviceId = "DEVICE1", deviceId2 = "DEVICE2", signature = "SIGNATURE1")
        val forbidden = listOf(mac, "00:1a:79", "TOKEN-PLACEHOLDER", "user1", "pass1", "SN00000000001", "DEVICE1",
            "DEVICE2", "SIGNATURE1", server.hostName, "${server.port}")
        val messages = mutableListOf<String>()
        suspend fun capture(block: suspend () -> Unit) {
            try { block(); fail("expected a failure") } catch (e: Exception) {
                messages += e.message.orEmpty()
                messages += e.toString()
            }
        }
        portal.profileStatus = 1
        portal.profileMsg = "Blocked 00:1A:79:00:00:01 user1"
        capture { client.authenticate(endpoint, secretCfg, null) }
        portal.profileStatus = 0
        portal.profileMsg = null
        portal.accessDenied = true
        capture { client.authenticate(endpoint, secretCfg, "TOKEN-PLACEHOLDER-1") }
        portal.accessDenied = false
        portal.loginRequired = true
        portal.expectedPassword = "other"
        capture { client.authenticate(endpoint, secretCfg, null) }
        portal.loginRequired = false
        portal.expectedPassword = "pass1"
        val s = client.authenticate(endpoint, secretCfg, null)
        portal.createLinkJson = """{"js":{"cmd":"","error":"limit"}}"""
        capture { client.createLink(s, secretCfg, "ffmpeg http://localhost/ch/1_") }
        portal.authFailAlways += "get_epg_info"
        capture { client.epg(s, secretCfg, 72) {} }
        capture { client.authenticate("$base/missing.php", secretCfg, null) }
        capture { client.discover("$base/nothing/c/", mac) }
        capture { StalkerClient(refusingHttp(AtomicInteger())).discover("$base/c/", mac) }
        portal.htmlPaths += "/stalker_portal/server/load.php"
        capture { client.watchdog(s, secretCfg) }
        for (m in messages) for (f in forbidden) assertFalse("'$m' contains '$f'", m.contains(f, ignoreCase = true))
        assertEquals(18, messages.size)
    }

    @Test fun blankIdentityFieldsNeverSent() = runTest {
        val blank = cfg().copy(serial = "", deviceId = " ", deviceId2 = "", signature = "\t", login = " ", password = "")
        client.authenticate(endpoint, blank, null)
        val p = portal.ofAction("get_profile").single().requestUrl!!
        for (k in listOf("device_id", "device_id2", "sn", "signature")) assertNull(k, p.queryParameter(k))
        for (r in portal.requests) assertFalse(r.getHeader("Cookie")!!, r.getHeader("Cookie")!!.contains("sn="))
        assertTrue(p.queryParameter("metrics")!!.contains("\"sn\":\"\""))
        // A blank login is no login: status 2 is an auth error, and do_auth never sends blank device ids.
        portal.loginRequired = true
        portal.requests.clear()
        try { client.authenticate(endpoint, blank, null); fail() } catch (_: StalkerAuthException) {}
        assertEquals(0, portal.count("do_auth"))
        client.authenticate(endpoint, blank.copy(login = "user1", password = "pass1"), null)
        val auth = portal.ofAction("do_auth").single().requestUrl!!
        assertNull(auth.queryParameter("device_id"))
        assertNull(auth.queryParameter("device_id2"))
    }

    @Test fun parallelAuthFailuresReHandshakeOnce() = runTest {
        val s = session()
        portal.unbindSession() // the portal keeps the same token, so the token can't tell waiters apart
        portal.profileDelayMillis = 200
        portal.requests.clear()
        (1..5).map { async { client.watchdog(s, cfg()) } }.awaitAll()
        assertEquals(1, portal.count("handshake"))
        assertEquals(1, portal.count("get_profile"))
        assertEquals("TOKEN-PLACEHOLDER-1", s.token)
        assertEquals(1, portal.maxProfilesInFlight.get())
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun reHandshakesForOneMacNeverOverlap() = runTest {
        val a = session()
        val b = session() // a second session object for the same MAC (e.g. a stale one)
        portal.unbindSession()
        portal.profileDelayMillis = 200
        portal.requests.clear()
        listOf(
            async { client.watchdog(a, cfg()) },
            async { client.watchdog(b, cfg()) },
            async { client.authenticate(endpoint, cfg(), "TOKEN-PLACEHOLDER-1"); Unit },
        ).awaitAll()
        assertEquals(1, portal.maxProfilesInFlight.get())
    }

    @Test fun reHandshakeRefreshesWatchdogInterval() = runTest {
        val s = session()
        assertEquals(60, s.watchdogSeconds)
        val before = s.generation
        portal.unbindSession()
        client.watchdog(s, cfg())
        assertEquals(before + 1, s.generation)
        assertEquals(60, s.watchdogSeconds)
    }

    @Test fun truncatedAllChannelsFallsBackToPaged() = runTest {
        val full = FakePortal.fixture("all_channels.json")
        portal.allChannelsJson = full.substring(0, full.indexOf("{\"id\":\"103\""))
        portal.pagedChannels = listOf(101, 102, 103).map { FakePortal.row(it) }
        val s = session()
        val got = mutableListOf<StalkerChannel>()
        client.channels(s, cfg()) { got += it }
        assertEquals(listOf("101", "102", "103"), got.map { it.id })
        assertEquals(listOf("1"), portal.ofAction("get_ordered_list").map { it.requestUrl!!.queryParameter("p") })
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun refusedAllChannelsIsNotRetriedAsPaged() = runTest {
        val s = session()
        val calls = AtomicInteger()
        val refusing = StalkerClient(refusingHttp(calls))
        try { refusing.channels(s, cfg()) {}; fail() } catch (e: StalkerUnreachableException) {
            assertEquals(StalkerUnreachableException.Kind.REFUSED, e.kind)
        }
    }

    @Test fun profileThatIsNotAnObjectIsProtocolError() = runTest {
        val odd = MockWebServer()
        odd.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setBody(
                    if (request.requestUrl!!.queryParameter("action") == "handshake") """{"js":{"token":"T"}}""" else """{"js":[]}""",
                )
        }
        odd.start()
        try {
            client.authenticate("http://${odd.hostName}:${odd.port}/server/load.php", cfg(), null); fail()
        } catch (_: StalkerProtocolException) {
        } finally {
            odd.shutdown()
        }
    }
}

/** Explicit timeouts so a loaded build machine doesn't trip the 10 s defaults. */
internal fun testHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

/** Every call fails as a refused connection, deterministically (no freed ports). */
internal fun refusingHttp(calls: AtomicInteger): OkHttpClient = testHttp().newBuilder()
    .addInterceptor { calls.incrementAndGet(); throw ConnectException("Failed to connect to localhost mac=00:1A:79:00:00:01") }
    .build()
