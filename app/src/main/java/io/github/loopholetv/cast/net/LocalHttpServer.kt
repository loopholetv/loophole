package io.github.loopholetv.cast.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [path], [query], [headers] and [body] are exactly as the peer sent them and must be treated as
 * untrusted. Header names are lower-case; a repeated header's values are joined with ", ".
 * [body] holds exactly [bodyLength] bytes (the request's `Content-Length`, within the route's
 * limit) and ends there; reading it past the server's body deadline, or from a peer that stops
 * early, throws an [IOException]. [toString] leaves out the path, query, headers and body, since
 * they can carry a token.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val remote: InetAddress,
    val body: InputStream = EmptyBody,
    val bodyLength: Long = 0,
) {
    override fun toString() = "HttpRequest(method=$method, remote=${remote.hostAddress})"
}

interface HttpResponse {
    /**
     * Writes the status line and headers. Without [length] the body ends when the socket closes.
     * [headers] are sent as given after the standard ones. A name or value with CR or LF, a name that
     * isn't an HTTP token, or one the server sets itself (Content-Type, Content-Length, Connection,
     * Cache-Control, Transfer-Encoding) throws [IllegalArgumentException] before anything is sent.
     */
    fun start(code: Int, contentType: String?, length: Long? = null, headers: Map<String, String> = emptyMap())
    val body: OutputStream
}

typealias HttpHandler = suspend (HttpRequest, HttpResponse) -> Unit

/**
 * A minimal HTTP/1.1 server (`Connection: close`, GET and POST only) on plain sockets.
 *
 * At most [MAX_CLIENTS] connections are served at once; extra ones are closed immediately.
 * A request head must arrive within [headTimeoutMs] in total, and a single write that blocks for
 * [writeTimeoutMs] closes the connection.
 *
 * A route takes no request body unless it is given a `maxBodyBytes`: a `Content-Length` over the
 * route's limit gets 413, a body without one (`Transfer-Encoding`, or none on a route that takes
 * bodies) gets 411, and the whole body must arrive within [bodyTimeoutMs] of the head (or the
 * route's own deadline).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalHttpServer(
    private val scope: CoroutineScope,
    private val allow: (InetAddress) -> Boolean = ::isLocalPeer,
    private val headTimeoutMs: Int = 5_000,
    private val writeTimeoutMs: Long = 8_000,
    private val newListener: () -> ServerSocket = { ServerSocket() },
    private val maxPerRemote: Int = MAX_PER_REMOTE,
    private val bodyTimeoutMs: Long = BODY_TIMEOUT_MS,
) {
    private class Route(
        val method: String,
        val prefix: String,
        val maxBody: Long,
        val bodyTimeoutMs: Long,
        val handler: HttpHandler,
    )
    private class HeadTooLarge : IOException()

    /** A request head and the bytes after it that arrived in the same reads, `buf[bodyStart until end]`. */
    private class Head(val text: String, val buf: ByteArray, val bodyStart: Int, val end: Int)

    private val routes = CopyOnWriteArrayList<Route>()
    private val listeners = CopyOnWriteArrayList<ServerSocket>()
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val active = AtomicInteger(0)
    private val perRemote = ConcurrentHashMap<InetAddress, AtomicInteger>()
    private val io = Dispatchers.IO.limitedParallelism(MAX_CLIENTS + 8)
    private var job: Job? = null

    /** The port every listener is bound to; null while stopped. */
    @Volatile
    var port: Int? = null
        private set

    /** A route that takes no request body. */
    fun route(method: String, pathPrefix: String, handler: HttpHandler) = route(method, pathPrefix, 0, handler)

    /** [maxBodyBytes] is the largest request body the route takes; 0 takes none. */
    fun route(method: String, pathPrefix: String, maxBodyBytes: Long, handler: HttpHandler) =
        route(method, pathPrefix, maxBodyBytes, bodyTimeoutMs, handler)

    /** As above, with its own deadline for the whole body (for routes with small bodies). */
    fun route(method: String, pathPrefix: String, maxBodyBytes: Long, bodyTimeoutMs: Long, handler: HttpHandler) {
        require(maxBodyBytes >= 0)
        require(bodyTimeoutMs > 0)
        routes.removeAll { it.method == method && it.prefix == pathPrefix }
        routes.add(Route(method, pathPrefix, maxBodyBytes, bodyTimeoutMs, handler))
    }

    fun unroute(pathPrefix: String) {
        routes.removeAll { it.prefix == pathPrefix }
    }

    /**
     * Binds one listener per address on the same port and returns that port (also [port]).
     *
     * With [port] 0 the first address gets an ephemeral port that the others must share; if one of
     * them can't bind it, everything is closed and a fresh port is tried, up to [BIND_ATTEMPTS] times.
     * A fixed [port] is tried once. On failure nothing stays bound and the last error is thrown.
     */
    @Synchronized
    fun start(binds: List<InetAddress>, port: Int = 0): Int {
        check(job?.isActive != true) { "already started" }
        require(binds.isNotEmpty())
        require(binds.none { it.isAnyLocalAddress }) { "wildcard bind" }
        val attempts = if (port == 0) BIND_ATTEMPTS else 1
        var failure: IOException? = null
        repeat(attempts) {
            try {
                return listen(bindAll(binds, port))
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("bind failed")
    }

    /** Binds every address on one port, or closes what it opened and throws. */
    private fun bindAll(binds: List<InetAddress>, port: Int): List<ServerSocket> {
        val opened = ArrayList<ServerSocket>()
        var actual = port
        try {
            for (address in binds) {
                val ss = newListener()
                opened.add(ss)
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(address, actual))
                actual = ss.localPort
            }
        } catch (e: IOException) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
        return opened
    }

    private fun listen(opened: List<ServerSocket>): Int {
        val actual = opened.first().localPort
        val serverJob = SupervisorJob(scope.coroutineContext[Job])
        job = serverJob
        val serverScope = CoroutineScope(scope.coroutineContext + serverJob + io)
        listeners.addAll(opened)
        port = actual
        // Releases every socket as soon as the job is cancelled, by stop() or by the caller's scope.
        serverScope.launch(Dispatchers.Unconfined, start = CoroutineStart.ATOMIC) {
            try {
                awaitCancellation()
            } finally {
                closeAll()
            }
        }
        opened.forEach { ss -> serverScope.launch { acceptLoop(ss, serverJob, serverScope) } }
        return actual
    }

    @Synchronized
    fun stop() {
        closeAll()
        job?.cancel()
        job = null
    }

    private fun closeAll() {
        port = null
        listeners.forEach { runCatching { it.close() } }
        listeners.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop(ss: ServerSocket, serverJob: Job, serverScope: CoroutineScope) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (_: IOException) {
                if (ss.isClosed) return
                // A transient failure (for example running out of descriptors): keep listening.
                try {
                    Thread.sleep(ACCEPT_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val remote = socket.inetAddress
            if (!allow(remote)) {
                runCatching { socket.close() }
                continue
            }
            val mine = perRemote.computeIfAbsent(remote) { AtomicInteger(0) }
            if (mine.incrementAndGet() > maxPerRemote) {
                mine.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            if (active.incrementAndGet() > MAX_CLIENTS) {
                active.decrementAndGet()
                mine.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            clients.add(socket)
            if (!serverJob.isActive) {
                clients.remove(socket)
                release(remote)
                runCatching { socket.close() }
                return
            }
            serverScope.launch(start = CoroutineStart.ATOMIC) { serve(socket, remote, serverScope) }
        }
    }

    private fun release(remote: InetAddress) {
        active.decrementAndGet()
        perRemote[remote]?.decrementAndGet()
    }

    private suspend fun serve(socket: Socket, remote: InetAddress, serverScope: CoroutineScope) {
        val watchdog = WriteWatchdog(socket, writeTimeoutMs)
        val dog = serverScope.launch { watchdog.run() }
        try {
            socket.tcpNoDelay = true
            handle(socket, watchdog)
        } catch (_: IOException) {
            // The peer went away or was closed by the watchdog.
        } finally {
            dog.cancel()
            clients.remove(socket)
            release(remote)
            lingeringClose(socket)
        }
    }

    private suspend fun handle(socket: Socket, watchdog: WriteWatchdog) {
        val head = try {
            readHead(socket)
        } catch (_: SocketTimeoutException) {
            return respond(socket, 400, watchdog)
        } catch (_: HeadTooLarge) {
            return respond(socket, 400, watchdog)
        } ?: return
        val headEnd = System.nanoTime()
        val parsed = parse(head.text, socket.inetAddress) ?: return respond(socket, 400, watchdog)
        if (!pathIsSafe(parsed.path)) return respond(socket, 400, watchdog)
        if (parsed.method != "GET" && parsed.method != "POST") return respond(socket, 405, watchdog)
        val route = routes
            .filter { it.method == parsed.method && matches(it.prefix, parsed.path) }
            .maxByOrNull { it.prefix.length }
            ?: return respond(socket, 404, watchdog)
        if ("transfer-encoding" in parsed.headers) return respond(socket, 411, watchdog)
        val lengthHeader = parsed.headers["content-length"]
        val length = when {
            lengthHeader != null -> contentLength(lengthHeader) ?: return respond(socket, 400, watchdog)
            route.maxBody > 0 && parsed.method == "POST" -> return respond(socket, 411, watchdog)
            else -> 0L
        }
        if (length > route.maxBody) return respond(socket, 413, watchdog)
        val request = if (length == 0L) {
            parsed
        } else {
            val deadline = headEnd + route.bodyTimeoutMs * 1_000_000L
            parsed.copy(body = BodyStream(socket, head, length, deadline), bodyLength = length)
        }
        val response = ResponseImpl(socket.getOutputStream(), watchdog)
        try {
            route.handler(request, response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!response.started) runCatching { respond(socket, 500, watchdog) }
            return
        }
        if (!response.started) respond(socket, 500, watchdog)
    }

    private fun matches(prefix: String, path: String): Boolean =
        path == prefix || path.startsWith(prefix.trimEnd('/') + "/")

    private fun pathIsSafe(path: String): Boolean {
        if (path.indexOf('\u0000') >= 0) return false
        val decoded = decode(path) ?: return false
        if (decoded.indexOf('\u0000') >= 0) return false
        return decoded.split('/').none { it == ".." }
    }

    /** Sends FIN, then discards leftover request bytes briefly so the peer keeps the reply. */
    private fun lingeringClose(socket: Socket) {
        try {
            if (!socket.isClosed) {
                socket.shutdownOutput()
                val deadline = System.nanoTime() + LINGER_MS * 1_000_000L
                val buf = ByteArray(4096)
                var total = 0
                val input = socket.getInputStream()
                while (total < LINGER_MAX_BYTES) {
                    val left = (deadline - System.nanoTime()) / 1_000_000L
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        } catch (_: IOException) {
            // Closing anyway.
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Plain decimal digits only; null for anything else (signs, lists, overflow). */
    private fun contentLength(value: String): Long? {
        if (value.isEmpty() || value.length > 18 || value.any { it !in '0'..'9' }) return null
        return value.toLong()
    }

    /** Reads up to the blank line ending the headers, or returns null on EOF. */
    private fun readHead(socket: Socket): Head? {
        val input = socket.getInputStream()
        val deadline = System.nanoTime() + headTimeoutMs * 1_000_000L
        val buf = ByteArray(MAX_HEAD)
        var n = 0
        while (true) {
            if (n >= MAX_HEAD) throw HeadTooLarge()
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw SocketTimeoutException()
            socket.soTimeout = left.toInt()
            val r = input.read(buf, n, MAX_HEAD - n)
            if (r < 0) return null
            val from = maxOf(0, n - 3)
            n += r
            val end = endOfHead(buf, from, n)
            if (end >= 0) return Head(String(buf, 0, end, Charsets.ISO_8859_1), buf, end, n)
        }
    }

    /** The index just past the blank line ending the head, or -1 when it hasn't arrived yet. */
    private fun endOfHead(b: ByteArray, from: Int, n: Int): Int {
        for (i in maxOf(3, from + 3) until n) {
            if (b[i - 3] == CR && b[i - 2] == LF && b[i - 1] == CR && b[i] == LF) return i + 1
        }
        return -1
    }

    /**
     * A request body of exactly [length] bytes: what arrived with the head, then the socket. Every
     * read is bounded by [deadline] (System.nanoTime), so a peer that trickles can't hold it open.
     */
    private class BodyStream(
        private val socket: Socket,
        head: Head,
        length: Long,
        private val deadline: Long,
    ) : InputStream() {
        private val buf = head.buf
        private var pos = head.bodyStart
        private val end = head.end
        private var remaining = length

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            if (pos < end) {
                val n = minOf(want, end - pos)
                System.arraycopy(buf, pos, b, off, n)
                pos += n
                remaining -= n
                return n
            }
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw SocketTimeoutException()
            socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val n = socket.getInputStream().read(b, off, want)
            if (n < 0) throw EOFException()
            remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, (end - pos).toLong()).toInt().coerceAtLeast(0)
    }

    private fun parse(head: String, remote: InetAddress): HttpRequest? {
        val lines = head.substringBefore("\r\n\r\n").split("\r\n")
        val line = lines.first()
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        val path = target.substringBefore('?')
        val query = LinkedHashMap<String, String>()
        if (target.contains('?')) {
            target.substringAfter('?').split('&').filter { it.isNotEmpty() }.forEach {
                val key = decode(it.substringBefore('='))
                val value = decode(it.substringAfter('=', ""))
                if (key != null && value != null) query[key] = value
            }
        }
        val headers = parseHeaders(lines.drop(1)) ?: return null
        return HttpRequest(parts[0], path, query, headers, remote)
    }

    /** Lower-cased names; null for a line that isn't `name: value` (folded lines included). */
    private fun parseHeaders(lines: List<String>): Map<String, String>? {
        val headers = LinkedHashMap<String, String>()
        for (raw in lines) {
            val colon = raw.indexOf(':')
            if (colon <= 0) return null
            val name = raw.substring(0, colon)
            if (!isToken(name)) return null
            val key = name.lowercase(Locale.ROOT)
            val value = raw.substring(colon + 1).trim(' ', '\t')
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }
        return headers
    }

    private fun decode(s: String): String? =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: IllegalArgumentException) { null }

    private fun respond(socket: Socket, code: Int, watchdog: WriteWatchdog) {
        ResponseImpl(socket.getOutputStream(), watchdog).start(code, null, 0, emptyMap())
    }

    /** Closes the socket when a single write has been blocked for [timeoutMs]. */
    private class WriteWatchdog(private val socket: Socket, private val timeoutMs: Long) {
        @Volatile var writeStartedAt: Long = 0L

        suspend fun run() {
            while (true) {
                delay(maxOf(25L, minOf(500L, timeoutMs / 4)))
                val started = writeStartedAt
                if (started != 0L && System.nanoTime() - started > timeoutMs * 1_000_000L) {
                    runCatching { socket.close() }
                    return
                }
            }
        }
    }

    private class ResponseImpl(
        private val out: OutputStream,
        private val watchdog: WriteWatchdog,
    ) : HttpResponse {
        var started = false
            private set

        override val body: OutputStream = object : OutputStream() {
            override fun write(b: Int) = guarded { out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = guarded { out.write(b, off, len) }
            override fun flush() = guarded { out.flush() }
        }

        private inline fun guarded(block: () -> Unit) {
            check(started) { "start() must be called before writing the body" }
            watchdog.writeStartedAt = System.nanoTime()
            try {
                block()
            } finally {
                watchdog.writeStartedAt = 0L
            }
        }

        override fun start(code: Int, contentType: String?, length: Long?, headers: Map<String, String>) {
            check(!started) { "already started" }
            require(code in 100..999) { "bad status" }
            require(contentType == null || noLineBreak(contentType)) { "line break in Content-Type" }
            for ((name, value) in headers) {
                require(isToken(name)) { "bad header name" }
                require(name.lowercase(Locale.ROOT) !in RESERVED_HEADERS) { "reserved header" }
                require(noLineBreak(value)) { "line break in header value" }
            }
            started = true
            val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
            if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
            sb.append("Cache-Control: no-store\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> sb.append(name).append(": ").append(value).append("\r\n") }
            sb.append("\r\n")
            guarded { out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush() }
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            410 -> "Gone"
            411 -> "Length Required"
            413 -> "Content Too Large"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status"
        }
    }

    companion object {
        const val MAX_CLIENTS = 16
        const val MAX_PER_REMOTE = 6
        const val BIND_ATTEMPTS = 3
        const val BODY_TIMEOUT_MS = 120_000L
        private val RESERVED_HEADERS =
            setOf("content-type", "content-length", "connection", "cache-control", "transfer-encoding")
        private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

        /** An RFC 9110 token: what a header name may be. */
        private fun isToken(s: String) = s.isNotEmpty() &&
            s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }

        private fun noLineBreak(s: String) = s.none { it == '\r' || it == '\n' || it == '\u0000' }
        private const val ACCEPT_BACKOFF_MS = 100L
        private const val MAX_HEAD = 8 * 1024
        private const val LINGER_MS = 500L
        private const val LINGER_MAX_BYTES = 64 * 1024
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}

/** The body of a request without one. */
private object EmptyBody : InputStream() {
    override fun read(): Int = -1
}
