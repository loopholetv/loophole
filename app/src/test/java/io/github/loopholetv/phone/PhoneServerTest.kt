package io.github.loopholetv.phone

import io.github.loopholetv.cast.net.LocalHttpServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class InMemoryPairingStore : PairingStore {
    val phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    @Volatile var port: Int? = null
    override suspend fun pairedPhones() = phones.value
    override val pairedCount = phones.map { it.size }
    override suspend fun savePairedPhones(list: List<PairedPhone>) { phones.value = list }
    override suspend fun phonePort() = port
    override suspend fun savePhonePort(port: Int) { this.port = port }
}

/** Keeps every cookie the server sets, dropping it again when the server expires it. */
internal class MemoryCookieJar : CookieJar {
    val cookies = CopyOnWriteArrayList<Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies.add(c)
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
}

class PhoneServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = InMemoryPairingStore()
    private val pairing = PhonePairing(store, System::currentTimeMillis)
    private val http = LocalHttpServer(scope, maxPerRemote = 4)
    private val jar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile private var lan: Inet4Address? = loopback
    private val lanAsked = CountDownLatch(1)
    @Volatile private var onNetworkChange: (() -> Unit)? = null

    private val phone = PhoneServer(
        scope = scope,
        server = http,
        pairing = pairing,
        store = store,
        lanAddress = { lanAsked.countDown(); lan },
        watchNetwork = { onChange -> onNetworkChange = onChange; AutoCloseable { onNetworkChange = null } },
        asset = { name -> if (name == "phone/index.html") PAGE.byteInputStream() else null },
        tvName = { "Living room" },
    )

    @After fun tearDown() {
        phone.onForeground(false)
        http.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun started(): PhoneAddress {
        phone.onForeground(true)
        return awaitAddress()
    }

    private fun awaitAddress(): PhoneAddress = runBlocking {
        withTimeout(5_000) { phone.address.first { it != null }!! }
    }

    private fun awaitStopped() = runBlocking {
        withTimeout(5_000) { phone.address.first { it == null } }
    }

    private fun url(a: PhoneAddress, path: String) = "http://127.0.0.1:${a.port}$path"

    private fun get(a: PhoneAddress, path: String, build: Request.Builder.() -> Unit = {}): Response =
        client.newCall(Request.Builder().url(url(a, path)).apply(build).build()).execute()

    private fun post(a: PhoneAddress, path: String, body: String = "{}", build: Request.Builder.() -> Unit = {}): Response =
        client.newCall(
            Request.Builder()
                .url(url(a, path))
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-Loophole", "1")
                .apply(build)
                .build(),
        ).execute()

    private fun pair(a: PhoneAddress): Response {
        pairing.open()
        val code = pairing.code.value!!
        return post(a, "/api/pair", """{"code":"$code"}""")
    }

    private fun addPing() = phone.api("GET", "/api/ping") { _, res ->
        res.start(200, "text/plain", 4)
        res.body.write("pong".toByteArray())
    }

    @Test fun servesPageWithSecurityHeaders() {
        val a = started()
        assertEquals(loopback.hostAddress, a.host)
        get(a, "/").use {
            assertEquals(200, it.code)
            assertEquals("text/html; charset=utf-8", it.header("Content-Type"))
            assertEquals(
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:",
                it.header("Content-Security-Policy"),
            )
            assertEquals("DENY", it.header("X-Frame-Options"))
            assertEquals("no-referrer", it.header("Referrer-Policy"))
            assertEquals(PAGE, it.body.string())
        }
        get(a, "/other").use { assertEquals(404, it.code) }
    }

    @Test fun statusUnpaired() {
        val a = started()
        get(a, "/api/status").use {
            assertEquals(200, it.code)
            assertEquals("""{"paired":false,"tv":"Living room","showing":false}""", it.body.string())
        }
        pairing.open()
        get(a, "/api/status").use {
            assertEquals("""{"paired":false,"tv":"Living room","showing":true}""", it.body.string())
        }
    }

    @Test fun pairSetsHttpOnlyCookie() {
        val a = started()
        pair(a).use {
            assertEquals(200, it.code)
            val set = it.header("Set-Cookie")!!
            val token = set.substringAfter("$PHONE_COOKIE=").substringBefore(';')
            assertTrue(token.length >= 32)
            assertEquals(
                "$PHONE_COOKIE=$token; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000",
                set,
            )
            assertFalse(it.body.string().contains(token))
        }
        assertEquals(1, store.phones.value.size)
        get(a, "/api/status").use {
            assertEquals("""{"paired":true,"tv":"Living room","showing":true}""", it.body.string())
        }
    }

    @Test fun wrongCodeIsRefused() {
        val a = started()
        pairing.open()
        val wrong = if (pairing.code.value == "0000") "1111" else "0000"
        post(a, "/api/pair", """{"code":"$wrong"}""").use {
            assertEquals(403, it.code)
            assertEquals("""{"error":"wrong_code"}""", it.body.string())
            assertNull(it.header("Set-Cookie"))
        }
        pairing.close()
        post(a, "/api/pair", """{"code":"1234"}""").use {
            assertEquals(409, it.code)
            assertEquals("""{"error":"not_showing"}""", it.body.string())
        }
        post(a, "/api/pair", "not json").use { assertEquals(400, it.code) }
        assertTrue(store.phones.value.isEmpty())
    }

    @Test fun apiWithoutCookieIs401() {
        addPing()
        val a = started()
        get(a, "/api/ping").use {
            assertEquals(401, it.code)
            assertEquals("""{"error":"not_paired"}""", it.body.string())
        }
        get(a, "/api/ping") { header("Cookie", "$PHONE_COOKIE=made-up") }.use { assertEquals(401, it.code) }
    }

    @Test fun apiWithCookieRuns() {
        val a = started()
        pair(a).close()
        // Routes added once the server is up work too.
        addPing()
        get(a, "/api/ping").use {
            assertEquals(200, it.code)
            assertEquals("pong", it.body.string())
        }
    }

    @Test fun apiRouteTakesSubPaths() {
        val seen = CopyOnWriteArrayList<String>()
        phone.api("GET", "/api/x") { req, res ->
            seen += req.path
            res.sendEmpty(204)
        }
        val a = started()
        pair(a).close()
        get(a, "/api/x/123").use { assertEquals(204, it.code) }
        assertEquals(listOf("/api/x/123"), seen)
        get(a, "/nope").use { assertEquals(404, it.code) }
        get(a, "/api/status/extra").use { assertEquals(404, it.code) }
    }

    @Test fun postWithoutHeaderIs403() {
        val a = started()
        pairing.open()
        val code = pairing.code.value!!
        client.newCall(
            Request.Builder()
                .url(url(a, "/api/pair"))
                .post("""{"code":"$code"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use {
            assertEquals(403, it.code)
            assertEquals("""{"error":"forbidden"}""", it.body.string())
            assertNull(it.header("Set-Cookie"))
        }
        assertTrue(store.phones.value.isEmpty())
    }

    @Test fun foreignOriginIs403() {
        val a = started()
        pairing.open()
        val code = pairing.code.value!!
        post(a, "/api/pair", """{"code":"$code"}""") { header("Origin", "http://evil.example") }.use {
            assertEquals(403, it.code)
            assertEquals("""{"error":"forbidden"}""", it.body.string())
        }
        assertTrue(store.phones.value.isEmpty())
        // The page's own origin is fine.
        post(a, "/api/pair", """{"code":"$code"}""") { header("Origin", "http://127.0.0.1:${a.port}") }.use {
            assertEquals(200, it.code)
        }
    }

    @Test fun foreignHostIs421() {
        val a = started()
        get(a, "/") { header("Host", "evil.example:${a.port}") }.use { assertEquals(421, it.code) }
        get(a, "/api/status") { header("Host", "127.0.0.1:1") }.use { assertEquals(421, it.code) }
        pairing.open()
        val code = pairing.code.value!!
        post(a, "/api/pair", """{"code":"$code"}""") { header("Host", "evil.example:${a.port}") }.use {
            assertEquals(421, it.code)
        }
        assertTrue(store.phones.value.isEmpty())
    }

    @Test fun takenPreferredPortFallsBack() {
        ServerSocket().use { holder ->
            holder.bind(InetSocketAddress(loopback, 0))
            store.port = holder.localPort
            val a = started()
            assertNotEquals(holder.localPort, a.port)
            assertEquals(a.port, store.port)
            get(a, "/").use { assertEquals(200, it.code) }
        }
    }

    @Test fun restartKeepsPortAndPairing() {
        val first = started()
        pair(first).close()
        phone.onForeground(false)
        awaitStopped()
        val second = started()
        assertEquals(first.port, second.port)
        assertEquals(first.port, store.port)
        get(second, "/api/status").use {
            assertEquals("""{"paired":true,"tv":"Living room","showing":true}""", it.body.string())
        }
    }

    @Test fun noLanAddressLeavesServerStopped() {
        lan = null
        phone.onForeground(true)
        assertTrue(lanAsked.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertNull(phone.address.value)
        assertNull(http.port)
    }

    @Test fun networkChangeStartsServer() {
        lan = null
        phone.onForeground(true)
        assertTrue(lanAsked.await(5, TimeUnit.SECONDS))
        lan = loopback
        assertNotNull(onNetworkChange)
        onNetworkChange!!.invoke()
        val a = awaitAddress()
        get(a, "/").use { assertEquals(200, it.code) }
        lan = null
        onNetworkChange!!.invoke()
        awaitStopped()
        assertNull(http.port)
    }

    @Test fun forgetClearsCookie() {
        val a = started()
        pair(a).close()
        post(a, "/api/forget").use {
            assertEquals(200, it.code)
            assertEquals("$PHONE_COOKIE=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0", it.header("Set-Cookie"))
        }
        assertTrue(store.phones.value.isEmpty())
        assertTrue(jar.cookies.isEmpty())
        post(a, "/api/forget").use { assertEquals(401, it.code) }
    }

    private companion object {
        const val PAGE = "<!doctype html><title>Loophole</title>"
    }
}
