package io.github.loopholetv.cast.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalHttpServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val server = LocalHttpServer(scope)

    @After fun tearDown() {
        server.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun url(port: Int, path: String) = "http://127.0.0.1:$port$path"

    @Test fun servesRouteAndLength() {
        val seen = AtomicReference<HttpRequest>()
        server.route("GET", "/hello") { req, res ->
            seen.set(req)
            res.start(200, "text/plain", 5)
            res.body.write("world".toByteArray())
        }
        val port = server.start(listOf(loopback))
        client.newCall(Request.Builder().url(url(port, "/hello/x?a=1&b=two%20words")).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("5", it.header("Content-Length"))
            assertEquals("text/plain", it.header("Content-Type"))
            assertEquals("close", it.header("Connection"))
            assertEquals("world", it.body.string())
        }
        val req = seen.get()
        assertEquals("GET", req.method)
        assertEquals("/hello/x", req.path)
        assertEquals(mapOf("a" to "1", "b" to "two words"), req.query)
        assertEquals(loopback, req.remote)
        assertEquals("127.0.0.1:$port", req.headers["host"])
    }

    @Test fun longestPrefixWins() {
        server.route("GET", "/a") { _, res -> res.start(200, null, 1); res.body.write('a'.code) }
        server.route("GET", "/a/b") { _, res -> res.start(200, null, 1); res.body.write('b'.code) }
        val port = server.start(listOf(loopback))
        client.newCall(Request.Builder().url(url(port, "/a/b/c")).build()).execute().use {
            assertEquals("b", it.body.string())
        }
        server.unroute("/a/b")
        client.newCall(Request.Builder().url(url(port, "/a/b/c")).build()).execute().use {
            assertEquals("a", it.body.string())
        }
    }

    @Test fun unknownPathIs404() {
        server.route("GET", "/known") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        client.newCall(Request.Builder().url(url(port, "/other")).build()).execute().use {
            assertEquals(404, it.code)
        }
    }

    @Test fun postBodyIsIgnoredAndRouted() {
        server.route("POST", "/cmd") { _, res -> res.start(204, null) }
        val port = server.start(listOf(loopback))
        val body = "ignored body".toRequestBody()
        client.newCall(Request.Builder().url(url(port, "/cmd")).post(body).build()).execute().use {
            assertEquals(204, it.code)
        }
    }

    @Test fun putIs405() {
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        val body = "x".toRequestBody()
        client.newCall(Request.Builder().url(url(port, "/x")).put(body).build()).execute().use {
            assertEquals(405, it.code)
        }
    }

    @Test fun oversizedHeaderIs400() {
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        val request = Request.Builder().url(url(port, "/x"))
            .header("X-Big", "a".repeat(9000)).build()
        client.newCall(request).execute().use { assertEquals(400, it.code) }
    }

    @Test fun rejectsNonLocalPeer() {
        val ran = AtomicBoolean(false)
        val denying = LocalHttpServer(scope, allow = { false })
        denying.route("GET", "/x") { _, res -> ran.set(true); res.start(200, null, 0) }
        val port = denying.start(listOf(loopback))
        try {
            Socket(loopback, port).use { s ->
                s.soTimeout = 15_000
                s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val n = try { s.getInputStream().read() } catch (_: IOException) { -1 }
                assertEquals(-1, n)
            }
            assertFalse(ran.get())
        } finally {
            denying.stop()
        }
    }

    @Test fun streamsUntilHandlerReturns() {
        server.route("GET", "/live") { _, res ->
            res.start(200, "video/mp2t")
            repeat(3) { res.body.write(ByteArray(1000) { i -> i.toByte() }); res.body.flush() }
        }
        val port = server.start(listOf(loopback))
        client.newCall(Request.Builder().url(url(port, "/live")).build()).execute().use {
            assertNull(it.header("Content-Length"))
            assertEquals(3000, it.body.bytes().size)
        }
    }

    @Test fun stopClosesOpenClients() = runBlocking {
        val inHandler = CompletableDeferred<Unit>()
        server.route("GET", "/live") { _, res ->
            res.start(200, null)
            res.body.write(1)
            res.body.flush()
            inHandler.complete(Unit)
            awaitCancellation()
        }
        val port = server.start(listOf(loopback))
        Socket(loopback, port).use { s ->
            s.soTimeout = 30_000
            s.getOutputStream().write("GET /live HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            withTimeout(30_000) { inHandler.await() }
            server.stop()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (true) {
                val n = try { s.getInputStream().read(buf) } catch (_: IOException) { -1 }
                if (n < 0) break
                out.write(buf, 0, n)
            }
            assertTrue(out.toString().startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun stalledClientClosedAfterWriteTimeout() {
        val fast = LocalHttpServer(scope, writeTimeoutMs = 500)
        try { stallBody(fast) } finally { fast.stop() }
    }

    private fun stallBody(server: LocalHttpServer) {
        val ended = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        server.route("GET", "/big") { _, res ->
            res.start(200, null)
            val chunk = ByteArray(64 * 1024)
            try {
                while (true) res.body.write(chunk)
            } catch (_: IOException) {
                failed.set(true)
            } finally {
                ended.countDown()
            }
        }
        val port = server.start(listOf(loopback))
        Socket().use { s ->
            s.receiveBufferSize = 4096
            s.connect(InetSocketAddress(loopback, port))
            s.getOutputStream().write("GET /big HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            assertTrue(ended.await(60, TimeUnit.SECONDS))
            assertTrue(failed.get())
        }
    }

    private fun raw(port: Int, request: String): String =
        Socket(loopback, port).use { s ->
            s.soTimeout = 30_000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = try { s.getInputStream().read(buf) } catch (_: IOException) { -1 }
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.ISO_8859_1)
        }

    @Test fun seventhConnectionFromOneAddressIsClosedWhileAnotherIsServed() {
        val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
        assumeTrue(other != null)
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback, other!!))
        val idle = List(LocalHttpServer.MAX_PER_REMOTE) { Socket(loopback, port) }
        try {
            Socket(loopback, port).use { extra ->
                extra.soTimeout = 30_000
                val n = try { extra.getInputStream().read() } catch (_: IOException) { -1 }
                assertEquals(-1, n)
            }
            Socket().use { s ->
                s.bind(InetSocketAddress(other, 0))
                s.connect(InetSocketAddress(other, port))
                s.soTimeout = 30_000
                s.getOutputStream().write("GET /x HTTP/1.1\r\n\r\n".toByteArray())
                val head = ByteArray(12)
                s.getInputStream().readNBytes(head, 0, 12)
                assertEquals("HTTP/1.1 200", String(head))
            }
        } finally {
            idle.forEach { it.close() }
        }
    }

    @Test fun acceptErrorsDoNotStopTheListener() {
        val failures = java.util.concurrent.atomic.AtomicInteger(2)
        val flaky = LocalHttpServer(scope, newListener = {
            object : java.net.ServerSocket() {
                override fun accept(): Socket {
                    if (failures.getAndDecrement() > 0) throw IOException("transient")
                    return super.accept()
                }
            }
        })
        flaky.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = flaky.start(listOf(loopback))
        try {
            assertTrue(raw(port, "GET /x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        } finally {
            flaky.stop()
        }
    }

    @Test fun seventeenthConnectionIsClosedAndServiceRecovers() {
        val server = LocalHttpServer(scope, maxPerRemote = 100)
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        val idle = List(LocalHttpServer.MAX_CLIENTS) { Socket(loopback, port) }
        try {
            Socket(loopback, port).use { extra ->
                extra.soTimeout = 30_000
                val n = try { extra.getInputStream().read() } catch (_: IOException) { -1 }
                assertEquals(-1, n)
            }
        } finally {
            idle.forEach { it.close() }
        }
        var status = ""
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!status.startsWith("HTTP/1.1 200") && System.nanoTime() < deadline) {
            status = raw(port, "GET /x HTTP/1.1\r\nHost: h\r\n\r\n")
        }
        assertTrue(status.startsWith("HTTP/1.1 200"))
    }

    @Test fun slowDripHeadIsClosedByTheDeadline() {
        val slow = LocalHttpServer(scope, headTimeoutMs = 1_000)
        slow.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = slow.start(listOf(loopback))
        try {
            Socket(loopback, port).use { s ->
                s.soTimeout = 30_000
                val stop = AtomicBoolean(false)
                val dripper = Thread {
                    try {
                        for (c in "GET /x HTTP/1.1\r\nX-A: aaaaaaaaaaaaaaaaaaaaaaaaaaaa") {
                            if (stop.get()) break
                            s.getOutputStream().write(c.code)
                            Thread.sleep(250)
                        }
                    } catch (_: Exception) {
                    }
                }
                dripper.start()
                val started = System.nanoTime()
                val first = try { s.getInputStream().read() } catch (_: IOException) { -1 }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                stop.set(true)
                assertEquals('H'.code, first)
                assertTrue("took $elapsedMs ms", elapsedMs < 10_000)
            }
        } finally {
            slow.stop()
        }
    }

    @Test fun silentClientGets400AfterHeadTimeout() {
        val quick = LocalHttpServer(scope, headTimeoutMs = 300)
        val port = quick.start(listOf(loopback))
        try {
            assertTrue(raw(port, "").startsWith("HTTP/1.1 400"))
        } finally {
            quick.stop()
        }
    }

    @Test fun malformedRequestLineIs400() {
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        for (line in listOf("GARBAGE", "GET", "GET  /x HTTP/1.1", "GET x HTTP/1.1", "GET http://h/x HTTP/1.1")) {
            assertTrue(line, raw(port, "$line\r\n\r\n").startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun malformedEscapeInQueryIsDropped() {
        val seen = AtomicReference<HttpRequest>()
        server.route("GET", "/x") { req, res -> seen.set(req); res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        assertTrue(raw(port, "GET /x?a=%zz&b=ok HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        assertEquals(mapOf("b" to "ok"), seen.get().query)
    }

    @Test fun handlerFailureAndMissingStartAre500() {
        server.route("GET", "/boom") { _, _ -> error("boom") }
        server.route("GET", "/silent") { _, _ -> }
        val port = server.start(listOf(loopback))
        assertTrue(raw(port, "GET /boom HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
        assertTrue(raw(port, "GET /silent HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
    }

    @Test fun prefixMatchesOnSegmentBoundariesOnly() {
        server.route("GET", "/s/abc") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        assertTrue(raw(port, "GET /s/abc HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        assertTrue(raw(port, "GET /s/abc/live.ts HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        assertTrue(raw(port, "GET /s/abcdef HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test fun dotDotAndNulPathsAre400() {
        server.route("GET", "/s", { _, res -> res.start(200, null, 0) })
        val port = server.start(listOf(loopback))
        for (path in listOf("/s/../x", "/s/%2e%2e/x", "/s/a%00b", "/s/..")) {
            assertTrue(path, raw(port, "GET $path HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun largePostBodyStillGetsItsReply() {
        server.route("POST", "/cmd") { _, res -> res.start(204, null) }
        val port = server.start(listOf(loopback))
        val body = ByteArray(40_000).toRequestBody()
        client.newCall(Request.Builder().url(url(port, "/cmd")).post(body).build()).execute().use {
            assertEquals(204, it.code)
        }
    }

    @Test fun wildcardBindIsRefused() {
        var refused = false
        try {
            server.start(listOf(InetAddress.getByName("0.0.0.0")))
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test fun cancellingTheCallerScopeReleasesTheListener() {
        val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = LocalHttpServer(own)
        val port = s.start(listOf(loopback))
        own.cancel()
        val deadline = System.nanoTime() + 30_000_000_000L
        var closed = false
        while (!closed && System.nanoTime() < deadline) {
            closed = try {
                Socket(loopback, port).use { false }
            } catch (_: IOException) {
                true
            }
            if (!closed) Thread.sleep(50)
        }
        assertTrue(closed)
    }

    @Test fun bindsEveryAddressOnTheSamePort() {
        val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
        assumeTrue(other != null)
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback, other!!))
        for (host in listOf("127.0.0.1", other.hostAddress)) {
            client.newCall(Request.Builder().url("http://$host:$port/x").build()).execute().use {
                assertEquals(200, it.code)
            }
        }
    }

    @Test fun requestHeadersAreLowerCasedAndRepeatsJoined() {
        val seen = AtomicReference<HttpRequest>()
        server.route("GET", "/x") { req, res -> seen.set(req); res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        val reply = raw(port, "GET /x HTTP/1.1\r\nRange: bytes=100-\r\nX-Dup: a\r\nx-dup:b \r\nEmpty:\r\n\r\n")
        assertTrue(reply.startsWith("HTTP/1.1 200"))
        assertEquals(mapOf("range" to "bytes=100-", "x-dup" to "a, b", "empty" to ""), seen.get().headers)
    }

    @Test fun malformedHeaderLineIs400() {
        server.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        val port = server.start(listOf(loopback))
        for (line in listOf("NoColon", ": novalue", " folded: x", "Bad Name: x", "Bad\u0001: x")) {
            assertTrue(line, raw(port, "GET /x HTTP/1.1\r\n$line\r\n\r\n").startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun responseHeadersAreSent() {
        server.route("GET", "/r") { _, res ->
            res.start(206, "video/mp2t", 4, mapOf("Content-Range" to "bytes 0-3/10", "Accept-Ranges" to "bytes"))
            res.body.write("abcd".toByteArray())
        }
        val port = server.start(listOf(loopback))
        client.newCall(Request.Builder().url(url(port, "/r")).build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("bytes 0-3/10", it.header("Content-Range"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("4", it.header("Content-Length"))
            assertEquals("abcd", it.body.string())
        }
    }

    @Test fun unsafeResponseHeadersAreRefusedBeforeAnythingIsSent() {
        val refused = java.util.concurrent.atomic.AtomicInteger(0)
        val bad = listOf(
            mapOf("X-A" to "a\r\nSet-Cookie: x"),
            mapOf("X-A" to "a\nb"),
            mapOf("X-A\r\nSet-Cookie" to "x"),
            mapOf("X A" to "x"),
            mapOf("" to "x"),
            mapOf("content-length" to "1"),
            mapOf("Connection" to "keep-alive"),
            mapOf("Transfer-Encoding" to "chunked"),
        )
        server.route("GET", "/x") { _, res ->
            for (headers in bad) {
                try {
                    res.start(200, null, 0, headers)
                } catch (_: IllegalArgumentException) {
                    refused.incrementAndGet()
                }
            }
            res.start(200, null, 0)
        }
        val port = server.start(listOf(loopback))
        val reply = raw(port, "GET /x HTTP/1.1\r\n\r\n")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        assertFalse(reply, reply.contains("Set-Cookie"))
        assertFalse(reply, reply.contains("chunked"))
        assertEquals(bad.size, refused.get())
    }

    @Test fun requestToStringHidesPathQueryAndHeaders() {
        val req = HttpRequest("GET", "/c/s3cr3t/1.ts", mapOf("k" to "qv4lue"), mapOf("x-h" to "hv4lue"), loopback)
        val text = req.toString()
        listOf("s3cr3t", "qv4lue", "hv4lue", "x-h").forEach { assertFalse(text, text.contains(it)) }
        assertTrue(text.contains("GET"))
    }

    @Test fun portIsNullWhileStopped() {
        assertNull(server.port)
        val port = server.start(listOf(loopback))
        assertEquals(port, server.port)
        server.stop()
        assertNull(server.port)
        val again = server.start(listOf(loopback))
        assertEquals(again, server.port)
    }

    /** Listeners made in order; the ones whose number is in [failing] throw on bind. */
    private fun listeners(failing: (Int) -> Boolean, made: MutableList<java.net.ServerSocket>): () -> java.net.ServerSocket = {
        val n = made.size + 1
        val ss = object : java.net.ServerSocket() {
            override fun bind(endpoint: java.net.SocketAddress?, backlog: Int) {
                if (failing(n)) throw java.net.BindException("in use")
                super.bind(endpoint, backlog)
            }
        }
        made.add(ss)
        ss
    }

    @Test fun laterBindFailureRetriesOnAFreshPort() {
        val v6 = InetAddress.getByName("::1")
        val v6Works = runCatching { java.net.ServerSocket(0, 1, v6).close() }.isSuccess
        assumeTrue(v6Works)
        val made = mutableListOf<java.net.ServerSocket>()
        val retrying = LocalHttpServer(scope, newListener = listeners({ it == 2 }, made))
        retrying.route("GET", "/x") { _, res -> res.start(200, null, 0) }
        try {
            val port = retrying.start(listOf(loopback, v6))
            assertEquals(4, made.size)
            assertTrue(made[0].isClosed)
            assertEquals(port, retrying.port)
            assertEquals(port, made[2].localPort)
            assertEquals(port, made[3].localPort)
            assertTrue(raw(port, "GET /x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        } finally {
            retrying.stop()
        }
    }

    @Test fun bindRetriesAreBounded() {
        val made = mutableListOf<java.net.ServerSocket>()
        val failing = LocalHttpServer(scope, newListener = listeners({ it % 2 == 0 }, made))
        var thrown = false
        try {
            failing.start(listOf(loopback, loopback))
        } catch (_: java.net.BindException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals(LocalHttpServer.BIND_ATTEMPTS * 2, made.size)
        assertTrue(made.all { it.isClosed })
        assertNull(failing.port)
    }

    @Test fun fixedPortIsTriedOnce() {
        val made = mutableListOf<java.net.ServerSocket>()
        val fixed = LocalHttpServer(scope, newListener = listeners({ it == 2 }, made))
        val free = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        var thrown = false
        try {
            fixed.start(listOf(loopback, loopback), port = free)
        } catch (_: java.net.BindException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals(2, made.size)
        assertTrue(made.all { it.isClosed })
        assertNull(fixed.port)
    }
}
