package io.github.loopholetv.data.stalker

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Token
import io.github.loopholetv.data.xmltv.XmltvChannel
import io.github.loopholetv.data.xmltv.XmltvProgramme
import io.github.loopholetv.util.redact
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Stalker / Ministra MAC portal API client. Stateless apart from re-handshaking a
 * [StalkerSession] in place after an auth-failure body: it never persists anything.
 * `create_link` ([createLink]) is called only by the play path, never to prefetch.
 */
class StalkerClient(
    private val http: OkHttpClient,
    private val timeZone: () -> String = { TimeZone.getDefault().id },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * One handshake/get_profile at a time per MAC: every authenticate
     * path — [authenticate], the in-call re-handshake and [refresh] — runs under this MAC's lock.
     * Different MACs (portals) never wait for each other.
     */
    private val macLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> macLocked(cfg: PortalConfig, block: suspend () -> T): T =
        macLocks.getOrPut(macOf(cfg)) { Mutex() }.withLock { block() }

    /**
     * Re-handshakes [s] in place (handshake → get_profile → get_events) unless someone already did
     * since the caller saw [seenGeneration]. Used by [StalkerSessions] after a retryable auth failure.
     */
    internal suspend fun refresh(s: StalkerSession, cfg: PortalConfig, seenGeneration: Int) {
        macLocked(cfg) {
            if (s.generation == seenGeneration) {
                val fresh = authenticateUnlocked(s.endpoint, cfg, s.token)
                s.token = fresh.token
                s.random = fresh.random
                s.watchdogSeconds = fresh.watchdogSeconds
                s.generation = seenGeneration + 1
            }
        }
    }

    // --- discovery ---

    /**
     * Finds the working API endpoint for a pasted portal address: the pasted URL
     * when it ends in `.php`, then `<base>/portal.php`, `<base>/server/load.php`,
     * `<base>/stalker_portal/server/load.php`, `<base>/c/portal.php`, `<base>/stalker_portal/c/portal.php`.
     * A connection refused / DNS failure stops at once ([StalkerUnreachableException]).
     */
    suspend fun discover(portalUrl: String, mac: String): String {
        val cfg = PortalConfig(portalUrl, mac)
        val candidates = candidates(portalUrl)
        if (candidates.isEmpty()) throw StalkerProtocolException("This isn't a valid portal address")
        var transport: StalkerUnreachableException? = null
        for (candidate in candidates) {
            val params = listOf("type" to "itv", "action" to "get_genres")
            val found = try {
                exchange(candidate, cfg, token = null, params) { body ->
                    when (body) {
                        is Body.Json -> body.reader.peek() == Token.BEGIN_OBJECT && hasJsKey(body.reader)
                        is Body.Text -> classifyText(body.text) != TextKind.OTHER
                    }
                }
            } catch (_: AuthFailure) {
                true // `Authorization failed.`: a full portal, run the handshake
            } catch (_: StalkerAuthException) {
                true // `Access denied.` still comes from a portal
            } catch (_: HttpStatusException) {
                false
            } catch (_: StalkerProtocolException) {
                false
            } catch (e: StalkerUnreachableException) {
                // Refused / unknown host: every candidate shares the host, stop. Otherwise try the next.
                if (e.kind == StalkerUnreachableException.Kind.REFUSED) throw e
                transport = e
                false
            }
            if (found) return candidate
        }
        transport?.let { throw it }
        throw StalkerProtocolException("No MAC portal was found at this address")
    }

    private fun candidates(portalUrl: String): List<String> {
        val raw = portalUrl.trim().let { if (SCHEME.containsMatchIn(it)) it else "http://$it" }
        val url = raw.toHttpUrlOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        val path = url.encodedPath
        if (path.endsWith(".php")) out += url.newBuilder().query(null).fragment(null).build().toString()
        // The parent of `/c/` (or of a pasted `…/server/load.php` / `…/portal.php`) is the base.
        var basePath = path.trimEnd('/')
        basePath = when {
            basePath.endsWith("/server/load.php") -> basePath.removeSuffix("/server/load.php")
            basePath.endsWith(".php") -> basePath.substringBeforeLast('/')
            else -> basePath
        }
        basePath = basePath.removeSuffix("/c")
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        val base = origin + basePath
        out += "$base/portal.php"
        out += "$base/server/load.php"
        if (!basePath.endsWith("/stalker_portal")) out += "$base/stalker_portal/server/load.php"
        out += "$base/c/portal.php"
        if (!basePath.endsWith("/stalker_portal")) out += "$base/stalker_portal/c/portal.php"
        return out.distinct()
    }

    // --- session ---

    /**
     * Handshake (no Authorization, `prehash=0`, the saved token re-sent) → `get_profile` →
     * on status 2 with a login: `do_auth`, then `get_profile auth_second_step=1` →
     * `get_events init=1`. Identity fields go out only when typed.
     */
    suspend fun authenticate(endpoint: String, cfg: PortalConfig, savedToken: String?): StalkerSession =
        macLocked(cfg) { authenticateUnlocked(endpoint, cfg, savedToken) }

    /** Callers hold this MAC's lock. */
    private suspend fun authenticateUnlocked(endpoint: String, cfg: PortalConfig, savedToken: String?): StalkerSession {
        val hs = requireAuthOk {
            exchange(endpoint, cfg, token = null, listOf("type" to "stb", "action" to "handshake",
                "token" to savedToken.orEmpty(), "prehash" to "0")) { jsTree(it) }
        }
        val js = hs as? Map<*, *>
        val token = js?.entries?.firstOrNull { (k, _) -> (k as? String).equals("token", ignoreCase = true) }
            ?.value.str()?.takeIf { it.isNotBlank() }
            ?: savedToken.orEmpty() // a token-less "simple" panel
        val random = js?.get("random").str()
        val notValid = js?.get("not_valid").str() ?: "0"

        var profile = getProfile(endpoint, cfg, token, random, notValid, secondStep = false)
        if (profile.status == 2) {
            val login = cfg.typedLogin ?: throw StalkerAuthException("The portal needs a login and password")
            val ok = requireAuthOk {
                exchange(endpoint, cfg, token, buildList {
                    add("type" to "stb"); add("action" to "do_auth")
                    add("login" to login); add("password" to cfg.typedPassword.orEmpty())
                    cfg.typedDeviceId?.let { add("device_id" to it) }
                    cfg.typedDeviceId2?.let { add("device_id2" to it) }
                }) { jsTree(it) }
            }
            if (!ok.truthy()) throw StalkerAuthException("The portal rejected the login or password")
            profile = getProfile(endpoint, cfg, token, random, notValid, secondStep = true)
        }
        when (profile.status) {
            0 -> Unit
            2 -> throw StalkerAuthException("The portal rejected the login or password")
            else -> throw StalkerAuthException(refusal(profile.message, cfg, token))
        }
        val session = StalkerSession(endpoint, token, random, profile.watchdog)
        // Some forks reject media until the watchdog has run once.
        try {
            events(session, cfg, init = true)
        } catch (_: StalkerProtocolException) {
        }
        return session
    }

    private class Profile(val status: Int, val watchdog: Int, val message: String?)

    private suspend fun getProfile(
        endpoint: String, cfg: PortalConfig, token: String, random: String?, notValid: String, secondStep: Boolean,
    ): Profile {
        val mac = macOf(cfg)
        val metrics = jsonObject(
            "mac" to mac, "sn" to cfg.typedSerial.orEmpty(), "model" to MODEL, "type" to "STB",
            "uid" to cfg.typedDeviceId2.orEmpty(), "random" to random.orEmpty(),
        )
        val params = buildList {
            add("type" to "stb"); add("action" to "get_profile"); add("hd" to "1")
            add("ver" to VER); add("num_banks" to "2")
            cfg.typedSerial?.let { add("sn" to it) }
            add("stb_type" to MODEL); add("client_type" to "STB"); add("image_version" to "218")
            add("video_out" to "hdmi")
            cfg.typedDeviceId?.let { add("device_id" to it) }
            cfg.typedDeviceId2?.let { add("device_id2" to it) }
            cfg.typedSignature?.let { add("signature" to it) }
            add("auth_second_step" to if (secondStep) "1" else "0")
            add("hw_version" to "1.7-BD-00"); add("not_valid_token" to notValid)
            add("metrics" to metrics)
            add("timestamp" to (nowMillis() / 1000).toString())
            add("api_signature" to "262"); add("prehash" to "0")
        }
        val js = requireAuthOk { exchange(endpoint, cfg, token, params) { jsTree(it) } }
        val map = js as? Map<*, *> ?: throw StalkerProtocolException("The portal returned an unexpected profile")
        val status = map["status"].str()?.toDoubleOrNull()?.toInt() ?: 0
        val watchdog = (map["watchdog_timeout"].str()?.toDoubleOrNull()?.toInt() ?: DEFAULT_WATCHDOG)
            .coerceIn(30, 3600)
        val message = (map["block_msg"].str() ?: map["msg"].str())?.takeIf { it.isNotBlank() }
        return Profile(status, watchdog, message)
    }

    private fun refusal(portalText: String?, cfg: PortalConfig, token: String): String {
        val base = "The portal refused this device"
        val text = portalText ?: return base
        return "$base: " + redact(text, stalkerSecrets(cfg, token)).take(200)
    }

    /** `type=watchdog&action=get_events`; send every [StalkerSession.watchdogSeconds] while a portal channel plays. */
    suspend fun watchdog(s: StalkerSession, cfg: PortalConfig) = events(s, cfg, init = false)

    private suspend fun events(s: StalkerSession, cfg: PortalConfig, init: Boolean) {
        val params = listOf("type" to "watchdog", "action" to "get_events", "event_active_id" to "0",
            "init" to if (init) "1" else "0", "cur_play_type" to "1")
        val js = if (init) {
            requireAuthOk { exchange(s.endpoint, cfg, s.token, params) { jsTree(it) } }
        } else {
            authed(s, cfg, params) { jsTree(it) }
        }
        val event = ((js as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("event").str()
        if (event == "cut_off") throw StalkerAuthException("The portal has turned this account off")
    }

    // --- channels ---

    /**
     * Genres (list or object) → `get_all_channels`; when that fails or is empty, paged
     * `get_ordered_list` from `p=1` (never `p=0`) until `total_items` or an empty page, at most
     * [MAX_PAGES] pages. Rows without an id or a name are skipped; ids are emitted once.
     */
    suspend fun channels(s: StalkerSession, cfg: PortalConfig, emit: (StalkerChannel) -> Unit) {
        val genres = try {
            authed(s, cfg, listOf("type" to "itv", "action" to "get_genres")) { genreMap(jsTree(it)) }
        } catch (_: StalkerProtocolException) {
            emptyMap()
        }
        val root = portalRoot(s.endpoint)
        val seen = HashSet<String>()
        val onRow: (Row) -> Unit = { row ->
            val ch = row.toChannel(genres, root)
            if (ch != null && seen.add(ch.id)) emit(ch)
        }
        val all = try {
            authed(s, cfg, listOf("type" to "itv", "action" to "get_all_channels", "force_ch_link_check" to "")) {
                readChannelPage(it, onRow)
            }
        } catch (_: StalkerProtocolException) {
            null
        } catch (e: StalkerUnreachableException) {
            // Big panels time out, reset or truncate the full list: fall back unless the host is gone.
            if (e.kind == StalkerUnreachableException.Kind.REFUSED) throw e else null
        }
        if (all != null && all.rows > 0) return
        var received = 0
        for (page in 1..MAX_PAGES) {
            coroutineContext.ensureActive()
            val result = authed(s, cfg, listOf("type" to "itv", "action" to "get_ordered_list", "genre" to "*",
                "force_ch_link_check" to "", "fav" to "0", "sortby" to "number", "hd" to "0", "p" to "$page")) {
                readChannelPage(it, onRow)
            }
            if (result.rows == 0) break
            received += result.rows
            val total = result.total
            if (total != null && received >= total) break
        }
    }

    private class PageResult(val rows: Int, val total: Int?)

    private class Row(
        val id: String?, val name: String?, val number: String?, val cmd: String?, val genreId: String?,
        val logo: String?, val xmltvId: String?, val tmpLink: Boolean, val loadBalancing: Boolean,
    ) {
        fun toChannel(genres: Map<String, String>, root: String): StalkerChannel? {
            val id = id.blankToNull() ?: return null
            val name = name.blankToNull() ?: return null
            val cmd = cmd?.trim().orEmpty()
            return StalkerChannel(
                id = id, name = name, number = number.toIntLenient(), cmd = cmd,
                genre = genreId?.let { genres[it] }, logo = resolveLogo(root, logo), xmltvId = xmltvId.blankToNull(),
                needsLink = needsCreateLink(tmpLink, loadBalancing, cmd),
            )
        }
    }

    private fun readChannelPage(body: Body, onRow: (Row) -> Unit): PageResult {
        val r = jsReader(body) ?: return PageResult(0, null)
        if (r.peek() != Token.BEGIN_OBJECT) { r.skipValue(); return PageResult(0, null) }
        var rows = 0
        var total: Int? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "total_items" -> total = r.scalar().toIntLenient()
                "data" -> rows += forEachObject(r) { onRow(readRow(it)) }
                "error", "msg" -> checkAuthText(r.scalar())
                else -> r.skipValue()
            }
        }
        r.endObject()
        return PageResult(rows, total)
    }

    private fun readRow(r: JsonReader): Row {
        var id: String? = null; var name: String? = null; var number: String? = null; var cmd: String? = null
        var genre: String? = null; var logo: String? = null; var xmltv: String? = null
        var tmp = false; var lb = false
        var firstCmd: String? = null; var firstCmdTmp = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.scalar()
                "name" -> name = r.scalar()
                "number" -> number = r.scalar()
                "cmd" -> cmd = r.scalar()
                "tv_genre_id" -> genre = r.scalar()
                "logo" -> logo = r.scalar()
                "xmltv_id" -> xmltv = r.scalar()
                "use_http_tmp_link" -> tmp = r.scalar().truthyText()
                "use_load_balancing" -> lb = r.scalar().truthyText()
                "cmds" -> if (r.peek() == Token.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (firstCmd == null && r.peek() == Token.BEGIN_OBJECT) {
                            val f = readFields(r)
                            firstCmd = f["url"]
                            firstCmdTmp = f["use_http_tmp_link"].truthyText()
                        } else {
                            r.skipValue()
                        }
                    }
                    r.endArray()
                } else {
                    r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val useFirst = cmd.isNullOrBlank() && !firstCmd.isNullOrBlank()
        return Row(id, name, number, if (useFirst) firstCmd else cmd, genre, logo, xmltv,
            tmp || (useFirst && firstCmdTmp), lb)
    }

    private fun genreMap(js: Any?): Map<String, String> {
        val items: Collection<*> = when (js) {
            is List<*> -> js
            is Map<*, *> -> js.values
            else -> emptyList<Any>()
        }
        val out = LinkedHashMap<String, String>()
        for (g in items) {
            val m = g as? Map<*, *> ?: continue
            val id = m["id"].str()?.trim() ?: continue
            val title = m["title"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (id != "*") out[id] = title
        }
        return out
    }

    // --- create_link ---

    /**
     * Mints a playable URL for [cmd]: the answer is stripped of its solution prefix,
     * resolved against the portal root and must use http/https/rtsp/rtmp/udp/rtp. Call it only at
     * play time, right before `prepare()`; never cache the result.
     * @throws StalkerLinkException with `limit`, `nothing_to_play`, `link_fault` or `empty`.
     */
    suspend fun createLink(s: StalkerSession, cfg: PortalConfig, cmd: String): String {
        val js = authed(
            s, cfg,
            listOf("type" to "itv", "action" to "create_link"),
            encoded = listOf("cmd" to encodeCmd(cmd), "series" to "", "forced_storage" to "undefined",
                "disable_ad" to "0", "download" to "0"),
        ) { jsTree(it) }
        val (linkCmd, error) = when (js) {
            is Map<*, *> -> js["cmd"].str().orEmpty() to js["error"].str().orEmpty()
            is String -> js to ""
            else -> "" to ""
        }
        if (linkCmd.isBlank()) {
            throw StalkerLinkException(error.trim().takeIf { it in LINK_ERRORS } ?: if (error.isBlank()) "empty" else "link_fault")
        }
        val url = playableUrl(portalRoot(s.endpoint), linkCmd) ?: throw StalkerLinkException("link_fault")
        return repairEmptyStream(url, cmd)
    }

    /** Some reseller panels answer `stream=` empty; copy the id from the source `cmd` (`/ch/590_`). */
    private fun repairEmptyStream(url: String, sourceCmd: String): String {
        if (!EMPTY_STREAM.containsMatchIn(url)) return url
        val id = CH_ID.find(sourceCmd)?.groupValues?.get(1) ?: return url
        return EMPTY_STREAM.replace(url) { "${it.groupValues[1]}stream=$id" }
    }

    // --- EPG ---

    /**
     * `get_epg_info&period=<hours>`, streamed. Accepts `data` keyed by channel id or as a list.
     * Emits an [XmltvChannel] (id = [stalkerTvgId], named from [channels]) once per channel before
     * its first [XmltvProgramme]; programmes without `start_timestamp` (and a stop or duration) are skipped.
     */
    suspend fun epg(
        s: StalkerSession,
        cfg: PortalConfig,
        periodHours: Int,
        channels: Map<String, StalkerChannelMeta> = emptyMap(),
        emit: (Any) -> Unit,
    ) {
        val announced = HashSet<String>()
        val onProgramme: (String?, Map<String, String>) -> Unit = { key, f ->
            val chId = (f["ch_id"] ?: key)?.trim()?.takeIf { it.isNotEmpty() }
            val prog = chId?.let { toProgramme(it, f, channels) }
            if (prog != null) {
                if (announced.add(chId)) {
                    val meta = channels[chId]
                    emit(XmltvChannel(prog.channelId, listOfNotNull(meta?.name.blankToNull()), null))
                }
                emit(prog)
            }
        }
        authed<Unit>(s, cfg, listOf("type" to "itv", "action" to "get_epg_info", "period" to "$periodHours")) { body ->
            val r = jsReader(body) ?: return@authed
            when (r.peek()) {
                Token.BEGIN_ARRAY -> forEachObject(r) { onProgramme(null, readFields(it)) }
                Token.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "data" -> readEpgData(r, onProgramme)
                            "error", "msg" -> checkAuthText(r.scalar())
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
    }

    private fun readEpgData(r: JsonReader, onProgramme: (String?, Map<String, String>) -> Unit) {
        when (r.peek()) {
            Token.BEGIN_ARRAY -> forEachObject(r) { onProgramme(null, readFields(it)) }
            Token.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val key = r.nextName()
                    if (r.peek() == Token.BEGIN_ARRAY) forEachObject(r) { onProgramme(key, readFields(it)) } else r.skipValue()
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }

    private fun toProgramme(chId: String, f: Map<String, String>, channels: Map<String, StalkerChannelMeta>): XmltvProgramme? {
        val start = f["start_timestamp"].toLongLenient() ?: return null
        val stop = f["stop_timestamp"].toLongLenient() ?: f["duration"].toLongLenient()?.let { start + it } ?: return null
        if (stop <= start) return null
        val title = f["name"].blankToNull() ?: return null
        return XmltvProgramme(
            channelId = stalkerTvgId(chId, channels[chId]?.xmltvId),
            start = Instant.ofEpochSecond(start), stop = Instant.ofEpochSecond(stop),
            title = title, subTitle = null, description = f["descr"].blankToNull(), category = f["category"].blankToNull(),
        )
    }

    // --- transport ---

    private sealed interface Body {
        class Json(val reader: JsonReader) : Body
        class Text(val text: String) : Body
    }

    private enum class TextKind { RETRY, DENIED, OTHER }

    /** Retryable auth-failure body; never escapes this class. */
    private class AuthFailure : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class HttpStatusException(code: Int) : Exception("The portal answered HTTP $code")

    /** An authorised call; on an auth-failure body: re-handshake [s] in place, retry once. */
    private suspend fun <T> authed(
        s: StalkerSession,
        cfg: PortalConfig,
        params: List<Pair<String, String>>,
        encoded: List<Pair<String, String>> = emptyList(),
        parse: (Body) -> T,
    ): T {
        val generation = s.generation
        try {
            return httpOk { exchange(s.endpoint, cfg, s.token, params, encoded, parse) }
        } catch (_: AuthFailure) {
        }
        refresh(s, cfg, generation)
        return try {
            httpOk { exchange(s.endpoint, cfg, s.token, params, encoded, parse) }
        } catch (_: AuthFailure) {
            throw StalkerAuthException("The portal rejected this device's session", retryable = true)
        }
    }

    private inline fun <T> requireAuthOk(block: () -> T): T = try {
        httpOk(block)
    } catch (_: AuthFailure) {
        throw StalkerAuthException("The portal rejected this device's session")
    }

    private inline fun <T> httpOk(block: () -> T): T = try {
        block()
    } catch (e: HttpStatusException) {
        throw StalkerProtocolException(e.message.orEmpty())
    }

    private suspend fun <T> exchange(
        endpoint: String,
        cfg: PortalConfig,
        token: String?,
        params: List<Pair<String, String>>,
        encoded: List<Pair<String, String>> = emptyList(),
        parse: (Body) -> T,
    ): T {
        val url = buildUrl(endpoint, params, encoded)
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-User-Agent", X_USER_AGENT)
            .header("Cookie", cookie(cfg))
            .header("Referer", portalRoot(endpoint) + "c/")
            .header("Accept", "*/*")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        val call = http.newCall(request)
        return coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    try {
                        call.execute().use { response ->
                            if (response.code !in 200..299) throw HttpStatusException(response.code)
                            val source = response.body.source()
                            val body = if (startsLikeJson(source)) {
                                Body.Json(JsonReader.of(source).apply { isLenient = true })
                            } else {
                                val text = readSome(source)
                                when (classifyText(text)) {
                                    TextKind.RETRY -> throw AuthFailure()
                                    TextKind.DENIED -> throw StalkerAuthException("The portal denied access to this device")
                                    TextKind.OTHER -> Body.Text(text)
                                }
                            }
                            try {
                                job.ensureActive()
                                parse(body)
                            } catch (e: JsonEncodingException) {
                                throw StalkerProtocolException("The portal did not return valid data")
                            } catch (e: JsonDataException) {
                                throw StalkerProtocolException("The portal returned unexpected data")
                            } catch (e: EOFException) {
                                throw StalkerProtocolException("The portal's answer was cut short")
                            }
                        }
                    } catch (e: IOException) {
                        job.ensureActive()
                        // The raw exception is never attached: its message can carry the URL (MAC, token).
                        throw when (e) {
                            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                                StalkerUnreachableException("Couldn't reach the portal", StalkerUnreachableException.Kind.REFUSED)
                            is InterruptedIOException ->
                                StalkerUnreachableException(TIMEOUT, StalkerUnreachableException.Kind.TIMEOUT)
                            else -> StalkerUnreachableException("The connection to the portal failed")
                        }
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun buildUrl(endpoint: String, params: List<Pair<String, String>>, encoded: List<Pair<String, String>>): HttpUrl {
        val base = endpoint.toHttpUrlOrNull() ?: throw StalkerProtocolException("This isn't a valid portal address")
        val plain = base.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        if (encoded.isEmpty()) return plain.newBuilder().addQueryParameter("JsHttpRequest", "1-xml").build()
        // encodedQuery() keeps `=`, `:` and `/` raw inside values (addEncodedQueryParameter would turn `=`
        // into %3D); the values were pre-encoded by encodeCmd (`&`, `#`, space only).
        val raw = encoded.joinToString("&") { (k, v) -> "$k=$v" }
        val query = listOfNotNull(plain.encodedQuery, raw, "JsHttpRequest=1-xml").joinToString("&")
        return plain.newBuilder().encodedQuery(query).build()
    }

    private fun macOf(cfg: PortalConfig) = normaliseMac(cfg.mac) ?: cfg.mac.trim().uppercase()

    /** MAC uppercase with colons, not URL-encoded; device time zone. */
    private fun cookie(cfg: PortalConfig): String = buildString {
        append("mac=").append(macOf(cfg)).append("; stb_lang=en; timezone=").append(timeZone())
        cfg.typedSerial?.let { append("; sn=").append(it) }
    }

    private fun startsLikeJson(source: BufferedSource): Boolean {
        val peek = source.peek()
        var n = 0
        while (n < 4096 && peek.request(1)) {
            val b = peek.readByte().toInt() and 0xFF
            n++
            if (b == 0xEF || b == 0xBB || b == 0xBF || b.toChar().isWhitespace()) continue // UTF-8 BOM, spaces
            return b == '{'.code || b == '['.code
        }
        return false
    }

    private fun readSome(source: BufferedSource): String {
        source.request(MAX_TEXT)
        return source.buffer.clone().readUtf8(minOf(source.buffer.size, MAX_TEXT))
    }

    private fun classifyText(text: String): TextKind {
        val t = text.trim().lowercase()
        return when {
            t.startsWith("access denied") -> TextKind.DENIED
            isAuthText(t) -> TextKind.RETRY
            else -> TextKind.OTHER
        }
    }

    private fun isAuthText(text: String): Boolean {
        val t = text.trim().lowercase()
        return AUTH_TEXTS.any { t.startsWith(it) }
    }

    private fun checkAuthText(value: String?) {
        if (value != null && isAuthText(value)) throw AuthFailure()
    }

    /** The value of the top-level `js` key, as a tree; JSON-level auth errors become [AuthFailure]. */
    private fun jsTree(body: Body): Any? {
        val r = jsReader(body) ?: throw StalkerProtocolException("The portal returned unexpected data")
        val v = r.readJsonValue()
        if (v is Map<*, *>) {
            checkAuthText(v["error"] as? String)
            checkAuthText(v["msg"] as? String)
        }
        return v
    }

    /** Positions the reader at the value of the top-level `js` key; null when there is none. */
    private fun jsReader(body: Body): JsonReader? {
        val r = when (body) {
            is Body.Json -> body.reader
            is Body.Text -> throw StalkerProtocolException(
                if (body.text.trimStart().startsWith("<")) "The portal returned a web page instead of data"
                else "The portal returned unexpected data",
            )
        }
        if (r.peek() != Token.BEGIN_OBJECT) return null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "js") return r
            r.skipValue()
        }
        return null
    }

    private fun hasJsKey(r: JsonReader): Boolean = jsReader(Body.Json(r)) != null

    /** Calls [each] per object element of an array (`null`/other values skipped); returns the count. */
    private fun forEachObject(r: JsonReader, each: (JsonReader) -> Unit): Int {
        if (r.peek() != Token.BEGIN_ARRAY) { r.skipValue(); return 0 }
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == Token.BEGIN_OBJECT) { each(r); n++ } else r.skipValue()
        }
        r.endArray()
        return n
    }

    private fun readFields(r: JsonReader): Map<String, String> {
        val out = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            r.scalar()?.let { out[name] = it }
        }
        r.endObject()
        return out
    }

    /** A scalar as text (booleans as "1"/"0"); null for null, objects and arrays (skipped). */
    private fun JsonReader.scalar(): String? = when (peek()) {
        Token.STRING, Token.NUMBER -> nextString()
        Token.BOOLEAN -> if (nextBoolean()) "1" else "0"
        Token.NULL -> nextNull<Unit>().let { null }
        else -> { skipValue(); null }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val X_USER_AGENT = "Model: MAG250; Link: WiFi"
        const val MODEL = "MAG250"
        const val VER = "ImageDescription: 0.2.18-r23-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; " +
            "PORTAL version: 5.6.1; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566"
        const val DEFAULT_WATCHDOG = 120
        const val MAX_PAGES = 1000
        const val MAX_TEXT = 2048L
        const val TIMEOUT = "The portal didn't answer in time"
        val LINK_ERRORS = setOf("limit", "nothing_to_play", "link_fault")
        val AUTH_TEXTS = listOf("authorization failed", "unauthorized request", "invalid token", "auth failed")
        val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""")
        val EMPTY_STREAM = Regex("""([?&])stream=(?=&|$)""")
        val CH_ID = Regex("""/ch/(\d+)""")
    }
}

private fun Any?.str(): String? = when (this) {
    null -> null
    is String -> this
    is Double -> if (this % 1.0 == 0.0 && !isInfinite()) toLong().toString() else toString()
    is Number -> toString()
    is Boolean -> if (this) "1" else "0"
    else -> null
}

private fun Any?.truthy(): Boolean = str().truthyText()

private fun String?.truthyText(): Boolean {
    val t = this?.trim()?.lowercase() ?: return false
    return t == "1" || t == "true" || (t.toDoubleOrNull()?.let { it != 0.0 } ?: false)
}

private fun String?.toIntLenient(): Int? {
    val s = this?.trim() ?: return null
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
}

private fun String?.toLongLenient(): Long? {
    val s = this?.trim() ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

private fun jsonObject(vararg pairs: Pair<String, String>): String = pairs.joinToString(",", "{", "}") { (k, v) ->
    "\"$k\":\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
