package io.github.loopholetv.setup

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.cast.net.LocalHttpServer
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * This TV's side of copying a setup between TVs: the `/setup/` routes on the local server and the
 * one request slot. Only one copy runs on a TV at a time, in either direction, so a second offer
 * or ask while the slot is taken is refused as busy.
 *
 * - `POST /setup/offer` `{"from": castId}`: another TV wants to send its setup here. This TV makes
 *   a [SetupRequest] and shows its code; the reply is `{"id": requestId}`.
 * - `POST /setup/ask` `{"from": castId, "id": requestId}`: another TV (which shows the code) asks
 *   for this TV's setup. This TV then asks for the code. A TV with no playlists replies 409
 *   `{"error": "no_playlists"}` instead (a busy one replies 409 `{"error": "busy"}`).
 * - `POST /setup/bundle?id=`: the sealed setup for this TV's request. Nothing is imported unless
 *   [SetupRequest.tryOpen] accepts it.
 * - `POST /setup/cancel?id=` `{"reason": "cancelled" | "expired"}`: the other TV ended the copy.
 *
 * Who is asking is checked on every route: offers and asks must come from a TV in the peer list,
 * from that TV's own address, and the rest from the address of the TV the request is with. The
 * name shown is always the peer list's. Offers and asks are rate-limited per address, and every
 * wrong code counts against the address that sent it, however its request ends.
 *
 * Every way a received setup can fail (a short body, too little memory, an error while opening,
 * decoding or importing it) frees the slot, so a copy can never be left stuck.
 *
 * Nothing here logs: codes, request ids, bodies and exception messages never leave this class.
 */
class SetupHost internal constructor(
    private val peerById: (String) -> CastPeer?,
    /** Whether some TV in the peer list has this address; checked before an offer or ask is read. */
    private val isPeerHost: (String) -> Boolean,
    private val clock: () -> Long,
    private val stageFile: () -> Pair<File, OutputStream>?,
    private val import: suspend (SetupBundle) -> SetupImportResult,
    private val listener: Listener,
    /** Whether this TV has a playlist; one without any is asked for its setup in vain. */
    private val hasPlaylists: suspend () -> Boolean,
    private val lifetimeMs: Long = SetupRequest.LIFETIME_MS,
    private val newCode: () -> String = { SetupCrypto.newCode() },
    private val newId: () -> String = ::newRequestId,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    /** Heap that can still be allocated, in bytes. */
    private val availableMemory: () -> Long = ::availableHeap,
    /** Allocates the buffer a sealed setup is read into. */
    private val newBuffer: (Int) -> ByteArray = ::ByteArray,
    /** Asks for a garbage collection before a short heap is believed. */
    private val collectGarbage: () -> Unit = { System.gc() },
) {
    /** Called with [lock] held: must not block. */
    internal interface Listener {
        /** Another TV offered its setup: show [slot]'s code. */
        fun offered(slot: Slot.Receiving)

        /** Another TV asked for this TV's setup: ask for the code it shows. */
        fun asked(slot: Slot.Sending)

        /** The right code arrived; the import is starting. */
        fun copying(slot: Slot.Receiving)

        /** The import committed and the other TV has its answer. */
        fun copied(slot: Slot.Receiving, result: SetupImportResult.Ok)

        /** The copy ended without one: by the other TV, by wrong codes, by time or by a failure. */
        fun ended(slot: Slot, reason: End)
    }

    internal enum class End(val wire: String) { CANCELLED("cancelled"), EXPIRED("expired"), FAILED("failed") }

    /** The copy this TV is part of. Never printed with its id or code. */
    internal sealed class Slot(val peer: CastPeer) {
        abstract val id: String?

        /** This TV is the one being overwritten and shows [request]'s code. */
        class Receiving(peer: CastPeer, val request: SetupRequest, val offered: Boolean) : Slot(peer) {
            override val id: String get() = request.id

            /** Set once the right code arrived; the import then can't be cancelled. */
            var accepted = false
            override fun toString() = "Receiving(offered=$offered, accepted=$accepted)"
        }

        /** This TV sends its setup; [id] is the other TV's request, once known. */
        class Sending(peer: CastPeer, override var id: String?) : Slot(peer) {
            /** Wrong-code replies so far, counted here whatever the other TV says is left. */
            var wrongCodes = 0
            override fun toString() = "Sending(known=${id != null}, wrongCodes=$wrongCodes)"
        }
    }

    private class Gone(val reason: String, val host: String)

    internal val lock = Any()
    private var slot: Slot? = null
    private var bundleInFlight = false

    /** While true (picture-in-picture), new offers and asks are refused as busy. Set under [lock]. */
    internal var paused = false
    private val gone = object : LinkedHashMap<String, Gone>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Gone>?) = size > MAX_GONE
    }
    private val lastRequestAt = HashMap<String, Long>()
    private val wrongGuesses = HashMap<String, MutableList<Long>>()
    private val throttledUntil = HashMap<String, Long>()
    private val blockedIds = HashSet<String>()

    /** Adds the routes to [server]. Call once. */
    fun register(server: LocalHttpServer) {
        server.route("POST", OFFER, SMALL_BODY, SMALL_BODY_TIMEOUT_MS) { req, res -> guarded(res) { offer(req, res) } }
        server.route("POST", ASK, SMALL_BODY, SMALL_BODY_TIMEOUT_MS) { req, res -> guarded(res) { ask(req, res) } }
        server.route("POST", BUNDLE, MAX_BUNDLE_BYTES) { req, res -> bundle(req, res) }
        server.route("POST", CANCEL, SMALL_BODY, SMALL_BODY_TIMEOUT_MS) { req, res -> guarded(res) { cancel(req, res) } }
    }

    override fun toString() = "SetupHost(${synchronized(lock) { slot }})"

    // For the controller (all under [lock]).

    internal fun current(): Slot? = synchronized(lock) { slot }

    /** Takes the slot to receive from [peer] (this TV asked); null while it is taken. */
    internal fun startReceiving(peer: CastPeer): Slot.Receiving? = synchronized(lock) {
        if (slot != null || bundleInFlight) return null
        val now = clock()
        Slot.Receiving(peer, SetupRequest(newId(), newCode(), clock, now + lifetimeMs), offered = false)
            .also { slot = it }
    }

    /** Takes the slot to send to [peer]; null while it is taken. */
    internal fun startSending(peer: CastPeer): Slot.Sending? = synchronized(lock) {
        if (slot != null || bundleInFlight) return null
        Slot.Sending(peer, null).also { slot = it }
    }

    /**
     * Frees the slot if [s] still holds it (false otherwise, or once a received setup is being
     * imported). [notify] tells the listener; the controller passes false for its own endings.
     */
    internal fun end(s: Slot, reason: End, notify: Boolean = false): Boolean = synchronized(lock) {
        if (slot !== s) return false
        if (s is Slot.Receiving && s.accepted) return false
        release(s, reason, notify)
        true
    }

    /** Frees the slot if [s] holds it, even mid-import: for failures nothing else will clean up. */
    internal fun abandon(s: Slot, notify: Boolean): Boolean = synchronized(lock) {
        if (slot !== s) return false
        release(s, End.FAILED, notify)
        true
    }

    private fun release(s: Slot, reason: End, notify: Boolean) {
        slot = null
        if (s is Slot.Receiving) s.request.cancel()
        s.id?.let { gone[it] = Gone(reason.wire, s.peer.host) }
        if (notify) listener.ended(s, reason)
    }

    /** Ends a successful send: the other TV used the request. */
    internal fun finish(s: Slot.Sending): Boolean = synchronized(lock) {
        if (slot !== s) return false
        slot = null
        s.id?.let { gone[it] = Gone(USED, s.peer.host) }
        true
    }

    /** Refuses offers and asks from [peer] (by its id) until the app restarts. */
    internal fun block(peer: CastPeer) = synchronized(lock) {
        blockedIds += peer.id
    }

    // Routes

    /** A reply decided under [lock] and sent after it. */
    private class Answer(val code: Int, vararg val fields: Pair<String, Any>)

    /** Turns anything unexpected (out of memory included) into a 500 instead of an escaped error. */
    private suspend fun guarded(res: HttpResponse, route: suspend () -> Unit) {
        try {
            route()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            replyIfUnanswered(res, Answer(500))
        } catch (_: OutOfMemoryError) {
            replyIfUnanswered(res, Answer(500))
        }
    }

    private suspend fun offer(req: HttpRequest, res: HttpResponse) {
        val host = hostOf(req) ?: return reply(res, Answer(403))
        synchronized(lock) { screen(host) }?.let { return reply(res, it) }
        val body = readSmall(req)?.let(SetupWire::parse) ?: return reply(res, Answer(400))
        val from = body.string("from") ?: return reply(res, Answer(400))
        reply(res, synchronized(lock) { takeOffer(from, host) })
    }

    private fun takeOffer(from: String, host: String): Answer {
        val peer = when (val a = admit(from, host)) {
            is Admit.No -> return Answer(a.code)
            is Admit.Ok -> a.peer
        }
        if (paused || slot != null || bundleInFlight) return Answer(409, ERROR to BUSY)
        if (tooSoon(host)) return Answer(429)
        val now = clock()
        lastRequestAt[host] = now
        val s = Slot.Receiving(peer, SetupRequest(newId(), newCode(), clock, now + lifetimeMs), offered = true)
        slot = s
        listener.offered(s)
        return Answer(200, "id" to s.request.id)
    }

    private suspend fun ask(req: HttpRequest, res: HttpResponse) {
        val host = hostOf(req) ?: return reply(res, Answer(403))
        synchronized(lock) { screen(host) }?.let { return reply(res, it) }
        val body = readSmall(req)?.let(SetupWire::parse) ?: return reply(res, Answer(400))
        val from = body.string("from") ?: return reply(res, Answer(400))
        val id = body.string("id")?.takeIf { ID.matches(it) } ?: return reply(res, Answer(400))
        // Read before the lock (it is a database query), but only answered once the ask is admitted.
        val has = hasPlaylists()
        reply(res, synchronized(lock) { takeAsk(from, host, id, has) })
    }

    private fun takeAsk(from: String, host: String, id: String, hasPlaylists: Boolean): Answer {
        val peer = when (val a = admit(from, host)) {
            is Admit.No -> return Answer(a.code)
            is Admit.Ok -> a.peer
        }
        if (paused || slot != null || bundleInFlight) return Answer(409, ERROR to BUSY)
        if (tooSoon(host)) return Answer(429)
        lastRequestAt[host] = clock()
        // Sending an empty setup would only clear the other TV's playlists.
        if (!hasPlaylists) return Answer(409, ERROR to NO_PLAYLISTS)
        val s = Slot.Sending(peer, id)
        slot = s
        listener.asked(s)
        return Answer(200)
    }

    /** What can be refused before an offer or ask body is read: a throttled or unknown address. */
    private fun screen(host: String): Answer? {
        if (throttled(host)) return Answer(429)
        if (!isPeerHost(host)) return Answer(403)
        return null
    }

    private sealed interface Admit {
        class Ok(val peer: CastPeer) : Admit
        class No(val code: Int) : Admit
    }

    /**
     * An address throttled after wrong codes is refused first; then the request must come from a
     * known TV, from its own address, and not one this TV blocked. The gap between requests from
     * one address ([tooSoon]) is checked after the busy check, so a busy TV says so.
     */
    private fun admit(from: String, host: String): Admit {
        if (throttled(host)) return Admit.No(429)
        val peer = peerById(from) ?: return Admit.No(403)
        if (peer.host != host) return Admit.No(403)
        if (peer.id in blockedIds) return Admit.No(403)
        return Admit.Ok(peer)
    }

    private fun throttled(host: String): Boolean {
        val until = throttledUntil[host] ?: return false
        if (clock() < until) return true
        throttledUntil.remove(host)
        return false
    }

    private fun tooSoon(host: String): Boolean =
        lastRequestAt[host]?.let { clock() - it < MIN_REQUEST_GAP_MS } ?: false

    /**
     * A wrong code from [host]. Counted per address in a sliding window, whether its request then
     * runs out of tries, is cancelled or expires; enough of them throttle the address.
     */
    private fun recordWrongGuess(host: String) {
        val now = clock()
        val recent = wrongGuesses.getOrPut(host) { ArrayList() }
        recent.removeAll { now - it > WRONG_GUESS_WINDOW_MS }
        recent += now
        if (recent.size >= WRONG_GUESSES_BEFORE_THROTTLE) {
            throttledUntil[host] = now + THROTTLE_MS
            recent.clear()
        }
    }

    private sealed interface Gate {
        class Refuse(val answer: Answer, val drain: Boolean = false) : Gate
        class Go(val slot: Slot.Receiving) : Gate
    }

    private suspend fun bundle(req: HttpRequest, res: HttpResponse) {
        val host = hostOf(req) ?: return reply(res, Answer(403))
        val id = req.query["id"] ?: return reply(res, Answer(404))
        when (val gate = synchronized(lock) { gateBundle(id, host) }) {
            is Gate.Refuse -> guarded(res) {
                // The TV the request was with gets its answer even for a large body.
                if (gate.drain) withContext(work) { drain(req) }
                reply(res, gate.answer)
            }
            is Gate.Go -> try {
                receive(gate.slot, id, host, req, res)
            } catch (e: CancellationException) {
                abandon(gate.slot, notify = true)
                throw e
            } catch (_: Exception) {
                abandon(gate.slot, notify = true)
                replyIfUnanswered(res, Answer(500, ERROR to End.FAILED.wire))
            } catch (_: OutOfMemoryError) {
                abandon(gate.slot, notify = true)
                replyIfUnanswered(res, Answer(500, ERROR to End.FAILED.wire))
            } finally {
                synchronized(lock) { bundleInFlight = false }
            }
        }
    }

    /**
     * Only the request this TV is waiting on, from the TV it is with, gets past here: nothing of
     * the body has been read and nothing allocated before that.
     */
    private fun gateBundle(id: String, host: String): Gate {
        val current = slot as? Slot.Receiving
        if (current == null || current.request.id != id) {
            val ended = gone[id] ?: return Gate.Refuse(Answer(404))
            return Gate.Refuse(Answer(410, ERROR to ended.reason), drain = ended.host == host)
        }
        if (current.peer.host != host) return Gate.Refuse(Answer(403))
        if (bundleInFlight || current.accepted) return Gate.Refuse(Answer(409, ERROR to BUSY))
        bundleInFlight = true
        return Gate.Go(current)
    }

    private suspend fun receive(s: Slot.Receiving, id: String, host: String, req: HttpRequest, res: HttpResponse) {
        val length = req.bodyLength
        if (length < MIN_SEALED_BYTES) return reply(res, Answer(400))
        // Opening a sealed setup needs room for it and for what it decrypts to.
        if (!enoughMemory(length, availableMemory, collectGarbage)) {
            abandon(s, notify = true)
            return reply(res, Answer(413, ERROR to End.FAILED.wire))
        }
        val outcome = withContext(work) {
            // One array of exactly the body's size, dropped right after the attempt.
            val sealed = try {
                readFully(req, newBuffer(length.toInt()))
            } catch (_: IOException) {
                null
            }
            sealed?.let { s.request.tryOpen(it) }
        } ?: return reply(res, Answer(400))
        when (outcome) {
            is SetupRequest.Outcome.WrongCode -> {
                synchronized(lock) { recordWrongGuess(host) }
                reply(res, Answer(409, ERROR to WRONG_CODE, "left" to outcome.left))
            }
            SetupRequest.Outcome.Cancelled -> {
                val reason = synchronized(lock) {
                    // Still ours: that was the last try. Otherwise it was ended here meanwhile.
                    if (end(s, End.CANCELLED, notify = true)) recordWrongGuess(host)
                    gone[id]?.reason ?: End.CANCELLED.wire
                }
                reply(res, Answer(410, ERROR to reason))
            }
            SetupRequest.Outcome.Expired -> {
                synchronized(lock) { end(s, End.EXPIRED, notify = true) }
                reply(res, Answer(410, ERROR to End.EXPIRED.wire))
            }
            is SetupRequest.Outcome.Accepted -> {
                val stillOurs = synchronized(lock) {
                    // Cancelled here between the attempt and now: nothing is imported.
                    (slot === s).also { ours ->
                        if (ours) {
                            s.accepted = true
                            listener.copying(s)
                        }
                    }
                }
                if (!stillOurs) {
                    outcome.plain.fill(0)
                    return reply(res, Answer(410, ERROR to (synchronized(lock) { gone[id]?.reason } ?: End.CANCELLED.wire)))
                }
                withContext(NonCancellable) { importAccepted(s, id, outcome.plain, res) }
            }
        }
    }

    private suspend fun importAccepted(s: Slot.Receiving, id: String, plain: ByteArray, res: HttpResponse) {
        val bundle = try {
            withContext(work) { SetupJson.decode(ByteArrayInputStream(plain), stageFile) }
        } finally {
            plain.fill(0)
        }
        if (bundle == null) {
            abandon(s, notify = true)
            return reply(res, Answer(400, ERROR to BAD_BUNDLE))
        }
        val result = try {
            withContext(work) { import(bundle) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SetupImportResult.Failed
        } catch (_: OutOfMemoryError) {
            SetupImportResult.Failed
        }
        if (result !is SetupImportResult.Ok) {
            withContext(work) { bundle.discardFiles() }
            abandon(s, notify = true)
            return reply(res, Answer(500, ERROR to End.FAILED.wire))
        }
        try {
            reply(res, Answer(200))
        } catch (_: IOException) {
            // The sending TV went away; the setup is in anyway.
        }
        synchronized(lock) {
            if (slot === s) slot = null
            gone[id] = Gone(USED, s.peer.host)
            listener.copied(s, result)
        }
    }

    private suspend fun cancel(req: HttpRequest, res: HttpResponse) {
        val host = hostOf(req) ?: return reply(res, Answer(403))
        val id = req.query["id"] ?: return reply(res, Answer(404))
        // Who is asking is checked before the body is read; the body only carries the reason.
        synchronized(lock) { checkCancel(id, host) }?.let { return reply(res, it) }
        val reason = readSmall(req)?.let(SetupWire::parse)?.string("reason")
        reply(res, synchronized(lock) { takeCancel(id, host, reason) })
    }

    /** Null when [host] may cancel request [id] now. */
    private fun checkCancel(id: String, host: String): Answer? {
        val current = slot
        if (current == null || current.id != id) {
            val ended = gone[id] ?: return Answer(404)
            return Answer(410, ERROR to ended.reason)
        }
        if (current.peer.host != host) return Answer(403)
        if (current is Slot.Receiving && current.accepted) return Answer(409, ERROR to BUSY)
        return null
    }

    private fun takeCancel(id: String, host: String, reason: String?): Answer {
        checkCancel(id, host)?.let { return it }
        val current = slot ?: return Answer(404)
        end(current, if (reason == End.EXPIRED.wire) End.EXPIRED else End.CANCELLED, notify = true)
        return Answer(200)
    }

    // Plumbing

    private fun hostOf(req: HttpRequest): String? = ipv4Text(req.remote)

    /** A small JSON body, or null when it can't be read. */
    private suspend fun readSmall(req: HttpRequest): ByteArray? {
        if (req.bodyLength !in 1..SMALL_BODY) return null
        return withContext(work) {
            try {
                readFully(req, ByteArray(req.bodyLength.toInt()))
            } catch (_: IOException) {
                null
            }
        }
    }

    private fun readFully(req: HttpRequest, out: ByteArray): ByteArray {
        var n = 0
        while (n < out.size) {
            val r = req.body.read(out, n, out.size - n)
            if (r < 0) throw EOFException()
            n += r
        }
        return out
    }

    private fun drain(req: HttpRequest) {
        val buf = ByteArray(64 * 1024)
        try {
            while (req.body.read(buf) >= 0) Unit
        } catch (_: IOException) {
            // The reply goes out anyway.
        }
    }

    private suspend fun reply(res: HttpResponse, answer: Answer) {
        val bytes = SetupWire.write(*answer.fields)
        withContext(work) {
            res.start(answer.code, SetupWire.JSON, bytes.size.toLong())
            res.body.write(bytes)
            res.body.flush()
        }
    }

    /** Best effort, for failure paths: the reply may have started, or the peer gone. */
    private suspend fun replyIfUnanswered(res: HttpResponse, answer: Answer) {
        try {
            withContext(NonCancellable) { reply(res, answer) }
        } catch (_: Exception) {
            // Already answered, or nobody to answer.
        }
    }

    companion object {
        const val OFFER = "/setup/offer"
        const val ASK = "/setup/ask"
        const val BUNDLE = "/setup/bundle"
        const val CANCEL = "/setup/cancel"

        /** The largest sealed setup a TV takes. */
        const val MAX_BUNDLE_BYTES = 64L * 1024 * 1024
        const val SMALL_BODY = 4L * 1024

        /** How long an offer, ask or cancel body may take to arrive. */
        const val SMALL_BODY_TIMEOUT_MS = 5_000L

        /** The least kept free beyond a sealed setup and its decrypted copy, for the rest of the app. */
        const val MIN_MEMORY_HEADROOM_BYTES = 4L * 1024 * 1024

        /**
         * Whether a sealed setup of [length] bytes can be opened: room for it, its decrypted copy
         * and as much again (at least [MIN_MEMORY_HEADROOM_BYTES]) for the rest of the app. Free heap
         * counts garbage not yet collected as used, so a short reading is taken again after one
         * [collect] before the answer is no.
         */
        internal fun enoughMemory(length: Long, available: () -> Long, collect: () -> Unit): Boolean {
            val needed = length * 2 + maxOf(MIN_MEMORY_HEADROOM_BYTES, length)
            if (available() >= needed) return true
            collect()
            return available() >= needed
        }

        /** At most one offer or ask per address in this time. */
        const val MIN_REQUEST_GAP_MS = 30_000L

        /**
         * This many wrong codes from one address within [WRONG_GUESS_WINDOW_MS] throttle it for
         * [THROTTLE_MS]: two requests' worth of tries.
         */
        const val WRONG_GUESSES_BEFORE_THROTTLE = 2 * SetupRequest.MAX_ATTEMPTS
        const val WRONG_GUESS_WINDOW_MS = 10 * 60_000L
        const val THROTTLE_MS = 10 * 60_000L

        internal const val ERROR = "error"
        internal const val BUSY = "busy"
        internal const val WRONG_CODE = "wrong_code"

        /** A 409 to an ask: this TV has no playlists, so there is no setup to send. */
        internal const val NO_PLAYLISTS = "no_playlists"
        internal const val BAD_BUNDLE = "bad_bundle"
        internal const val USED = "used"

        /** Salt, nonce and tag: nothing shorter can be a sealed setup. */
        private const val MIN_SEALED_BYTES = 16 + 12 + 16
        private const val MAX_GONE = 32
        internal val ID = Regex("""[A-Za-z0-9_-]{16,64}""")

        private val random = SecureRandom()

        /** 128 random bits as 32 hex digits. */
        internal fun newRequestId(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** The heap this process can still allocate. */
        internal fun availableHeap(): Long {
            val rt = Runtime.getRuntime()
            return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        }

        /**
         * The dotted IPv4 text of [address], written plainly or IPv4-mapped (`::ffff:a.b.c.d`), the
         * form the peer list keeps; null for any other address.
         */
        internal fun ipv4Text(address: InetAddress): String? = when (address) {
            is Inet4Address -> address.hostAddress
            is Inet6Address -> {
                val b = address.address
                val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
                if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)).hostAddress else null
            }
            else -> null
        }
    }
}

/** The small JSON bodies of the setup routes. */
internal object SetupWire {
    const val JSON = "application/json"

    class Fields(private val values: Map<String, Any?>) {
        fun string(key: String): String? = values[key] as? String
        fun int(key: String): Int? = (values[key] as? String)?.toIntOrNull()
        override fun toString() = "Fields(${values.keys})"
    }

    fun write(vararg fields: Pair<String, Any>): ByteArray {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { w ->
            w.beginObject()
            for ((k, v) in fields) {
                w.name(k)
                when (v) {
                    is Int -> w.value(v.toLong())
                    is Long -> w.value(v)
                    is Boolean -> w.value(v)
                    else -> w.value(v.toString())
                }
            }
            w.endObject()
        }
        return buffer.readByteArray()
    }

    /** Null unless [bytes] is one JSON object. Numbers and strings are kept as text, other values dropped. */
    fun parse(bytes: ByteArray): Fields? = try {
        JsonReader.of(Buffer().write(bytes)).use { r ->
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return null
            val values = HashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                values[key] = when (r.peek()) {
                    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> r.nextString()
                    else -> { r.skipValue(); null }
                }
            }
            r.endObject()
            Fields(values)
        }
    } catch (_: Exception) {
        null
    }
}
