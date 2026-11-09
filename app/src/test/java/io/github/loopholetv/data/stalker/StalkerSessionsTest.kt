package io.github.loopholetv.data.stalker

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class StalkerSessionsTest {
    private lateinit var server: MockWebServer
    private val portal = FakePortal()
    private val client = StalkerClient(testHttp(), timeZone = { "Europe/London" })
    private val rows = ConcurrentHashMap<Long, PlaylistEntity>()
    private val saves = CopyOnWriteArrayList<Triple<Long, String, String>>()
    private lateinit var sessions: StalkerSessions

    @Before fun setUp() {
        server = MockWebServer().also { it.dispatcher = portal; it.start() }
        rows[1] = playlist(endpoint = null, token = null)
        sessions = StalkerSessions(client, { rows[it] }) { id, e, t ->
            saves += Triple(id, e, t)
            rows[id] = rows.getValue(id).copy(portalEndpoint = e, portalToken = t)
        }
    }

    @After fun tearDown() { server.shutdown() }

    private val base get() = "http://${server.hostName}:${server.port}"
    private val endpoint get() = "$base/stalker_portal/server/load.php"

    private fun playlist(endpoint: String?, token: String?) = PlaylistEntity(
        id = 1, name = "Portal", type = PlaylistType.STALKER, url = "$base/stalker_portal/c/", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
        lastError = null, mac = "00:1A:79:00:00:01", portalEndpoint = endpoint, portalToken = token,
    )

    @Test fun tokenPersistedViaSave() = runTest {
        val token = sessions.withSession(1) { s, cfg ->
            assertEquals("00:1A:79:00:00:01", cfg.mac)
            assertEquals(endpoint, s.endpoint)
            s.token
        }
        assertEquals("TOKEN-PLACEHOLDER-1", token)
        assertEquals(listOf(Triple(1L, endpoint, "TOKEN-PLACEHOLDER-1")), saves.toList())
        // Reused in memory: no second handshake, no second save.
        sessions.withSession(1) { _, _ -> }
        assertEquals(1, portal.count("handshake"))
        assertEquals(1, saves.size)
    }

    @Test fun savedEndpointAndTokenReusedWithoutProbe() = runTest {
        rows[1] = playlist(endpoint, "TOKEN-PLACEHOLDER-1")
        sessions.withSession(1) { _, _ -> }
        assertEquals(0, portal.count("get_genres"))
        assertEquals("TOKEN-PLACEHOLDER-1", portal.ofAction("handshake").single().requestUrl!!.queryParameter("token"))
        assertTrue(saves.isEmpty())
    }

    @Test fun staleSavedEndpointIsReprobedOnce() = runTest {
        rows[1] = playlist("$base/old/server/load.php", "TOKEN-PLACEHOLDER-1")
        sessions.withSession(1) { s, _ -> assertEquals(endpoint, s.endpoint) }
        assertEquals(endpoint, saves.single().second)
    }

    @Test fun sessionsSingleFlight() = runTest {
        portal.profileDelayMillis = 300
        val results = (1..2).map { async { sessions.withSession(1) { s, _ -> s } } }.awaitAll()
        assertSame(results[0], results[1])
        assertEquals(1, portal.count("handshake"))
        assertEquals(1, portal.count("get_profile"))
    }

    @Test fun blockDoesNotHoldSessionLock() = runTest {
        sessions.withSession(1) { _, _ -> }
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val long = async { sessions.withSession(1) { _, _ -> started.complete(Unit); release.await(); "import" } }
        started.await()
        val quick = sessions.withSession(1) { _, _ -> "mint" }
        assertEquals("mint", quick)
        release.complete(Unit)
        assertEquals("import", long.await())
    }

    @Test fun authFailureInBlockInvalidatesAndRetriesOnce() = runTest {
        sessions.withSession(1) { _, _ -> }
        var calls = 0
        val result = sessions.withSession(1) { s, _ ->
            calls++
            if (calls == 1) throw StalkerAuthException("stale", retryable = true)
            s.token
        }
        assertEquals(2, calls)
        assertEquals("TOKEN-PLACEHOLDER-1", result)
        assertEquals(2, portal.count("handshake"))
        assertEquals(2, portal.count("get_profile"))
        var always = 0
        try {
            sessions.withSession(1) { _, _ -> always++; throw StalkerAuthException("dead", retryable = true) }
            fail()
        } catch (_: StalkerAuthException) {}
        assertEquals(2, always)
        assertEquals(3, portal.count("handshake"))
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun terminalAuthFailureIsNotRetried() = runTest {
        sessions.withSession(1) { _, _ -> }
        var calls = 0
        try {
            sessions.withSession(1) { _, _ -> calls++; throw StalkerAuthException("The portal has turned this account off") }
            fail()
        } catch (_: StalkerAuthException) {}
        assertEquals(1, calls)
        assertEquals(1, portal.count("handshake"))
    }

    @Test fun blockRetryAndInCallRetryShareOneReHandshake() = runTest {
        sessions.withSession(1) { _, _ -> }
        portal.unbindSession() // same token comes back: only the generation tells callers apart
        portal.profileDelayMillis = 200
        portal.requests.clear()
        var aCalls = 0
        listOf(
            async {
                sessions.withSession(1) { _, _ ->
                    aCalls++
                    if (aCalls == 1) throw StalkerAuthException("stale", retryable = true)
                }
            },
            async { sessions.withSession(1) { s, cfg -> client.watchdog(s, cfg) } },
        ).awaitAll()
        assertEquals(1, portal.count("handshake"))
        assertEquals(1, portal.count("get_profile"))
        assertEquals(1, portal.maxProfilesInFlight.get())
    }

    @Test fun acquisitionAndInCallRetryNeverOverlap() = runTest {
        val old = sessions.withSession(1) { s, _ -> s }
        sessions.invalidate(1)
        portal.unbindSession()
        portal.profileDelayMillis = 200
        listOf(
            async { client.watchdog(old, rows.getValue(1).portalConfig()) }, // in-call re-handshake on a stale session
            async { sessions.withSession(1) { _, _ -> } },                    // a fresh acquisition
        ).awaitAll()
        assertEquals(1, portal.maxProfilesInFlight.get())
    }

    @Test fun invalidateDuringAcquisitionIsNotUndone() = runTest {
        portal.profileDelayMillis = 300
        val first = async { sessions.withSession(1) { s, _ -> s } }
        while (portal.count("get_profile") == 0) kotlinx.coroutines.yield().also { Thread.sleep(5) }
        sessions.invalidate(1)
        val s1 = first.await()
        val s2 = sessions.withSession(1) { s, _ -> s }
        org.junit.Assert.assertNotSame(s1, s2)
    }

    @Test fun reHandshakeInsideBlockIsSavedOnNextAcquisitionNotInsideBlock() = runTest {
        sessions.withSession(1) { _, _ -> }
        portal.expireToken()
        sessions.withSession(1) { s, cfg ->
            client.watchdog(s, cfg) // fails once, re-handshakes, updates s in place
            assertEquals("TOKEN-PLACEHOLDER-2", s.token)
            assertEquals(1, saves.size) // Nothing persisted from inside block
        }
        sessions.withSession(1) { _, _ -> }
        assertEquals(Triple(1L, endpoint, "TOKEN-PLACEHOLDER-2"), saves.last())
    }

    /** Waits (real time) until the acquisition in flight has reached get_profile. */
    private suspend fun awaitProfileRequest() {
        while (portal.count("get_profile") == 0) kotlinx.coroutines.yield().also { Thread.sleep(5) }
    }

    @Test fun sourceEditDuringAcquisitionDoesNotGetTheOldTokenSaved() = runTest {
        // Settings edits the portal (setSource clears the session) while a play-time
        // acquisition is in flight; the old portal's endpoint and token must not land on the edited row.
        portal.profileDelayMillis = 300
        val first = async { sessions.withSession(1) { s, _ -> s } }
        awaitProfileRequest()
        rows[1] = rows.getValue(1).copy(mac = "00:1A:79:00:00:02", portalEndpoint = null, portalToken = null)
        first.await()
        assertTrue(saves.isEmpty())
        assertEquals(null, rows.getValue(1).portalToken)
    }

    @Test fun invalidateDuringAcquisitionSkipsTheSave() = runTest {
        portal.profileDelayMillis = 300
        val first = async { sessions.withSession(1) { s, _ -> s } }
        awaitProfileRequest()
        sessions.invalidate(1)
        first.await()
        assertTrue(saves.isEmpty())
    }

    @Test fun invalidateForcesNewHandshake() = runTest {
        sessions.withSession(1) { _, _ -> }
        sessions.invalidate(1)
        sessions.withSession(1) { _, _ -> }
        assertEquals(2, portal.count("handshake"))
    }
}
