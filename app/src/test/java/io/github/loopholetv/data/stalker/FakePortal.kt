package io.github.loopholetv.data.stalker

import com.squareup.moshi.Moshi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A synthetic Stalker/Ministra portal for MockWebServer. Only [path] answers as the
 * portal's `load.php`; every other path is a 404 page unless listed in [htmlPaths].
 *
 * Session model: `handshake` always returns the current [token]; every other call must carry
 * `Authorization: Bearer <token>` and the `mac=<expectedMac>` cookie, otherwise the portal answers
 * HTTP 200 `Authorization failed.` / `Unauthorized request.`. [expireToken]
 * rotates the token, as when another device runs get_profile.
 */
internal class FakePortal(@Volatile var path: String = "/stalker_portal/server/load.php") : Dispatcher() {
    @Volatile var expectedMac = "00:1A:79:00:00:01"
    @Volatile var token = "TOKEN-PLACEHOLDER-1"
    /** The key the handshake uses for the token (`token` or `Token`). */
    @Volatile var tokenKey = "token"
    /** Full portal (true): token-less calls get `Authorization failed.`. False: a "simple" panel. */
    @Volatile var requireToken = true
    @Volatile var accessDenied = false
    @Volatile var profileStatus = 0
    @Volatile var profileMsg: String? = null
    @Volatile var loginRequired = false
    @Volatile var expectedLogin = "user1"
    @Volatile var expectedPassword = "pass1"
    @Volatile var profileDelayMillis = 0L
    @Volatile var genresJson: String = fixture("genres_list.json")
    /** null → HTTP 500 for get_all_channels (forces the paged fallback). */
    @Volatile var allChannelsJson: String? = fixture("all_channels.json")
    /** Rows served by get_ordered_list, 14 per page. */
    @Volatile var pagedChannels: List<Map<String, Any?>> = emptyList()
    /** total_items reported by get_ordered_list (null → pagedChannels.size). */
    @Volatile var pagedTotal: Int? = null
    @Volatile var createLinkJson: String = fixture("create_link.json")
    @Volatile var epgJson: String = fixture("epg_keyed.json")
    @Volatile var watchdogJson: String = """{"js":{"data":{"msgs":0,"event":""}}}"""
    /** Paths answering HTTP 200 with an HTML page (a wrong candidate path). */
    val htmlPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Actions that answer `Authorization failed.` exactly once (then rotate nothing). */
    val authFailOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Actions that always answer `Authorization failed.`. */
    val authFailAlways: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    private var tokenCounter = 1

    fun count(action: String): Int = requests.count { it.requestUrl?.queryParameter("action") == action }
    fun ofAction(action: String): List<RecordedRequest> =
        requests.filter { it.requestUrl?.queryParameter("action") == action }

    @Synchronized fun expireToken() { token = "TOKEN-PLACEHOLDER-${++tokenCounter}" }

    /** False after [unbindSession]: the token is unchanged but calls fail until the next get_profile. */
    @Volatile var sessionBound = true
    /** The token is kept (the handshake returns it unchanged) but the server forgot the binding. */
    fun unbindSession() { sessionBound = false }

    private val profilesInFlight = java.util.concurrent.atomic.AtomicInteger()
    /** The most get_profile calls that were ever being served at the same time. */
    val maxProfilesInFlight = java.util.concurrent.atomic.AtomicInteger()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        if (url.encodedPath in htmlPaths) return html()
        if (url.encodedPath != path) return MockResponse().setResponseCode(404).setBody("<html>Not found</html>")
        val action = url.queryParameter("action").orEmpty()
        val cookie = request.getHeader("Cookie").orEmpty()
        if (!cookie.contains("mac=$expectedMac")) return text("Unauthorized request.")
        if (accessDenied) return text("Access denied.")
        if (action == "handshake") {
            return json("""{"js":{"$tokenKey":"$token","random":"0123abcdef","not_valid":0}}""")
        }
        if (action in authFailAlways || authFailOnce.remove(action)) return text("Authorization failed.")
        if (requireToken && request.getHeader("Authorization") != "Bearer $token") return text("Authorization failed.")
        if (requireToken && !sessionBound && action != "get_profile") return text("Authorization failed.")
        return when (action) {
            "get_profile" -> profile(url.queryParameter("auth_second_step") == "1")
            "do_auth" -> {
                val ok = url.queryParameter("login") == expectedLogin && url.queryParameter("password") == expectedPassword
                if (ok) loginRequired = false
                json("""{"js":$ok,"text":""}""")
            }
            "get_genres" -> json(genresJson)
            "get_all_channels" -> allChannelsJson?.let { json(it) } ?: MockResponse().setResponseCode(500).setBody("err")
            "get_ordered_list" -> orderedPage(url.queryParameter("p")?.toIntOrNull() ?: 0)
            "create_link" -> json(createLinkJson)
            "get_epg_info" -> json(epgJson)
            "get_events" -> json(watchdogJson)
            else -> json("""{"js":[]}""")
        }
    }

    private fun profile(secondStep: Boolean): MockResponse {
        val status = if (loginRequired && !secondStep) 2 else profileStatus
        val msg = profileMsg?.let { ""","msg":"$it"""" } ?: ""
        val body = if (status == 0) fixture("profile_ok.json") else """{"js":{"status":$status$msg}}"""
        // Sleeping here (one MockWebServer thread per connection) lets overlapping get_profile calls be counted.
        val now = profilesInFlight.incrementAndGet()
        maxProfilesInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try { if (profileDelayMillis > 0) Thread.sleep(profileDelayMillis) } finally { profilesInFlight.decrementAndGet() }
        if (status == 0) sessionBound = true
        return json(body)
    }

    private fun orderedPage(p: Int): MockResponse {
        val rows = pagedChannels
        val from = ((p.coerceAtLeast(1)) - 1) * PAGE
        val page = if (from >= rows.size) emptyList() else rows.subList(from, minOf(rows.size, from + PAGE))
        val js = mapOf(
            "total_items" to (pagedTotal ?: rows.size), "max_page_items" to PAGE,
            "cur_page" to p, "data" to page,
        )
        return json(adapter.toJson(mapOf("js" to js)))
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "text/javascript")
    private fun text(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "text/html")
    private fun html() = MockResponse().setBody("<html><body>Welcome</body></html>").setHeader("Content-Type", "text/html")

    companion object {
        const val PAGE = 14
        private val adapter = Moshi.Builder().build().adapter(Any::class.java)

        fun fixture(name: String): String =
            FakePortal::class.java.classLoader!!.getResourceAsStream("fixtures/stalker/$name")!!
                .readBytes().decodeToString()

        /** A synthetic paged row. */
        fun row(id: Int): Map<String, Any?> = mapOf(
            "id" to "$id", "name" to "Channel $id", "number" to "$id",
            "cmd" to "ffmpeg http://localhost/ch/${id}_", "tv_genre_id" to "12", "use_http_tmp_link" to "1",
        )
    }
}
