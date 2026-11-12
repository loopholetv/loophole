package io.github.loopholetv.cast

import androidx.annotation.VisibleForTesting
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.cast.relay.HLS_CACHE_MAX_BYTES
import io.github.loopholetv.cast.relay.HlsRelay
import io.github.loopholetv.cast.relay.HlsResult
import io.github.loopholetv.cast.relay.TsReader
import io.github.loopholetv.cast.relay.TsRelay
import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.SWITCH_RETRY_HTTP_CODES
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One cast run by this TV. [token] is the secret in every relay path; [toString] leaves it out. */
data class CastSession(val castId: String, val token: String, val startedAtMs: Long, val epoch: Int) {
    override fun toString() = "CastSession(castId=$castId, token=***, startedAtMs=$startedAtMs, epoch=$epoch)"
}

/**
 * Shares what this TV plays with up to [MAX_FOLLOWERS] other TVs over a single provider connection.
 *
 * While a cast runs, [open] is the only thing that talks to the provider: it closes the previous
 * upstream, opens the new one, and hands back a loopback stream for this TV's own player, which
 * reads through the relay like every follower. Routes live under `/c/<token>/` on [server]:
 *
 * - `GET events?id=<follower>`: newline-delimited [CastEvent] JSON. The current [CastEvent.Playing]
 *   or [CastEvent.Idle] comes first, then a [CastEvent.Heartbeat] every [heartbeatMs].
 * - `GET <epoch>.ts`: the MPEG-TS relay. `GET <epoch>/<id>`: the HLS relay (`index.m3u8` first).
 * - `POST leave?id=<follower>`, and `POST alive?id=<follower>`, which a follower sends for each
 *   heartbeat. A follower silent for [followerSilenceMs] is dropped: a TV that lost power or its
 *   network never closes its events stream, and would otherwise be counted for minutes.
 *
 * A provider refusal reaches this TV's player with the provider's own status, so its retry and
 * re-mint logic keep working; a busy-style one is asked again once first, since the provider may
 * still count the connection just released. A failed connection reads as 503. The relay's own refusals use codes
 * that logic ignores: 401 for a wrong token, 409 for another epoch, 404 for an id never issued,
 * [READERS_FULL] past the reader cap. Nothing here logs a URL, path or token.
 *
 * [clock] is monotonic (relay timing); [wallClock] dates the session so casts from different TVs
 * compare. [positionMs] reports this TV's position in a seekable archive for heartbeats; it is
 * always called on [Dispatchers.Main] (so it may read the player), and a throw counts as null.
 */
class CastHost(
    private val scope: CoroutineScope,
    private val server: LocalHttpServer,
    private val upstreamHttp: OkHttpClient,
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val heartbeatMs: Long = 2_000,
    private val positionMs: () -> Long? = { null },
    /** What this TV's player sends to the relay; never the provider's agent or headers. */
    private val userAgent: String = UserAgents.default(BuildConfig.VERSION_NAME),
    private val tsCapacityBytes: Int = 12 * 1024 * 1024,
    private val joinBehindMs: Long = 2_000,
    /** The HLS relay's cache budget; see [hlsCacheBytes]. */
    private val hlsCacheBytes: Long = HLS_CACHE_MAX_BYTES,
    private val followerSilenceMs: Long = 8_000,
) {
    /** What the relay serves for one epoch of the cast [castId]. */
    private sealed interface Media {
        val castId: String
        val epoch: Int

        class Ts(
            override val castId: String,
            override val epoch: Int,
            val relay: TsRelay,
            val call: Call,
            val response: Response,
        ) : Media {
            lateinit var pump: Job

            /** Set once this upstream is being closed on purpose, so its end isn't announced. */
            @Volatile var released = false

            /**
             * Where this TV's player is in the relay: byte N of its response is stream position
             * `localBase + N`, for N from [localFrom]. Guarded by the host's lock.
             */
            var localBase: Long? = null
            var localFrom = 0L

            /** The stream position a ranged reconnect from this TV's player continues at, if known. */
            fun localResume(offset: Long): Long? = localBase?.takeIf { offset >= localFrom }?.let { it + offset }
        }

        class Hls(override val castId: String, override val epoch: Int, val relay: HlsRelay) : Media

        /** The provider refused (or couldn't be reached): every request for this epoch gets [code]. */
        class Failed(override val castId: String, override val epoch: Int, val code: Int) : Media
    }

    /** [heardAt]: when ([clock]) it joined or last answered a heartbeat. Guarded by the host's lock. */
    private class Follower(val id: String, var heardAt: Long) {
        val events = Channel<CastEvent>(EVENT_BUFFER)
    }

    private enum class Kind { TS, HLS, UNSUPPORTED }

    private sealed interface Probe {
        /** A body still being received: TS keeps reading it, anything else is closed. */
        class Opened(val response: Response, val call: Call, val kind: Kind) : Probe

        /** An HLS entry playlist, read whole so the relay doesn't request the link again. */
        class Playlist(val prefetched: HlsRelay.Prefetched) : Probe

        /** [answered]: the provider itself sent [code], rather than this TV failing to reach it. */
        class Refused(val code: Int, val answered: Boolean = false) : Probe
    }

    private val lock = Any()
    private val switching = Mutex()

    // Guarded by lock.
    private var session: CastSession? = null
    private var localKey = ""
    private var media: Media? = null
    private var current: CastEvent? = null

    /** This TV's player is paused: followers are shown [CastEvent.Idle] in place of [current]'s Playing. */
    private var paused = false
    private val followerMap = LinkedHashMap<String, Follower>()

    /** The open TS reader of each follower that named itself, so a re-open replaces it. Guarded by lock. */
    private val followerReaders = HashMap<String, TsReader>()
    private var heartbeat: Job? = null

    @Volatile private var probing: Call? = null

    /** Runs in [probe] just before its call is published for [stop] to cancel. */
    @VisibleForTesting @Volatile internal var beforePublish: (() -> Unit)? = null

    /** Runs in [stop] once the session has ended, before the upstream is released. */
    @VisibleForTesting @Volatile internal var onStopping: (suspend () -> Unit)? = null

    /** Runs on IO as a probe finishes, before the opening coroutine resumes. */
    @VisibleForTesting @Volatile internal var onProbed: (() -> Unit)? = null

    private val _active = MutableStateFlow<CastSession?>(null)
    val active: StateFlow<CastSession?> = _active.asStateFlow()

    private val _followers = MutableStateFlow(0)
    val followers: StateFlow<Int> = _followers.asStateFlow()

    /** Starts a cast (or returns the one running): a new castId and 128-bit token, routes under `/c/<token>/`. */
    fun start(): CastSession = synchronized(lock) {
        session?.let { return it }
        val s = CastSession(hex(8), hex(16), wallClock(), 0)
        session = s
        localKey = hex(16)
        paused = false
        current = CastEvent.Idle(s.castId)
        _active.value = s
        server.route("GET", ROUTE) { req, res -> handle(req, res) }
        server.route("POST", ROUTE) { req, res -> handle(req, res) }
        heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                beat()
            }
        }
        s
    }

    /**
     * Ends the cast: [CastEvent.Stopped] to every follower, routes removed, and the upstream fully
     * closed before this returns. Playback on this TV is left to the caller.
     */
    suspend fun stop(reason: StopReason) {
        val ended = synchronized(lock) {
            val s = session ?: return@synchronized null
            session = null
            _active.value = null
            heartbeat?.cancel()
            heartbeat = null
            server.unroute(ROUTE)
            paused = false
            val stopped = CastEvent.Stopped(s.castId, reason)
            followerMap.values.forEach {
                it.events.trySend(stopped)
                it.events.close()
            }
            followerMap.clear()
            followerReaders.clear()
            _followers.value = 0
            s
        } ?: return
        onStopping?.invoke()
        // An open still waiting for the provider gives up now instead of holding the switch.
        probing?.cancel()
        switching.withLock { releaseCurrent(onlyFor = ended.castId) }
    }

    /**
     * Switches the relay to [stream] and returns what this TV's player should open: a loopback
     * stream with the app's user agent and no provider headers, or [stream] itself when no cast is
     * running or the format can't be shared (then followers get [CastEvent.Idle] with
     * [CastEvent.Idle.REASON_UNSUPPORTED]).
     *
     * The previous upstream is closed completely before the new one is requested. The provider's
     * response is awaited and its first bytes decide the relay, so [hls] (from the URL) only shapes
     * the loopback path when the provider refuses. An [archive] isn't paced by the provider, so the
     * relay reads it only as fast as this TV's player does.
     */
    suspend fun open(stream: ResolvedStream, hls: Boolean, archive: Boolean = false): ResolvedStream = switching.withLock {
        val castId = synchronized(lock) { session?.castId } ?: return@withLock stream
        releaseCurrent()
        val epoch = synchronized(lock) {
            val s = session?.takeIf { it.castId == castId } ?: return@withLock stream
            s.copy(epoch = s.epoch + 1).also {
                session = it
                _active.value = it
            }.epoch
        }
        val probe = try {
            probeAllowingBusy(castId, stream)
        } catch (e: CancellationException) {
            synchronized(lock) { if (session?.castId == castId) setCurrent(CastEvent.Idle(castId)) }
            throw e
        }
        val next = relayFor(castId, epoch, stream, probe, archive)
        val result = install(castId, epoch, next, stream, hls)
        if (result == null) {
            // The cast ended meanwhile: nothing of this open may stay connected.
            discard(next)
            stream
        } else {
            (next as? Media.Ts)?.pump?.start()
            result
        }
    }

    /**
     * [probe], asked once more after [BUSY_RETRY_MS] when the provider answers with a busy-style
     * status ([SWITCH_RETRY_HTTP_CODES]): it may still count the connection released just before.
     * The second answer stands, as does the first if the cast ended during the wait. Called with
     * [switching] held, so the two requests never overlap.
     */
    private suspend fun probeAllowingBusy(castId: String, stream: ResolvedStream): Probe? {
        val first = probe(castId, stream)
        if (first !is Probe.Refused || !first.answered || first.code !in SWITCH_RETRY_HTTP_CODES) return first
        evictUpstream()
        delay(BUSY_RETRY_MS)
        if (synchronized(lock) { session?.castId } != castId) return first
        return probe(castId, stream)
    }

    private fun current(castId: String): Boolean = synchronized(lock) { session?.castId } == castId

    /**
     * What to serve after [probe]; null when the stream can't be shared. The link is spent once: a TS
     * relay reads on from the probe's own body and an HLS relay starts from the playlist it read.
     * A format that can't be shared is closed, and this TV's player then opens the link itself.
     */
    private suspend fun relayFor(castId: String, epoch: Int, stream: ResolvedStream, probe: Probe?, archive: Boolean): Media? {
        when (probe) {
            null -> return null
            is Probe.Refused -> {
                evictUpstream()
                return Media.Failed(castId, epoch, probe.code)
            }
            is Probe.Playlist -> {
                evictUpstream()
                return Media.Hls(castId, epoch, HlsRelay(upstreamHttp, stream, clock, hlsCacheBytes, firstIndex = probe.prefetched))
            }
            is Probe.Opened -> if (probe.kind == Kind.TS) {
                val relay = TsRelay(tsCapacityBytes, clock, joinBehindMs, paced = archive)
                return Media.Ts(castId, epoch, relay, probe.call, probe.response).also {
                    it.pump = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { pump(it) }
                }
            }
        }
        close(probe as Probe.Opened)
        return null
    }

    /**
     * Makes [next] current and tells followers, all while [castId] is still the cast; returns the
     * stream for this TV's player, or null when the cast has ended.
     */
    private fun install(castId: String, epoch: Int, next: Media?, stream: ResolvedStream, hls: Boolean): ResolvedStream? {
        val port = server.port ?: return null
        return synchronized(lock) {
            val s = session?.takeIf { it.castId == castId } ?: return null
            media = next
            val base = "http://$LOOPBACK:$port/c/${s.token}"
            when (next) {
                is Media.Ts -> {
                    setCurrent(CastEvent.Playing(castId, epoch, "/c/${s.token}/$epoch.ts", hls = false))
                    loopback("$base/$epoch.ts?$LOCAL=$localKey")
                }
                is Media.Hls -> {
                    setCurrent(CastEvent.Playing(castId, epoch, "/c/${s.token}/$epoch/index.m3u8", hls = true))
                    loopback("$base/$epoch/index.m3u8")
                }
                is Media.Failed -> {
                    setCurrent(CastEvent.Idle(castId))
                    loopback(if (hls) "$base/$epoch/index.m3u8" else "$base/$epoch.ts")
                }
                null -> {
                    setCurrent(CastEvent.Idle(castId, CastEvent.Idle.REASON_UNSUPPORTED))
                    stream
                }
            }
        }
    }

    /** Closes a relay that was never installed. */
    private suspend fun discard(next: Media?) {
        when (next) {
            is Media.Ts -> {
                next.pump.cancel()
                next.relay.close()
                withContext(NonCancellable + Dispatchers.IO) {
                    next.call.cancel()
                    next.response.close()
                }
                evictUpstream()
            }
            is Media.Hls -> next.relay.close()
            is Media.Failed, null -> Unit
        }
    }

    /** This TV stopped playing: the upstream is closed and followers get [CastEvent.Idle]. */
    suspend fun idle() {
        switching.withLock {
            val castId = synchronized(lock) { session?.castId } ?: return
            releaseCurrent()
            synchronized(lock) {
                if (session?.castId == castId) setCurrent(CastEvent.Idle(castId))
            }
        }
    }

    /**
     * This TV's player was paused ([paused] true) or plays on (false). While it is paused followers
     * are told [CastEvent.Idle], so they pause too; when it plays on they get the current
     * [CastEvent.Playing] again, same epoch. The upstream stays open throughout, as it does for this
     * TV's own player; a stream opened meanwhile is announced once it plays on.
     */
    fun pause(paused: Boolean) = synchronized(lock) {
        if (session == null || this.paused == paused) return
        this.paused = paused
        val playing = current as? CastEvent.Playing ?: return
        broadcast(shown(playing))
    }

    /**
     * Lets go of the upstream before the caller resolves its next stream (a portal may refuse a new
     * link while the old one is connected). Followers aren't told, since the next [open] announces
     * the new stream, but one that joins meanwhile sees [CastEvent.Idle]. A caller that then opens
     * nothing calls [idle].
     */
    suspend fun detach() {
        switching.withLock {
            val castId = synchronized(lock) { session?.castId } ?: return
            releaseCurrent()
            synchronized(lock) {
                if (session?.castId == castId) current = CastEvent.Idle(castId)
            }
        }
    }

    // Switching

    private fun loopback(url: String) = ResolvedStream(url, userAgent, emptyMap())

    /**
     * Closes whatever the relay has open upstream (only [onlyFor]'s, when given) and returns once its
     * connection is gone, even if the caller is cancelled meanwhile.
     */
    private suspend fun releaseCurrent(onlyFor: String? = null) = withContext(NonCancellable) {
        val old = synchronized(lock) {
            media?.takeIf { onlyFor == null || it.castId == onlyFor }?.also { media = null }
        }
        when (old) {
            is Media.Ts -> {
                old.released = true
                old.call.cancel()
                old.relay.close()
                old.pump.join()
                // In case the pump never ran (its scope was already cancelled).
                withContext(Dispatchers.IO) { old.response.close() }
            }
            is Media.Hls -> old.relay.release()
            is Media.Failed, null -> Unit
        }
        evictUpstream()
    }

    /** Drops the upstream client's idle connections, which a one-connection provider may still count. */
    private suspend fun evictUpstream() = withContext(NonCancellable + Dispatchers.IO) {
        upstreamHttp.connectionPool.evictAll()
    }

    private suspend fun close(opened: Probe.Opened) {
        withContext(NonCancellable + Dispatchers.IO) {
            opened.call.cancel()
            opened.response.close()
        }
        evictUpstream()
    }

    /**
     * Requests [stream] and waits for the provider's answer and enough of the body to tell its kind.
     * Cancelling the caller cancels the request. Null when OkHttp can't request it at all (for
     * example an RTSP or UDP address): the relay can't share that.
     */
    private suspend fun probe(castId: String, stream: ResolvedStream): Probe? {
        if (stream.url.toHttpUrlOrNull() == null) return null
        val request = try {
            request(stream)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val call = upstreamHttp.newCall(request)
        beforePublish?.invoke()
        probing = call
        if (!current(castId)) call.cancel()
        val got = AtomicReference<Response?>()
        try {
            return blocking(call) { probeOnIo(call, got) }
        } catch (e: CancellationException) {
            // Possibly cancelled after the probe finished: the response is live, so cancel the call and
            // close it off the caller's thread (closing an uncancelled body would read from the socket).
            withContext(NonCancellable + Dispatchers.IO) {
                call.cancel()
                got.get()?.close()
            }
            evictUpstream()
            throw e
        } finally {
            probing = null
        }
    }

    /** The blocking part of [probe]; every response it doesn't hand on is closed here, on IO. */
    private fun probeOnIo(call: Call, got: AtomicReference<Response?>): Probe {
        val response = try {
            call.execute()
        } catch (_: IOException) {
            return Probe.Refused(UNREACHABLE)
        }
        got.set(response)
        val probe = try {
            classify(response, call)
        } catch (_: IOException) {
            Probe.Refused(UNREACHABLE)
        }
        if (probe !is Probe.Opened) response.close()
        onProbed?.invoke()
        return probe
    }

    private fun request(stream: ResolvedStream): Request {
        val builder = Request.Builder().url(stream.url)
        stream.headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", stream.userAgent)
        return builder.build()
    }

    /**
     * The body decides: `#EXTM3U` is HLS and sync bytes a packet apart are TS. The content type only
     * breaks a tie: an error page (HTML, JSON, any text, or nothing at all) is a provider failure
     * the player should retry, a playlist type is HLS, and anything else can't be shared.
     */
    private fun classify(response: Response, call: Call): Probe {
        if (!response.isSuccessful) return Probe.Refused(response.code, answered = true)
        val type = response.header("Content-Type").orEmpty().lowercase()
        val source = response.body.source()
        source.request(SNIFF_BYTES)
        val size = minOf(source.buffer.size, SNIFF_BYTES)
        val head = source.buffer.snapshot(size.toInt()).toByteArray()
        return when {
            looksLikePlaylist(head) -> playlist(response)
            looksLikeTs(head) -> Probe.Opened(response, call, Kind.TS)
            looksLikeErrorPage(head, type) -> Probe.Refused(BAD_GATEWAY)
            "mpegurl" in type -> playlist(response)
            else -> Probe.Opened(response, call, Kind.UNSUPPORTED)
        }
    }

    private fun playlist(response: Response): Probe {
        val source = response.body.source()
        if (source.request(MAX_PLAYLIST_BYTES + 1)) return Probe.Refused(BAD_GATEWAY)
        return Probe.Playlist(HlsRelay.Prefetched(source.readByteArray(), response.request.url.toString()))
    }

    /** Runs [block] on IO; cancelling the caller cancels [call], which unblocks the reads in [block]. */
    private suspend fun <T> blocking(call: Call, block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    /** Copies the provider's body into the relay until it ends, fails or is released. */
    private fun pump(ts: Media.Ts) {
        try {
            ts.response.use {
                val source = it.body.source()
                val buf = ByteArray(PUMP_BYTES)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    ts.relay.write(buf, 0, n)
                }
            }
        } catch (_: IOException) {
            // Ended by the provider, the network, or a release; the readers just reach the end.
        } finally {
            ts.relay.close()
            synchronized(lock) {
                val s = session
                if (!ts.released && media === ts && s != null) setCurrent(CastEvent.Idle(s.castId))
            }
        }
    }

    // Events

    /** Called with [lock] held. */
    private fun setCurrent(event: CastEvent) {
        current = event
        broadcast(shown(event))
    }

    /** Called with [lock] held. What followers are told of [event]: a paused stream reads as idle. */
    private fun shown(event: CastEvent): CastEvent =
        if (paused && event is CastEvent.Playing) CastEvent.Idle(event.castId) else event

    /** Called with [lock] held. A follower whose events back up is dropped. */
    private fun broadcast(event: CastEvent) {
        val dropped = followerMap.values.filter { !it.events.trySend(event).isSuccess }
        dropped.forEach {
            it.events.close()
            followerMap.remove(it.id)
        }
        if (dropped.isNotEmpty()) _followers.value = followerMap.size
    }

    private suspend fun beat() {
        // The supplier reads the player, which only allows its own (main) thread.
        val position = withContext(Dispatchers.Main) { runCatching { positionMs() }.getOrNull() }
        synchronized(lock) {
            val s = session ?: return
            dropSilent()
            broadcast(CastEvent.Heartbeat(s.castId, s.epoch, followerMap.size, position))
        }
    }

    /** Called with [lock] held. Followers that stopped answering heartbeats are let go. */
    private fun dropSilent() {
        val now = clock()
        val silent = followerMap.values.filter { now - it.heardAt > followerSilenceMs }
        silent.forEach {
            it.events.close()
            followerMap.remove(it.id)
        }
        if (silent.isNotEmpty()) _followers.value = followerMap.size
    }

    // Routes

    private suspend fun handle(req: HttpRequest, res: HttpResponse) {
        val parts = req.path.removePrefix("$ROUTE/").split('/')
        val s = synchronized(lock) { session }
        if (s == null || !sameSecret(parts[0], s.token)) return res.empty(401)
        val rest = parts.drop(1)
        when {
            req.method == "POST" -> when (rest) {
                listOf("leave") -> leave(req, res)
                listOf("alive") -> alive(req, res)
                else -> res.empty(404)
            }
            rest == listOf("events") -> events(req, res, s)
            rest.size == 1 && rest[0].endsWith(".ts") -> {
                val epoch = rest[0].removeSuffix(".ts").toIntOrNull() ?: return res.empty(404)
                ts(req, res, s, epoch)
            }
            rest.size == 2 -> {
                val epoch = rest[0].toIntOrNull() ?: return res.empty(404)
                hls(res, s, epoch, rest[1])
            }
            else -> res.empty(404)
        }
    }

    /** The media of [s]'s [epoch], or the status that refuses the request. */
    private fun mediaFor(s: CastSession, epoch: Int): Pair<Media?, Int> {
        val m = synchronized(lock) { media }
        return when {
            m == null || m.castId != s.castId || m.epoch != epoch -> null to 409
            m is Media.Failed -> null to m.code
            else -> m to 200
        }
    }

    private fun followerId(req: HttpRequest): String? = req.query["id"]?.takeIf { it.length in 1..MAX_ID }

    private fun leave(req: HttpRequest, res: HttpResponse) {
        val id = followerId(req) ?: return res.empty(400)
        synchronized(lock) {
            followerMap.remove(id)?.events?.close()
            _followers.value = followerMap.size
        }
        res.empty(204)
    }

    /** A follower answered a heartbeat; 404 for one this cast doesn't have (any more). */
    private fun alive(req: HttpRequest, res: HttpResponse) {
        val id = followerId(req) ?: return res.empty(400)
        val known = synchronized(lock) {
            followerMap[id]?.let { it.heardAt = clock() } != null
        }
        res.empty(if (known) 204 else 404)
    }

    private suspend fun events(req: HttpRequest, res: HttpResponse, s: CastSession) {
        val id = followerId(req) ?: return res.empty(400)
        var refusal = 0
        val follower = synchronized(lock) {
            val previous = followerMap[id]
            when {
                session?.castId != s.castId -> { refusal = 401; null }
                previous == null && followerMap.size >= MAX_FOLLOWERS -> { refusal = 503; null }
                else -> Follower(id, clock()).also {
                    previous?.events?.close()
                    current?.let { event -> it.events.trySend(shown(event)) }
                    followerMap[id] = it
                    _followers.value = followerMap.size
                }
            }
        } ?: return res.empty(refusal)
        try {
            res.start(200, EVENTS_TYPE)
            for (event in follower.events) {
                res.body.write((CastJson.event(event) + "\n").toByteArray(Charsets.UTF_8))
                res.body.flush()
            }
        } catch (_: IOException) {
            // The follower went away; it is dropped below.
        } finally {
            synchronized(lock) {
                if (followerMap[id] === follower) {
                    followerMap.remove(id)
                    _followers.value = followerMap.size
                }
            }
            follower.events.close()
        }
    }

    /**
     * The TS relay from about [joinBehindMs] behind live. A `Range: bytes=N-` retry gets 206 from the
     * current join point, so the player doesn't read and discard N bytes. Readers other than this
     * TV's own player are capped at the followers plus two.
     */
    private fun ts(req: HttpRequest, res: HttpResponse, s: CastSession, epoch: Int) {
        val (found, code) = mediaFor(s, epoch)
        val m = found as? Media.Ts ?: return res.empty(if (found == null) code else 404)
        val primary = req.query[LOCAL]?.let { key -> sameSecret(key, synchronized(lock) { localKey }) } == true
        val from = rangeStart(req.headers["range"])
        val id = if (primary) null else followerId(req)
        val reader = synchronized(lock) {
            if (id != null) followerReaders.remove(id)?.close()
            when {
                primary -> localReader(m, from)
                m.relay.readers >= followerMap.size + 2 -> null
                else -> m.relay.reader().also { if (id != null) followerReaders[id] = it }
            }
        } ?: return res.empty(READERS_FULL)
        reader.use {
            if (from == null) {
                res.start(200, TS_TYPE)
            } else {
                res.start(206, TS_TYPE, headers = mapOf("Content-Range" to "bytes $from-*/*"))
            }
            val buf = ByteArray(PUMP_BYTES)
            try {
                while (true) {
                    val n = reader.read(buf, 0, buf.size, READ_WAIT_MS)
                    if (n < 0) break
                    if (n > 0) res.body.write(buf, 0, n)
                }
            } catch (_: IOException) {
                // A dropped slow reader, or the peer went away.
            } finally {
                if (id != null) synchronized(lock) { if (followerReaders[id] === reader) followerReaders.remove(id) }
            }
        }
    }

    /**
     * Called with [lock] held. This TV's player reconnecting with `Range: bytes=N-` continues at its
     * own byte N while that is still in the ring, so it neither skips ahead nor repeats; otherwise it
     * joins afresh and the mapping restarts from there.
     */
    private fun localReader(m: Media.Ts, from: Long?): TsReader {
        val at = from?.let(m::localResume)
        val reader = m.relay.reader(primary = true, at = at)
        if (at == null || reader.startPosition != at || reader.prefixBytes != 0) {
            val offset = from ?: 0L
            m.localBase = reader.startPosition - reader.prefixBytes - offset
            m.localFrom = offset + reader.prefixBytes
        }
        return reader
    }

    /** HLS ids as [HlsRelay] issues them; `*.m3u8` are playlists (`index.m3u8` first), the rest items. */
    private suspend fun hls(res: HttpResponse, s: CastSession, epoch: Int, id: String) {
        val (found, code) = mediaFor(s, epoch)
        val m = found as? Media.Hls ?: return res.empty(if (found == null) code else 404)
        val result = if (id.endsWith(".m3u8")) m.relay.playlist(id) else m.relay.item(id)
        // Switched away while fetching: the answer belongs to an old epoch.
        if (synchronized(lock) { media } !== m) return res.empty(409)
        when (result) {
            is HlsResult.Ok -> {
                res.start(200, result.contentType, result.bytes.size.toLong())
                res.body.write(result.bytes)
            }
            HlsResult.NotFound -> res.empty(404)
            is HlsResult.Upstream -> res.empty(result.code)
        }
    }

    private fun HttpResponse.empty(code: Int) = start(code, null, 0)

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        val out = StringBuilder(bytes * 2)
        b.forEach {
            out.append(HEX[(it.toInt() shr 4) and 0x0F]).append(HEX[it.toInt() and 0x0F])
        }
        return out.toString()
    }

    companion object {
        const val MAX_FOLLOWERS = 3

        /** Too many relay readers for this cast; not a code the player retries or re-mints on. */
        const val READERS_FULL = 423

        private const val ROUTE = "/c"
        private const val LOOPBACK = "127.0.0.1"
        private const val LOCAL = "local"
        private const val UNREACHABLE = 503
        private const val BAD_GATEWAY = 502
        private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        private const val EVENTS_TYPE = "application/x-ndjson"
        private const val TS_TYPE = "video/mp2t"
        private const val EVENT_BUFFER = 32
        private const val MAX_ID = 64
        private const val PUMP_BYTES = 64 * 1024
        private const val READ_WAIT_MS = 8_000L
        private const val BUSY_RETRY_MS = 500L
        private const val PACKET = 188
        private const val SNIFF_BYTES = PACKET * 4L
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private const val HEX = "0123456789abcdef"

        private fun sameSecret(given: String, expected: String): Boolean =
            expected.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

        /** The start of a `bytes=N-` range, or null for none (or one this relay can't honour). */
        internal fun rangeStart(header: String?): Long? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=") ?: return null
            if (!spec.endsWith("-") || spec.contains(',')) return null
            return spec.removeSuffix("-").trim().toLongOrNull()?.takeIf { it >= 0 }
        }

        internal fun looksLikePlaylist(head: ByteArray): Boolean {
            var i = if (head.size >= 3 && head.copyOfRange(0, 3).contentEquals(BOM)) 3 else 0
            while (i < head.size && head[i].toInt().toChar().isWhitespace()) i++
            val tag = "#EXTM3U".toByteArray()
            return head.size - i >= tag.size && head.copyOfRange(i, i + tag.size).contentEquals(tag)
        }

        /** Nothing at all, a text type, or a body that opens like HTML or JSON. */
        internal fun looksLikeErrorPage(head: ByteArray, contentType: String): Boolean {
            if (head.isEmpty()) return true
            if (contentType.startsWith("text/") || "json" in contentType || "html" in contentType) return true
            val text = String(head, Charsets.ISO_8859_1).trimStart().lowercase()
            return text.startsWith("<!doctype html") || text.startsWith("<html") ||
                text.startsWith("{") || text.startsWith("[")
        }

        /** A sync byte at some offset within the first packet, repeated one and two packets later. */
        internal fun looksLikeTs(head: ByteArray): Boolean {
            if (head.size < 3 * PACKET) return head.isNotEmpty() && head[0] == TsRelay.SYNC
            return (0 until minOf(PACKET, head.size - 2 * PACKET)).any { k ->
                head[k] == TsRelay.SYNC && head[k + PACKET] == TsRelay.SYNC && head[k + 2 * PACKET] == TsRelay.SYNC
            }
        }
    }
}
