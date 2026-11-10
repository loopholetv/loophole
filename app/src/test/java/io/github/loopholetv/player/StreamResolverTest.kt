package io.github.loopholetv.player

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.SettingsSnapshot
import io.github.loopholetv.data.stalker.FakePortal
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.xtream.XtreamClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.testHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineExceptionHandler
import io.github.loopholetv.data.settings.SETTINGS_FIRST_VALUE_TIMEOUT_MS
import io.github.loopholetv.player.catchup.ArchiveRequest
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Uses the [FakePortal] over MockWebServer. The resolver's own waits go through an injected
 * `delayMs`, recorded here (with the portal's create_link count at that moment) instead of slept.
 * `runBlocking`, not `runTest`: virtual time would fire the 8 s create_link timeout while real
 * MockWebServer I/O is in flight.
 */
class StreamResolverTest {
    private lateinit var server: MockWebServer
    private val portal = FakePortal()
    private val client = StalkerClient(testHttp(), timeZone = { "Europe/London" })
    private val playlists = mutableMapOf<Long, PlaylistEntity>()
    private var settings = AppSettings()
    private lateinit var sessions: StalkerSessions

    /** (ms, create_link calls so far) for every delayMs call. */
    private val delays = CopyOnWriteArrayList<Pair<Long, Int>>()
    private var delayHook: suspend (Long) -> Unit = {}
    private var deviceZone: ZoneId = ZoneId.of("UTC")

    @Before fun setUp() {
        server = MockWebServer().also { it.dispatcher = portal; it.start() }
        playlists[1] = m3u(1)
        playlists[2] = portalPlaylist(2)
        sessions = StalkerSessions(client, { playlists[it] }) { id, e, t ->
            playlists[id] = playlists.getValue(id).copy(portalEndpoint = e, portalToken = t)
        }
    }

    @After fun tearDown() { server.shutdown() }

    private val base get() = "http://${server.hostName}:${server.port}"

    private fun resolver() = PlaylistStreamResolver(
        playlist = { playlists[it] },
        settings = { settings },
        defaultUa = "Loophole/0.3.0",
        sessions = sessions,
        client = client,
        delayMs = { ms -> delays += ms to portal.count("create_link"); delayHook(ms) },
        zone = { deviceZone },
        xtream = XtreamClient(testHttp()),
        storeArchiveInfo = { id, zone, ext -> stored += Triple(id, zone, ext); playlists[id] = playlists.getValue(id).copy(xtreamTimeZone = zone, xtreamArchiveExt = ext) },
    )

    private val stored = CopyOnWriteArrayList<Triple<Long, String?, String?>>()

    private fun xtreamLoginJson(zone: String) =
        """{"user_info":{"auth":1,"status":"Active","max_connections":"1","allowed_output_formats":["m3u8"]},"server_info":{"timezone":"$zone"}}"""

    private fun m3u(id: Long, ua: String? = null) = PlaylistEntity(
        id = id, name = "List", type = PlaylistType.M3U, url = "http://example.test/list.m3u", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
        lastError = null, userAgent = ua,
    )

    private fun portalPlaylist(id: Long) = PlaylistEntity(
        id = id, name = "Portal", type = PlaylistType.STALKER, url = "$base/stalker_portal/c/", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
        lastError = null, mac = "00:1A:79:00:00:01",
    )

    private fun channel(
        playlistId: Long = 1, url: String = "http://example.test/live/1.ts", ua: String? = null,
        referrer: String? = null, needsLink: Boolean = false,
    ) = ChannelEntity(
        id = 1, playlistId = playlistId, key = "k1", number = 1, name = "One", streamUrl = url, logo = null,
        groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null, userAgent = ua,
        referrer = referrer, catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = null,
        needsLink = needsLink,
    )

    private val archiveFrom = Instant.parse("2026-11-10T20:00:00Z")
    private val archiveRequest = ArchiveRequest(archiveFrom, archiveFrom.plusSeconds(3600))
    private val archiveNow = Instant.parse("2026-11-10T22:30:00Z")

    private fun xtreamPlaylist(id: Long, zone: String? = "Europe/London", ext: String? = "m3u8") = PlaylistEntity(
        id = id, name = "X", type = PlaylistType.XTREAM, url = null, xtreamServer = "http://example.test:8080",
        xtreamUser = "archiveuser", xtreamPass = "archivepass", epgUrlsFromSource = "", manualEpgUrl = null,
        lastRefreshedAt = null, lastError = null, xtreamTimeZone = zone, xtreamArchiveExt = ext,
    )

    private fun archiveChannel(
        playlistId: Long, key: String = "42", url: String = "http://example.test:8080/live/archiveuser/archivepass/42.ts", type: String? = "xc",
        days: Int? = 3, source: String? = null, correction: Int = 0, ua: String? = null, referrer: String? = null,
    ) = channel(playlistId = playlistId, url = url, ua = ua, referrer = referrer)
        .copy(key = key, catchupType = type, catchupDays = days, catchupSource = source, catchupCorrectionMinutes = correction)

    @Test fun xtreamArchiveUrlFromPlaylist() = runBlocking {
        playlists[3] = xtreamPlaylist(3)
        val s = resolver().resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertEquals("http://example.test:8080/timeshift/archiveuser/archivepass/60/2026-11-10:20-00/42.m3u8", s.url)
    }

    @Test fun airingProgrammeIsAskedForUpToItsStop() = runBlocking {
        playlists[3] = xtreamPlaylist(3, zone = "UTC")
        // Watch from start, ten minutes into a programme still airing: one request to its stop.
        val tenMinutesIn = archiveFrom.plusSeconds(600)
        val s = resolver().resolveArchive(archiveChannel(3), archiveRequest, tenMinutesIn)!!
        assertEquals("http://example.test:8080/timeshift/archiveuser/archivepass/60/2026-11-10:20-00/42.m3u8", s.url)

        val xc = archiveChannel(1, url = "http://example.test:8080/live/u/p/42.ts", type = "xc")
        assertTrue(resolver().resolveArchive(xc, archiveRequest, tenMinutesIn)!!.url.contains("/60/"))
    }

    @Test fun airingTemplateArchiveRunsToStopUnlessItReadsNow() = runBlocking {
        val tenMinutesIn = archiveFrom.plusSeconds(600)
        val stop = archiveRequest.until.epochSecond
        val ends = archiveChannel(1, url = "http://example.test/live/1.ts", type = "append", source = "?d={duration}&e={utcend}")
        assertEquals("http://example.test/live/1.ts?d=3600&e=$stop", resolver().resolveArchive(ends, archiveRequest, tenMinutesIn)!!.url)
        // A template that also reads the current time keeps it true.
        val relative = archiveChannel(1, url = "http://example.test/live/1.ts", type = "append", source = "?o={offset}")
        assertEquals("http://example.test/live/1.ts?o=600", resolver().resolveArchive(relative, archiveRequest, tenMinutesIn)!!.url)
    }

    @Test fun xtreamArchiveDefaultsToTsAndDeviceZone() = runBlocking {
        playlists[3] = xtreamPlaylist(3, zone = null, ext = null)
        deviceZone = ZoneId.of("Asia/Tokyo")
        val s = resolver().resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertEquals("http://example.test:8080/timeshift/archiveuser/archivepass/60/2026-11-11:05-00/42.ts", s.url)
    }

    private fun loginPlaylist(id: Long) = xtreamPlaylist(id, zone = null, ext = null).copy(xtreamServer = base)

    private fun serveLogin(body: () -> MockResponse): CopyOnWriteArrayList<String> {
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { paths += request.path.orEmpty(); return body() }
        }
        return paths
    }

    @Test fun nullZoneLearnsServerZoneOnceAndStoresIt() = runBlocking {
        val paths = serveLogin { MockResponse().setBody(xtreamLoginJson("Asia/Tokyo")) }
        playlists[3] = loginPlaylist(3)
        deviceZone = ZoneId.of("UTC")
        val r = resolver()
        val first = r.resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertEquals(1, paths.size)
        assertTrue(first.url, "/2026-11-11:05-00/42.m3u8" in first.url)
        assertEquals(listOf(Triple(3L, "Asia/Tokyo", "m3u8")), stored.toList())
        val second = r.resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertEquals(1, paths.size)
        assertEquals(first.url, second.url)
    }

    @Test fun failedLoginFallsBackToDeviceZoneWithoutRetry() = runBlocking {
        val paths = serveLogin { MockResponse().setResponseCode(500) }
        playlists[3] = loginPlaylist(3)
        deviceZone = ZoneId.of("Asia/Tokyo")
        val r = resolver()
        val s = r.resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertTrue(s.url, "/2026-11-11:05-00/42.ts" in s.url)
        r.resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertEquals(1, paths.size)
        assertTrue(stored.isEmpty())
    }

    @Test fun archiveUsesShownTimes() = runBlocking {
        playlists[3] = xtreamPlaylist(3, zone = "+02:00")
        val shifted = archiveChannel(3).copy(epgShiftMinutes = 60)
        val s = resolver().resolveArchive(shifted, archiveRequest, archiveNow)!!
        assertTrue(s.url, "/2026-11-10:22-00/" in s.url)
    }

    @Test fun m3uArchiveAppliesCorrection() = runBlocking {
        val ch = archiveChannel(1, url = "http://example.test/live/1.ts", type = "append", source = "?t={Y}{m}{d}{H}", correction = 60)
        val s = resolver().resolveArchive(ch, archiveRequest, archiveNow)!!
        assertEquals("http://example.test/live/1.ts?t=2026111019", s.url)
        val def = archiveChannel(1, url = "http://example.test/live/1.ts", type = "default", source = "http://example.test/a/{Y}{m}{d}{H}", correction = 60)
        assertEquals("http://example.test/a/2026111019", resolver().resolveArchive(def, archiveRequest, archiveNow)!!.url)
    }

    @Test fun parsedPlaylistCorrectionShiftsArchiveTimeBack() = runBlocking {
        val playlist = "#EXTM3U\n#EXTINF:-1 catchup=\"append\" catchup-days=\"3\" catchup-source=\"?t={H}\" catchup-correction=\"+1\",One\nhttp://example.test/live/1.ts\n"
        var entry: io.github.loopholetv.data.m3u.M3uEntry? = null
        io.github.loopholetv.data.m3u.M3uParser.parse(playlist.byteInputStream()) { entry = it }
        val e = entry!!
        assertEquals(60, e.catchupCorrectionMinutes)
        val ch = channel(url = e.url).copy(
            catchupType = e.catchup, catchupDays = e.catchupDays, catchupSource = e.catchupSource,
            catchupCorrectionMinutes = e.catchupCorrectionMinutes,
        )
        assertEquals("http://example.test/live/1.ts?t=19", resolver().resolveArchive(ch, archiveRequest, archiveNow)!!.url)
    }

    @Test fun stalkerAndNoCatchupResolveNull() = runBlocking {
        val r = resolver()
        assertEquals(null, r.resolveArchive(archiveChannel(2), archiveRequest, archiveNow))
        assertEquals(null, r.resolveArchive(archiveChannel(1, url = "http://example.test/live/1.ts", type = null, days = null), archiveRequest, archiveNow))
        assertTrue(delays.isEmpty())
    }

    @Test fun archiveGoesThroughUdpProxyAndUserAgent() = runBlocking {
        playlists[1] = m3u(1, ua = "PlaylistUA")
        settings = AppSettings(udpProxy = "192.168.1.1:4022")
        val udp = archiveChannel(1, url = "udp://@239.0.0.1:1234", type = "append", source = "?t={H}", referrer = "http://ref.example.test/")
        val s = resolver().resolveArchive(udp, archiveRequest, archiveNow)!!
        assertTrue(s.url, s.url.startsWith("http://192.168.1.1:4022/udp/239.0.0.1:1234"))
        assertEquals("PlaylistUA", s.userAgent)
        assertEquals(mapOf("Referer" to "http://ref.example.test/"), s.headers)
        val own = resolver().resolveArchive(archiveChannel(1, url = "http://example.test/live/1.ts", type = "append", source = "?t={H}", ua = "ChannelUA"), archiveRequest, archiveNow)!!
        assertEquals("ChannelUA", own.userAgent)
    }

    @Test fun invalidZoneFallsBackToDevice() = runBlocking {
        playlists[3] = xtreamPlaylist(3, zone = "Mars/Base")
        deviceZone = ZoneId.of("Asia/Tokyo")
        val s = resolver().resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!
        assertTrue(s.url, "/2026-11-11:05-00/" in s.url)
    }

    @Test fun archiveStreamToStringRedacted() = runBlocking {
        playlists[3] = xtreamPlaylist(3)
        val text = resolver().resolveArchive(archiveChannel(3), archiveRequest, archiveNow)!!.toString()
        assertFalse(text, "archiveuser" in text || "archivepass" in text || "timeshift" in text)
        playlists[1] = m3u(1)
        val tokenised = archiveChannel(1, type = "shift", url = "http://example.test/live/1.ts?token=sekret")
        val t2 = resolver().resolveArchive(tokenised, archiveRequest, archiveNow)!!
        assertTrue(t2.url, "utc=" in t2.url)
        assertFalse(t2.toString().contains("sekret"))
    }

    @Test fun uaPrecedence() = runBlocking {
        val r = resolver()
        playlists[1] = m3u(1, ua = "PlaylistUA")
        settings = AppSettings(userAgent = "GeneralUA")
        assertEquals("ChannelUA", r.resolve(channel(ua = "ChannelUA")).userAgent)
        assertEquals("PlaylistUA", r.resolve(channel(ua = " ")).userAgent)
        playlists[1] = m3u(1, ua = null)
        assertEquals("GeneralUA", r.resolve(channel()).userAgent)
        settings = AppSettings(userAgent = null)
        val plain = r.resolve(channel(referrer = "http://ref.example.test/"))
        assertEquals("Loophole/0.3.0", plain.userAgent)
        assertEquals(mapOf("Referer" to "http://ref.example.test/"), plain.headers)
        assertEquals("http://example.test/live/1.ts", plain.url)
        assertEquals(emptyMap<String, String>(), r.resolve(channel(referrer = "")).headers)
        assertTrue(delays.isEmpty())
    }

    @Test fun udpUrlRewrittenThroughProxy() = runBlocking {
        val r = resolver()
        val udp = channel(url = "udp://@239.0.0.1:1234")
        assertEquals("udp://@239.0.0.1:1234", r.resolve(udp).url)
        settings = AppSettings(udpProxy = "192.168.1.1:4022")
        assertEquals("http://192.168.1.1:4022/udp/239.0.0.1:1234", r.resolve(udp).url)
        assertEquals("http://example.test/live/1.ts", r.resolve(channel()).url)
        // The stored URL stays raw.
        assertEquals("udp://@239.0.0.1:1234", udp.streamUrl)
    }

    @Test fun stalkerStaticCmdNeedsNoLink() = runBlocking {
        val r = resolver()
        val resolved = r.resolve(channel(playlistId = 2, url = "ffmpeg http://edge.example.test/live/9.ts", needsLink = false))
        assertEquals("http://edge.example.test/live/9.ts", resolved.url)
        assertEquals(0, portal.count("create_link"))
        assertEquals(listOf(STALKER_ZAP_DEBOUNCE_MS to 0), delays.toList())
    }

    @Test fun stalkerLinkMintedAfterDebounce() = runBlocking {
        val r = resolver()
        val resolved = r.resolve(channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true))
        assertEquals("http://edge.example.test/live/abc/101.ts?play_token=PLAYTOKEN1", resolved.url)
        // The debounce ran before any create_link.
        assertEquals(listOf(STALKER_ZAP_DEBOUNCE_MS to 0), delays.toList())
        assertEquals(1, portal.count("create_link"))
        assertEquals("ffmpeg http://localhost/ch/101", portal.ofAction("create_link").single().requestUrl!!.queryParameter("cmd"))
        // Never cached: a second play mints again.
        r.resolve(channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true))
        assertEquals(2, portal.count("create_link"))
    }

    @Test fun stalkerLinkGoesThroughProxyToo() = runBlocking {
        settings = AppSettings(udpProxy = "10.0.0.1")
        portal.createLinkJson = """{"js":{"id":"101","cmd":"ffmpeg udp://@239.1.1.1:5000","error":""}}"""
        val resolved = resolver().resolve(channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true))
        assertEquals("http://10.0.0.1:4022/udp/239.1.1.1:5000", resolved.url)
    }

    @Test fun limitRetriesOnceAfterDelay() = runBlocking {
        val limit = """{"js":{"id":"101","cmd":"","error":"limit"}}"""
        val ok = portal.createLinkJson
        portal.createLinkJson = limit
        // After the first (limited) attempt, the portal frees a slot.
        delayHook = { ms -> if (ms == STALKER_LIMIT_RETRY_MS) portal.createLinkJson = ok }
        val ch = channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true)
        val resolved = resolver().resolve(ch)
        assertEquals("http://edge.example.test/live/abc/101.ts?play_token=PLAYTOKEN1", resolved.url)
        assertEquals(listOf(STALKER_ZAP_DEBOUNCE_MS to 0, STALKER_LIMIT_RETRY_MS to 1), delays.toList())
        assertEquals(2, portal.count("create_link"))

        // Limited twice → StalkerLinkException("limit"), no third attempt.
        delays.clear()
        portal.createLinkJson = limit
        delayHook = {}
        try {
            resolver().resolve(ch)
            fail("expected StalkerLinkException")
        } catch (e: StalkerLinkException) {
            assertEquals("limit", e.code)
        }
        assertEquals(4, portal.count("create_link"))
        assertEquals(listOf(STALKER_ZAP_DEBOUNCE_MS to 2, STALKER_LIMIT_RETRY_MS to 3), delays.toList())
    }

    @Test fun otherLinkErrorsAreNotRetried() = runBlocking {
        portal.createLinkJson = """{"js":{"id":"101","cmd":"","error":"nothing_to_play"}}"""
        try {
            resolver().resolve(channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true))
            fail("expected StalkerLinkException")
        } catch (e: StalkerLinkException) {
            assertEquals("nothing_to_play", e.code)
        }
        assertEquals(1, portal.count("create_link"))
    }

    @Test fun keepAlivePingsUntilCancelled() = runBlocking {
        val r = resolver()
        val ch = channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true)
        val pings = { portal.requests.count { it.requestUrl?.queryParameter("action") == "get_events" && it.requestUrl?.queryParameter("init") == "0" } }
        val waits = CopyOnWriteArrayList<Long>()
        delayHook = { ms -> waits += ms; if (waits.size >= 3) awaitCancellation() }
        val job = launch(Dispatchers.Default) { r.keepAlive(ch) }
        withTimeout(10_000) { while (waits.size < 3) yield() }
        assertEquals(2, pings())
        // The session's watchdog_timeout (60 s in the fixture profile).
        assertEquals(listOf(60_000L, 60_000L, 60_000L), waits.toList())
        job.cancelAndJoin()
        assertEquals(2, pings())
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun keepAliveDoesNothingForOtherPlaylists() = runBlocking {
        resolver().keepAlive(channel())
        assertTrue(delays.isEmpty())
        assertTrue(portal.requests.isEmpty())
    }

    @Test fun canRemintOnlyForPortals() = runBlocking {
        val r = resolver()
        assertTrue(r.canRemint(channel(playlistId = 2, needsLink = true)))
        // A static portal row would only re-resolve to the same URL.
        assertFalse(r.canRemint(channel(playlistId = 2, needsLink = false)))
        assertFalse(r.canRemint(channel(playlistId = 1)))
        assertFalse(r.canRemint(channel(playlistId = 99)))
        assertFalse(DirectResolver("Loophole/x").canRemint(channel()))
    }

    @Test fun resolveThenCanRemintLooksUpThePlaylistOnce() = runBlocking {
        var lookups = 0
        val r = PlaylistStreamResolver({ lookups++; playlists[it] }, { settings }, "Loophole/0.3.0", sessions, client,
            delayMs = {})
        val ch = channel(playlistId = 2, url = "ffmpeg http://localhost/ch/101", needsLink = true)
        r.resolve(ch)
        assertTrue(r.canRemint(ch))
        assertEquals(1, lookups)
        // The memo is consumed: a later check looks up again.
        assertTrue(r.canRemint(ch))
        assertEquals(2, lookups)
    }

    @Test fun directResolverKeepsTodaysBehaviour() = runBlocking {
        val d = DirectResolver("Loophole/x")
        assertEquals(ResolvedStream("http://example.test/live/1.ts", "Loophole/x", emptyMap()), d.resolve(channel()))
        assertEquals(
            ResolvedStream("udp://@239.0.0.1:1", "UA", mapOf("Referer" to "http://r.example.test/")),
            d.resolve(channel(url = "udp://@239.0.0.1:1", ua = "UA", referrer = "http://r.example.test/")),
        )
    }

    @Test fun settingsSnapshotDefaultsThenFollowsFlow() = runTest {
        val flow = MutableStateFlow(AppSettings(userAgent = "A"))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            assertEquals(AppSettings(), SettingsSnapshot(emptyFlow(), scope).current)
            val snapshot = SettingsSnapshot(flow, scope)
            assertEquals("A", snapshot.current.userAgent)
            flow.value = AppSettings(udpProxy = "1.2.3.4")
            assertEquals("1.2.3.4", snapshot.current.udpProxy)
        } finally {
            scope.cancel()
        }
    }

    @Test fun firstResolveWaitsForLateSettings() = runBlocking {
        // DataStore's first value arrives 300 ms after the snapshot is created (process start).
        val late = flow {
            kotlinx.coroutines.delay(300)
            emit(AppSettings(userAgent = "GeneralUA", udpProxy = "192.168.1.1:4022"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snapshot = SettingsSnapshot(late, scope)
            assertEquals(AppSettings(), snapshot.current)
            val r = PlaylistStreamResolver({ playlists[it] }, { snapshot.await() }, "Loophole/0.3.0", sessions, client)
            val resolved = r.resolve(channel(url = "udp://@239.0.0.1:1234"))
            assertEquals("http://192.168.1.1:4022/udp/239.0.0.1:1234", resolved.url)
            assertEquals("GeneralUA", resolved.userAgent)
        } finally {
            scope.cancel()
        }
    }

    @Test fun snapshotAwaitGivesDefaultsAfterOneSecond() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val snapshot = SettingsSnapshot(flow { awaitCancellation() }, scope)
            val start = testScheduler.currentTime
            assertEquals(AppSettings(), snapshot.await())
            assertEquals(SETTINGS_FIRST_VALUE_TIMEOUT_MS, testScheduler.currentTime - start)
        } finally {
            scope.cancel()
        }
    }

    @Test fun snapshotSurvivesDataStoreError() = runTest {
        val crashes = CopyOnWriteArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> crashes += e }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + handler)
        try {
            val broken = SettingsSnapshot(flow { throw IOException("datastore") }, scope)
            assertEquals(AppSettings(), broken.await())
            assertEquals(0L, testScheduler.currentTime) // ready at once, no 1 s wait
            val afterValue = SettingsSnapshot(
                flow { emit(AppSettings(userAgent = "A")); throw IOException("datastore") }, scope,
            )
            assertEquals("A", afterValue.await().userAgent) // the last good value is kept
            assertTrue(crashes.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
