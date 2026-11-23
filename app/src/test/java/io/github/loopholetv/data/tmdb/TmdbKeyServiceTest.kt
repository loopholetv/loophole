package io.github.loopholetv.data.tmdb

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.work.TmdbScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TmdbKeyServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val v3 = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    private var kicks = 0

    private class Parts(val service: TmdbKeyService, val store: SettingsStore)

    private fun TestScope.parts(): Parts {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = backgroundScope,
            ) { File(tmp.root, "settings.preferences_pb") },
        )
        lateinit var service: TmdbKeyService
        val client = TmdbClient(OkHttpClient(), baseUrl = server.url("/3/"), key = { service.key() })
        service = TmdbKeyService(store, client, TmdbScheduler { kicks++ })
        return Parts(service, store)
    }

    private fun respond(code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
    }

    @Test fun savingTrimsKey() = runTest {
        val p = parts()
        respond()
        assertEquals(KeyStatus.WORKING, p.service.save("  $v3\n"))
        assertEquals(v3, p.store.current().tmdbKey)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("api_key") == v3)
        assertEquals("cdef", p.service.last4())
    }

    @Test fun unknownShapeIsBadWithoutRequest() = runTest {
        val p = parts()
        assertEquals(KeyStatus.BAD_KEY, p.service.save("not a key"))
        assertEquals(KeyStatus.BAD_KEY, p.service.status.value)
        assertEquals(0, server.requestCount)
        assertEquals("", p.store.current().tmdbKey)
        assertFalse(p.service.hasKey())
        assertEquals(0, kicks)
    }

    @Test fun workingKeyKicksSync() = runTest {
        val p = parts()
        respond()
        assertEquals(KeyStatus.WORKING, p.service.save(v3))
        assertEquals(KeyStatus.WORKING, p.service.status.value)
        assertEquals("/3/configuration", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(1, kicks)
        assertTrue(p.service.hasKey())
    }

    @Test fun badKeyFromTmdbIsNotAcceptedAndDoesNotKick() = runTest {
        val p = parts()
        respond(401)
        assertEquals(KeyStatus.BAD_KEY, p.service.save(v3))
        assertEquals(0, kicks)
        assertFalse(p.service.hasKey())
    }

    @Test fun unreachableKeepsKey() = runTest {
        val p = parts()
        respond(503)
        assertEquals(KeyStatus.UNREACHABLE, p.service.save(v3))
        assertEquals(v3, p.store.current().tmdbKey)
        assertTrue(p.service.hasKey())
        assertEquals(0, kicks)
    }

    @Test fun removeClearsAndStatusNone() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        p.service.remove()
        assertEquals("", p.store.current().tmdbKey)
        assertEquals(KeyStatus.NONE, p.service.status.value)
        assertNull(p.service.last4())
        assertFalse(p.service.hasKey())
        assertNull(p.service.key())
    }

    @Test fun rejectedKeyStopsLookupsUntilChanged() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        assertTrue(p.service.hasKey())

        // The background lookup got a 401 for this key.
        p.service.markBadKey()
        assertEquals(KeyStatus.BAD_KEY, p.service.status.value)
        assertFalse(p.service.hasKey())
        assertNull(p.service.key())

        // Opening the page or saving the same key again makes no request.
        p.service.recheck()
        assertEquals(KeyStatus.BAD_KEY, p.service.save(v3))
        assertEquals(KeyStatus.BAD_KEY, p.service.status.value)
        assertEquals(1, server.requestCount)

        // A different key is checked and used.
        respond()
        assertEquals(KeyStatus.WORKING, p.service.save(other))
        assertTrue(p.service.hasKey())
        assertEquals(other, p.service.key())
        assertEquals(2, server.requestCount)
    }

    @Test fun removingARejectedKeyForgetsTheRejection() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        p.service.markBadKey()
        p.service.remove()
        respond()
        assertEquals(KeyStatus.WORKING, p.service.save(v3))
        assertTrue(p.service.hasKey())
    }

    @Test fun removingTheKeyMidRunShowsNoStatus() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        // A lookup was running when the key was removed; its next request finds no key.
        assertNotNull(p.service.key())
        p.service.remove()
        p.service.markBadKey()
        assertEquals(KeyStatus.NONE, p.service.status.value)

        // Nothing was recorded against the old key.
        respond()
        assertEquals(KeyStatus.WORKING, p.service.save(v3))
        assertTrue(p.service.hasKey())
    }

    @Test fun recheckChecksAStoredKey() = runTest {
        val p = parts()
        p.service.recheck()
        assertEquals(KeyStatus.NONE, p.service.status.value)
        assertEquals(0, server.requestCount)

        p.store.update { it.copy(tmdbKey = v3) }
        respond(503)
        p.service.recheck()
        assertEquals(KeyStatus.UNREACHABLE, p.service.status.value)
        respond()
        p.service.recheck()
        assertEquals(KeyStatus.WORKING, p.service.status.value)
        assertEquals(1, kicks)
    }

    @Test fun savingBlankRemovesTheKey() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        assertEquals(KeyStatus.NONE, p.service.save("   "))
        assertEquals("", p.store.current().tmdbKey)
        assertEquals(1, server.requestCount)
    }

    @Test fun toStringHasNoKey() = runTest {
        val p = parts()
        respond()
        p.service.save(v3)
        assertFalse(p.service.toString().contains(v3))
    }
}
