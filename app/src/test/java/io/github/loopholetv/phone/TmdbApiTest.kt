package io.github.loopholetv.phone

import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.tmdb.KeyStatus
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbApiTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = InMemoryPairingStore()
    private val pairing = PhonePairing(store, System::currentTimeMillis)
    private val http = LocalHttpServer(scope, maxPerRemote = 4)
    private val client = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val phone = PhoneServer(
        scope = scope,
        server = http,
        pairing = pairing,
        store = store,
        lanAddress = { loopback },
        watchNetwork = { AutoCloseable { } },
        asset = { null },
        tvName = { "Living room" },
    )

    @Volatile private var stored: String? = null

    @Volatile private var status = KeyStatus.NONE

    @Volatile private var saveResult = KeyStatus.WORKING
    private val rechecks = java.util.concurrent.atomic.AtomicInteger()

    private val api = TmdbApi(
        last4 = { stored?.takeLast(4) },
        status = { status },
        save = { raw ->
            val k = raw.trim()
            stored = k.ifEmpty { null }
            status = if (k.isEmpty()) KeyStatus.NONE else saveResult
            status
        },
        remove = {
            stored = null
            status = KeyStatus.NONE
        },
        recheck = {
            rechecks.incrementAndGet()
            status = KeyStatus.WORKING
        },
    )

    private var port = 0

    @Before fun setUp() {
        api.register(phone)
        phone.onForeground(true)
        port = runBlocking { withTimeout(5_000) { phone.address.first { it != null }!!.port } }
        pairing.open()
        send("POST", "/api/pair", """{"code":"${pairing.code.value}"}""").use { assertEquals(200, it.code) }
    }

    @After fun tearDown() {
        phone.onForeground(false)
        http.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun send(method: String, path: String, body: String? = null, marker: Boolean = true, plain: Boolean = false) =
        client.newCall(
            Request.Builder().url(url(path)).apply {
                val type = (if (plain) "text/plain" else "application/json").toMediaType()
                when {
                    body != null -> method(method, body.toRequestBody(type))
                    method == "GET" -> get()
                    else -> method(method, "".toRequestBody(type))
                }
                if (marker) header("X-Loophole", "1")
            }.build(),
        ).execute()

    private fun call(method: String, body: String? = null, plain: Boolean = false): Pair<Int, String> =
        send(method, "/api/tmdb", body, plain = plain).use { it.code to it.body.string() }

    @Test fun getNeverReturnsKey() {
        stored = "0123456789abcdef0123456789abcdef"
        status = KeyStatus.WORKING
        val (code, body) = call("GET")
        assertEquals(200, code)
        assertEquals("""{"set":true,"last4":"cdef","status":"WORKING"}""", body)
        assertFalse(body.contains("0123456789abcdef0123"))
    }

    @Test fun getWithoutAKeyIsNone() {
        val (code, body) = call("GET")
        assertEquals(200, code)
        assertEquals("""{"set":false,"last4":null,"status":"NONE"}""", body)
    }

    @Test fun getChecksAStoredKeyNobodyHasCheckedYet() {
        stored = "0123456789abcdef0123456789abcdef"
        val (_, body) = call("GET")
        assertEquals(1, rechecks.get())
        assertTrue(body.contains(""""status":"WORKING""""))
        call("GET")
        assertEquals(1, rechecks.get())
    }

    @Test fun putSavesTrimmedAndReportsStatus() {
        saveResult = KeyStatus.BAD_KEY
        val (code, body) = call("PUT", """{"key":"  0123456789abcdef0123456789abcdef  "}""")
        assertEquals(200, code)
        assertEquals("0123456789abcdef0123456789abcdef", stored)
        assertEquals("""{"set":true,"last4":"cdef","status":"BAD_KEY"}""", body)
        assertFalse(body.contains("0123456789abcdef0123"))
    }

    @Test fun putRejectsBadBodies() {
        assertEquals(400, call("PUT", """{"nope":1}""").first)
        assertEquals(400, call("PUT", "not json").first)
        assertEquals(400, call("PUT", """{"key":5}""").first)
        assertEquals(400, call("PUT", """{"key":"abc"}""", plain = true).first)
        assertNull(stored)
    }

    @Test fun putOverTheLimitIsRefused() {
        assertEquals(413, call("PUT", """{"key":"${"a".repeat(3000)}"}""").first)
        assertNull(stored)
    }

    @Test fun deleteClears() {
        stored = "0123456789abcdef0123456789abcdef"
        status = KeyStatus.WORKING
        val (code, body) = call("DELETE")
        assertEquals(200, code)
        assertNull(stored)
        assertEquals("""{"set":false,"last4":null,"status":"NONE"}""", body)
    }

    @Test fun requiresPairing() {
        val other = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            for (m in listOf("GET", "PUT", "DELETE")) {
                val rb = if (m == "GET") null else """{"key":"x"}""".toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url("/api/tmdb")).method(m, rb).header("X-Loophole", "1").build()
                other.newCall(req).execute().use { assertEquals(m, 401, it.code) }
            }
        } finally {
            other.dispatcher.executorService.shutdown()
            other.connectionPool.evictAll()
        }
        assertNull(stored)
    }

    @Test fun changesNeedThePageMarker() {
        stored = "0123456789abcdef0123456789abcdef"
        send("PUT", "/api/tmdb", """{"key":"zzz"}""", marker = false).use { assertEquals(403, it.code) }
        send("DELETE", "/api/tmdb", marker = false).use { assertEquals(403, it.code) }
        assertEquals("0123456789abcdef0123456789abcdef", stored)
    }
}
