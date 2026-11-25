package io.github.loopholetv.phone

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import io.github.loopholetv.cast.net.HttpHandler
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.cast.net.LocalHttpServer
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val PHONE_DEFAULT_PORT = 8765
const val PHONE_COOKIE = "loophole_phone"

/** Where a phone on the same network reaches this TV's phone page. */
data class PhoneAddress(val host: String, val port: Int) {
    val short get() = "$host:$port"
    val url get() = "http://$host:$port/"
}

/**
 * Serves the phone page and its API from this TV while Loophole is in the foreground, on the TV's
 * LAN address and loopback, on a port that stays the same across restarts when it can.
 *
 * Every request must name one of those addresses in its `Host`; every POST, PUT and DELETE must come from the page
 * itself ([postAllowed]); API routes also need a paired phone's cookie. Nothing here logs codes,
 * tokens, cookies, bodies or addresses.
 */
class PhoneServer(
    private val scope: CoroutineScope,
    private val server: LocalHttpServer,
    private val pairing: PhonePairing,
    private val store: PairingStore,
    private val lanAddress: () -> Inet4Address?,
    private val watchNetwork: (() -> Unit) -> AutoCloseable,
    private val asset: (String) -> InputStream?,
    private val tvName: suspend () -> String,
    private val parental: PhoneParental = PhoneParental(),
) {
    private val sessions = PhoneSessions(parental)

    private val _address = MutableStateFlow<PhoneAddress?>(null)

    /** Null while stopped or without a LAN address. */
    val address: StateFlow<PhoneAddress?> = _address.asStateFlow()

    @Volatile private var foreground = false

    /** The `Host` values requests may carry; empty while stopped. */
    @Volatile private var hosts: Set<String> = emptySet()

    private val lifecycle = Mutex()
    private var boundLan: Inet4Address? = null // Guarded by lifecycle.
    private var networkWatch: AutoCloseable? = null // Guarded by this.

    init {
        server.route("GET", "/", 0, BODY_TIMEOUT_MS, guarded("/", auth = false, exact = true, ::page))
        server.route("GET", "/api/status", 0, BODY_TIMEOUT_MS, guarded("/api/status", auth = false, exact = true, ::status))
        server.route("POST", "/api/pair", PAIR_MAX_BODY, BODY_TIMEOUT_MS, guarded("/api/pair", auth = false, exact = true, ::pair))
        api("POST", "/api/forget", handler = ::forget)
        api("POST", PIN_PATH, PIN_MAX_BODY, ::pin)
    }

    /**
     * Whether this request's phone must enter the PIN before a protected action: a PIN exists and
     * the phone hasn't entered it. Independent of the Settings lock.
     */
    internal suspend fun pinNeeded(req: HttpRequest): Boolean = sessions.locked(token(req))

    /** True: starts serving, and follows network changes. False: stops. */
    fun onForeground(foreground: Boolean) {
        synchronized(this) {
            if (foreground == this.foreground) return
            this.foreground = foreground
            if (foreground) {
                networkWatch = watchNetwork { settle() }
            } else {
                networkWatch?.let { runCatching { it.close() } }
                networkWatch = null
            }
        }
        settle()
    }

    /**
     * An authenticated API route: the handler runs only with a paired cookie (and, for POST, the
     * guard). It also receives sub-paths (`/api/x` gets `/api/x/123`) and parses them itself.
     * Routes can be added before or after the server starts, and stay across restarts.
     */
    fun api(method: String, path: String, maxBodyBytes: Long = 16 * 1024, handler: HttpHandler) {
        server.route(method, path, maxBodyBytes, BODY_TIMEOUT_MS, guarded(path, auth = true, exact = false, handler))
    }

    /** Brings the server in line with [foreground] and the current LAN address. */
    private fun settle() {
        scope.launch(Dispatchers.IO) { lifecycle.withLock { reconcile() } }
    }

    private suspend fun reconcile() {
        val lan = if (foreground) lanAddress() else null
        if (lan == null) return stopServer()
        if (server.port != null && lan == boundLan) return
        // Restarting on a new address keeps the port; a fresh start takes the last one bound.
        val preferred = server.port ?: store.phonePort() ?: PHONE_DEFAULT_PORT
        stopServer()
        val binds = listOf<InetAddress>(lan, LOOPBACK).distinct()
        val port = bind(binds, preferred) ?: return
        boundLan = lan
        hosts = setOf("${lan.hostAddress}:$port", "${LOOPBACK.hostAddress}:$port")
        _address.value = PhoneAddress(lan.hostAddress.orEmpty(), port)
        if (store.phonePort() != port) store.savePhonePort(port)
    }

    /** The preferred port, then the default, then any free one; null when nothing binds. */
    private fun bind(binds: List<InetAddress>, preferred: Int): Int? {
        for (port in listOf(preferred, PHONE_DEFAULT_PORT, 0).distinct()) {
            try {
                return server.start(binds, port)
            } catch (_: IOException) {
                // Taken: try the next.
            }
        }
        return null
    }

    private fun stopServer() {
        server.stop()
        boundLan = null
        hosts = emptySet()
        _address.value = null
    }

    private fun guarded(path: String, auth: Boolean, exact: Boolean, handler: HttpHandler): HttpHandler = { req, res ->
        when {
            !hostAllowed(req.headers["host"], hosts) -> res.sendEmpty(421)
            exact && req.path != path -> res.sendEmpty(404)
            req.method != "GET" && !postAllowed(req.headers) -> res.sendError(403, "forbidden")
            auth && !pairing.isPaired(token(req)) -> res.sendError(401, "not_paired")
            else -> {
                // The start-up relock may still be running: nothing answers before it is done.
                if (path.startsWith("/api/")) parental.ready()
                if (auth && path != PIN_PATH && sessions.settingsLocked(token(req))) res.sendPinRequired() else handler(req, res)
            }
        }
    }

    private fun token(req: HttpRequest): String? = cookieValue(req.headers["cookie"], PHONE_COOKIE)

    @Suppress("UNUSED_PARAMETER")
    private suspend fun page(req: HttpRequest, res: HttpResponse) {
        val bytes = asset(PAGE_ASSET)?.use { it.readBytes() } ?: return res.sendEmpty(404)
        res.start(200, "text/html; charset=utf-8", bytes.size.toLong(), PAGE_HEADERS)
        res.body.write(bytes)
    }

    private suspend fun status(req: HttpRequest, res: HttpResponse) {
        val paired = pairing.isPaired(token(req))
        val tv = tvName()
        val showing = pairing.code.value != null
        res.sendJson(200) {
            beginObject()
            name("paired").value(paired)
            name("tv").value(tv)
            name("showing").value(showing)
            endObject()
        }
    }

    private suspend fun pair(req: HttpRequest, res: HttpResponse) {
        val code = try {
            readCode(req)
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        } ?: return res.sendError(400, "bad_request")
        when (val result = pairing.pair(code, req.remote, req.headers["user-agent"])) {
            is PairResult.Paired -> res.sendJson(200, mapOf("Set-Cookie" to cookie(result.token, COOKIE_MAX_AGE))) {
                beginObject()
                name("paired").value(true)
                endObject()
            }
            PairResult.WrongCode -> res.sendError(403, "wrong_code")
            PairResult.NotShowing -> res.sendError(409, "not_showing")
            PairResult.TooMany -> res.sendError(429, "too_many")
        }
    }

    /** `{"code":"1234"}` → "1234"; null without a string code. */
    private fun readCode(req: HttpRequest): String? {
        var code: String? = null
        req.jsonBody().use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "code") code = r.nextString() else r.skipValue()
            }
            r.endObject()
        }
        return code
    }

    private suspend fun forget(req: HttpRequest, res: HttpResponse) {
        token(req)?.let {
            sessions.forget(it)
            pairing.forget(it)
        }
        res.sendJson(200, mapOf("Set-Cookie" to cookie("", 0))) {
            beginObject()
            name("paired").value(false)
            endObject()
        }
    }

    /** `{"pin":"1234"}`: 200 `{"ok":true}`, 403 `{"ok":false}` or 429 `{"wait":N}`. */
    private suspend fun pin(req: HttpRequest, res: HttpResponse) {
        if (req.path != PIN_PATH) return res.sendError(404, "not_found")
        val token = token(req) ?: return res.sendError(401, "not_paired")
        val pin = try {
            readPin(req)
        } catch (_: IOException) {
            return res.sendError(400, "bad_request")
        } catch (_: JsonDataException) {
            return res.sendError(400, "bad_request")
        }
        when (val result = sessions.enter(token, pin)) {
            PhonePin.Ok -> res.sendJson(200) {
                beginObject()
                name("ok").value(true)
                endObject()
            }
            PhonePin.Wrong -> res.sendJson(403) {
                beginObject()
                name("ok").value(false)
                endObject()
            }
            is PhonePin.Wait -> res.sendJson(429) {
                beginObject()
                name("wait").value(result.seconds)
                endObject()
            }
        }
    }

    /** The string `pin` of the body, or null when it is missing or not a string. */
    private fun readPin(req: HttpRequest): String? {
        var pin: String? = null
        req.jsonBody().use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "pin" && r.peek() == JsonReader.Token.STRING) pin = r.nextString() else r.skipValue()
            }
            r.endObject()
        }
        return pin
    }

    private fun cookie(value: String, maxAge: Int) =
        "$PHONE_COOKIE=$value; HttpOnly; SameSite=Strict; Path=/; Max-Age=$maxAge"

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val PAGE_ASSET = "phone/index.html"
        const val PAIR_MAX_BODY = 256L
        const val PIN_PATH = "/api/pin"
        const val PIN_MAX_BODY = 256L
        const val BODY_TIMEOUT_MS = 10_000L
        const val COOKIE_MAX_AGE = 31_536_000
        val PAGE_HEADERS = mapOf(
            "Content-Security-Policy" to
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )
    }
}
