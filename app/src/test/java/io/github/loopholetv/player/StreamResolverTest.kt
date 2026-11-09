package io.github.loopholetv.player

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.SettingsSnapshot
import io.github.loopholetv.data.stalker.FakePortal
import io.github.loopholetv.data.stalker.StalkerClient
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
import java.io.IOException
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
    )

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
