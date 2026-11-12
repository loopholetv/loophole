package io.github.loopholetv.cast.relay

import io.github.loopholetv.player.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as lockWith

/** Outcome of [HlsRelay.playlist] and [HlsRelay.item]. */
sealed interface HlsResult {
    data class Ok(val contentType: String, val bytes: ByteArray) : HlsResult

    /** An id this relay never issued (or no longer serves), or a relay that is closed. */
    data object NotFound : HlsResult

    /** The provider answered with [code], or the request failed (502) or the playlist is unsupported (502). */
    data class Upstream(val code: Int) : HlsResult
}

/** The most the HLS relay keeps in memory for segments, maps and keys, whatever the device. */
const val HLS_CACHE_MAX_BYTES = 48L * 1024 * 1024

/**
 * The HLS relay's cache budget on a device whose app heap is [memoryClassMb] MB (as
 * `ActivityManager.memoryClass` reports it): a sixth of the heap, at most [HLS_CACHE_MAX_BYTES].
 */
fun hlsCacheBytes(memoryClassMb: Int): Long =
    minOf(HLS_CACHE_MAX_BYTES, memoryClassMb.coerceAtLeast(0).toLong() * 1024 * 1024 / 6)

/**
 * Re-serves one provider HLS stream under opaque ids. Playlists are rebuilt from attribute
 * allowlists so they carry no provider URL, host or credential; only ids issued here can be
 * fetched, so it is not an open proxy. Every upstream request goes through one lock (one
 * connection at a time) and each segment, map and key is fetched once however many clients ask.
 *
 * Id grammar: [playlist] takes `index` (or `index.m3u8`) for the entry playlist and `p-<n>.m3u8`
 * for a variant; the rewritten playlists emit relative URIs `p-<n>.m3u8`, `s-<n>` (segment),
 * `m-<n>` (fMP4 init map) and `k-<n>` (key). A master playlist is reduced to one variant: the
 * highest bandwidth at 1080 lines or below (else the highest), with its renditions.
 *
 * Switching streams: call [release] on the old relay, which suspends until its upstream request
 * is closed, before creating the next relay. [close] only cancels without waiting. After either,
 * [playlist] and [item] return [HlsResult.NotFound].
 */
class HlsRelay(
    private val http: OkHttpClient,
    private val upstream: ResolvedStream,
    private val clock: () -> Long,
    private val cacheBytes: Long = HLS_CACHE_MAX_BYTES,
    firstIndex: Prefetched? = null,
) : Closeable {

    /**
     * The entry playlist as the caller already fetched it from [upstream]'s URL ([finalUrl] after
     * redirects). It answers the first `index` request, so a single-use link isn't spent twice.
     */
    class Prefetched(val bytes: ByteArray, val finalUrl: String) {
        override fun toString() = "Prefetched(${bytes.size} bytes)"
    }

    private sealed interface Fetch {
        class Got(val bytes: ByteArray, val contentType: String?, val finalUrl: HttpUrl) : Fetch
        class Failed(val code: Int) : Fetch
    }

    private class CachedPlaylist(val bytes: ByteArray, val expiresAt: Long)

    private class Rewritten(val bytes: ByteArray?, val ttlMs: Long)

    private class SegRef(val url: String)

    private class Variant(val attrs: Map<String, String>, val target: String)

    /** The segment ids of one media playlist and when each was last listed. */
    private class PlaylistState(val lastSeen: HashMap<String, Long>)

    /** Issues ids per stable key and remembers the newest [max] of them; the URL is refreshed on reuse. */
    private class IdTable(
        private val prefix: String,
        private val suffix: String,
        private val max: Int,
        private val onEvict: (String) -> Unit = {},
    ) {
        private val byKey = LinkedHashMap<String, String>()
        private val keyById = HashMap<String, String>()
        private val urlById = HashMap<String, String>()
        private var next = 1

        fun idFor(key: String, url: String): String {
            byKey[key]?.let {
                urlById[it] = url
                return it
            }
            val id = "$prefix${next++}$suffix"
            byKey[key] = id
            keyById[id] = key
            urlById[id] = url
            while (byKey.size > max) {
                val eldestKey = byKey.keys.first()
                val eldest = byKey.remove(eldestKey)!!
                keyById.remove(eldest)
                urlById.remove(eldest)
                onEvict(eldest)
            }
            return id
        }

        fun urlOf(id: String): String? = urlById[id]
    }

    private val state = Any()
    private val upstreamLock = Mutex()

    // Guarded by [state].
    private var prefetched: Prefetched? = firstIndex
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val callLock = ReentrantLock()
    private val idle = callLock.newCondition()
    private var active: Call? = null

    @Volatile
    private var closed = false

    private val playlists = IdTable("p-", ".m3u8", 32) { evicted ->
        playlistCache.remove(evicted)
        auxByPlaylist.remove(evicted)
        states.remove(evicted)?.lastSeen?.keys?.forEach(::dropSegment)
    }
    private val maps = IdTable("m-", "", 32)
    private val keys = IdTable("k-", "", 32)

    private val playlistCache = HashMap<String, CachedPlaylist>()
    private val auxByPlaylist = HashMap<String, Set<String>>()

    private val states = HashMap<String, PlaylistState>()
    private val segUrls = HashMap<String, String>()
    private val segIdByKey = HashMap<String, String>()
    private val segKeyById = HashMap<String, String>()
    private var nextSegment = 1

    // Access-ordered fetches of the newest segments, bounded by count and by bytes.
    private val segmentFetches = LinkedHashMap<String, Deferred<HlsResult>>(16, 0.75f, true)
    private val segmentSizes = HashMap<String, Int>()
    private val auxFetches = HashMap<String, Deferred<HlsResult>>()

    /** The rewritten playlist for [id]. */
    suspend fun playlist(id: String = "index"): HlsResult {
        if (closed) return HlsResult.NotFound
        val key = playlistKey(id)
        if (synchronized(state) { playlistUrl(key) } == null) return HlsResult.NotFound
        freshPlaylist(key)?.let { return it }
        return upstreamLock.withLock {
            freshPlaylist(key)?.let { return@withLock it }
            val url = synchronized(state) { playlistUrl(key) } ?: return@withLock HlsResult.NotFound
            val fetched = (if (key == "index") takePrefetched() else null)
                ?: withContext(Dispatchers.IO) { fetch(url, MAX_PLAYLIST_BYTES) }
            when (fetched) {
                is Fetch.Failed -> if (closed) HlsResult.NotFound else HlsResult.Upstream(fetched.code)
                is Fetch.Got -> {
                    val rewritten = rewrite(String(fetched.bytes, Charsets.UTF_8), fetched.finalUrl, key)
                    val bytes = rewritten.bytes
                    synchronized(state) {
                        if (bytes == null) {
                            playlistCache.remove(key)
                        } else {
                            playlistCache[key] = CachedPlaylist(bytes, clock() + rewritten.ttlMs)
                        }
                    }
                    if (bytes == null) HlsResult.Upstream(UNSUPPORTED_CODE) else HlsResult.Ok(PLAYLIST_TYPE, bytes)
                }
            }
        }
    }

    /** The prefetched entry playlist, once; callers hold [upstreamLock]. */
    private fun takePrefetched(): Fetch? = synchronized(state) {
        val p = prefetched ?: return null
        prefetched = null
        val base = p.finalUrl.toHttpUrlOrNull() ?: return null
        if (p.bytes.size > MAX_PLAYLIST_BYTES) Fetch.Failed(BAD_GATEWAY) else Fetch.Got(p.bytes, PLAYLIST_TYPE, base)
    }

    // Called with [state] held (or from a synchronized block).
    private fun playlistUrl(key: String): String? = if (key == "index") upstream.url else playlists.urlOf(key)

    private fun playlistKey(id: String): String = when {
        id == "index" || id == "index.m3u8" -> "index"
        id.startsWith("p-") && !id.endsWith(".m3u8") -> "$id.m3u8"
        else -> id
    }

    private fun freshPlaylist(key: String): HlsResult? = synchronized(state) {
        playlistCache[key]?.takeIf { clock() < it.expiresAt }?.let { HlsResult.Ok(PLAYLIST_TYPE, it.bytes) }
    }

    /** A segment, init map or key by id. */
    suspend fun item(id: String): HlsResult {
        if (closed) return HlsResult.NotFound
        val deferred = synchronized(state) {
            val store: MutableMap<String, Deferred<HlsResult>>
            val url: String?
            when {
                id.startsWith("s-") -> { url = segUrls[id]; store = segmentFetches }
                id.startsWith("m-") -> { url = maps.urlOf(id); store = auxFetches }
                id.startsWith("k-") -> { url = keys.urlOf(id); store = auxFetches }
                else -> return HlsResult.NotFound
            }
            if (url == null) return HlsResult.NotFound
            store.getOrPut(id) { scope.async(start = CoroutineStart.LAZY) { fetchItem(id, url) } }
        }
        deferred.start()
        val result = try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            HlsResult.NotFound
        } catch (e: Exception) {
            HlsResult.Upstream(BAD_GATEWAY)
        }
        synchronized(state) {
            if (result !is HlsResult.Ok) {
                if (segmentFetches[id] === deferred) segmentFetches.remove(id)
                if (auxFetches[id] === deferred) auxFetches.remove(id)
            } else if (id.startsWith("s-") && segmentFetches[id] === deferred) {
                account(id, result.bytes.size)
            }
        }
        return result
    }

    /** Cancels the in-flight upstream call without waiting; use [release] when switching streams. */
    override fun close() {
        callLock.lockWith {
            closed = true
            active?.cancel()
        }
        scope.cancel()
    }

    /** Cancels the in-flight upstream call and suspends until its connection has been released. */
    suspend fun release() {
        close()
        withContext(NonCancellable + Dispatchers.IO) {
            callLock.lockWith {
                while (active != null) idle.await()
            }
        }
    }

    // Called with [state] held.
    private fun account(id: String, size: Int) {
        if (segmentSizes.containsKey(id)) return
        if (size > cacheBytes) {
            segmentFetches.remove(id)
            return
        }
        segmentSizes[id] = size
        val iterator = segmentFetches.keys.iterator()
        while (segmentFetches.size > MIN_KEPT &&
            (segmentFetches.size > SEGMENT_CACHE || segmentSizes.values.sumOf { it.toLong() } > cacheBytes)
        ) {
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            iterator.remove()
            segmentSizes.remove(eldest)
        }
    }

    private fun dropSegment(id: String) {
        segUrls.remove(id)
        segKeyById.remove(id)?.let { segIdByKey.remove(it) }
        segmentFetches.remove(id)
        segmentSizes.remove(id)
    }

    private suspend fun fetchItem(id: String, url: String): HlsResult = upstreamLock.withLock {
        when (val fetched = withContext(Dispatchers.IO) { fetch(url, MAX_ITEM_BYTES) }) {
            is Fetch.Failed -> if (closed) HlsResult.NotFound else HlsResult.Upstream(fetched.code)
            is Fetch.Got -> HlsResult.Ok(fetched.contentType ?: defaultType(id), fetched.bytes)
        }
    }

    /** One upstream request, read completely; callers hold [upstreamLock]. Nothing here is logged. */
    private fun fetch(url: String, limit: Long): Fetch {
        val call = callLock.lockWith {
            if (closed) return Fetch.Failed(BAD_GATEWAY)
            http.newCall(request(url)).also { active = it }
        }
        try {
            return call.execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful) return@use Fetch.Failed(resp.code)
                if (body == null || body.contentLength() > limit) return@use Fetch.Failed(BAD_GATEWAY)
                val source = body.source()
                if (source.request(limit + 1)) return@use Fetch.Failed(BAD_GATEWAY)
                Fetch.Got(source.readByteArray(), resp.header("Content-Type"), resp.request.url)
            }
        } catch (e: IOException) {
            return Fetch.Failed(BAD_GATEWAY)
        } finally {
            callLock.lockWith {
                active = null
                idle.signalAll()
            }
        }
    }

    private fun request(url: String): Request {
        val builder = Request.Builder().url(url)
        upstream.headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", upstream.userAgent)
        return builder.build()
    }

    private fun rewrite(raw: String, base: HttpUrl, playlistId: String): Rewritten {
        val text = raw.removePrefix(BOM)
        if (text.contains("{$") || text.contains("EXT-X-DEFINE")) return UNSUPPORTED
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull()?.let { tagOf(it) } != "#EXTM3U") return UNSUPPORTED
        return if (lines.any { tagOf(it) == "#EXT-X-STREAM-INF" }) {
            rewriteMaster(lines, base)
        } else {
            rewriteMedia(lines, base, playlistId)
        }
    }

    private fun tagOf(line: String) = line.substringBefore(':').trim()

    private fun valueOf(line: String) = line.substringAfter(':', "").trim()

    /** Every playlist line is printed here from validated values; nothing from the provider is copied. */
    private fun rewriteMaster(lines: List<String>, base: HttpUrl): Rewritten {
        val variants = ArrayList<Variant>()
        val medias = ArrayList<Map<String, String>>()
        var pending: Map<String, String>? = null
        var version: String? = null
        var independent = false
        for (line in lines.drop(1)) {
            if (!line.startsWith("#")) {
                val attrs = pending
                pending = null
                val target = base.resolve(line)?.toString()
                if (attrs != null && target != null && INT.matches(attrs["BANDWIDTH"] ?: "")) variants += Variant(attrs, target)
                continue
            }
            when (tagOf(line)) {
                "#EXT-X-STREAM-INF" -> pending = attributes(line)
                "#EXT-X-MEDIA" -> medias += attributes(line)
                "#EXT-X-VERSION" -> if (INT.matches(valueOf(line))) version = valueOf(line)
                "#EXT-X-INDEPENDENT-SEGMENTS" -> independent = true
            }
        }
        val chosen = chooseVariant(variants) ?: return UNSUPPORTED
        val out = ArrayList<String>()
        out += "#EXTM3U"
        version?.let { out += "#EXT-X-VERSION:$it" }
        if (independent) out += "#EXT-X-INDEPENDENT-SEGMENTS"
        val groupIds = HashMap<String, String>()
        fun group(type: String, name: String) = groupIds.getOrPut("$type:$name") { "g-${groupIds.size + 1}" }
        val wanted = GROUP_ATTRS.mapNotNull { (attr, type) -> chosen.attrs[attr]?.let { type to it } }.toSet()
        val names = HashMap<String, Int>()
        synchronized(state) {
            for (media in medias) {
                val type = media["TYPE"]
                if (type !in MEDIA_TYPES) continue
                val name = media["GROUP-ID"] ?: continue
                if ((type!! to name) !in wanted) continue
                val parts = ArrayList<String>()
                parts += "TYPE=$type"
                parts += "GROUP-ID=\"${group(type, name)}\""
                val count = names.merge(type, 1, Int::plus)!!
                parts += "NAME=\"${MEDIA_NAMES.getValue(type)} $count\""
                media["LANGUAGE"]?.takeIf { LANGUAGE.matches(it) }?.let { parts += "LANGUAGE=\"$it\"" }
                for (flag in listOf("DEFAULT", "AUTOSELECT", "FORCED")) {
                    media[flag]?.takeIf { it == "YES" || it == "NO" }?.let { parts += "$flag=$it" }
                }
                media["CHANNELS"]?.takeIf { CHANNELS.matches(it) }?.let { parts += "CHANNELS=\"$it\"" }
                media["INSTREAM-ID"]?.takeIf { INSTREAM.matches(it) }?.let { parts += "INSTREAM-ID=\"$it\"" }
                val uriAttr = media["URI"]
                if (uriAttr != null) {
                    val target = base.resolve(uriAttr)?.toString() ?: continue
                    parts += "URI=\"${playlists.idFor(stableKey(target), target)}\""
                }
                out += "#EXT-X-MEDIA:" + parts.joinToString(",")
            }
            val parts = ArrayList<String>()
            val a = chosen.attrs
            parts += "BANDWIDTH=${a.getValue("BANDWIDTH")}"
            a["AVERAGE-BANDWIDTH"]?.takeIf { INT.matches(it) }?.let { parts += "AVERAGE-BANDWIDTH=$it" }
            a["CODECS"]?.takeIf { CODECS.matches(it) }?.let { parts += "CODECS=\"$it\"" }
            a["RESOLUTION"]?.takeIf { RESOLUTION.matches(it) }?.let { parts += "RESOLUTION=$it" }
            a["FRAME-RATE"]?.takeIf { DECIMAL.matches(it) }?.let { parts += "FRAME-RATE=$it" }
            a["VIDEO-RANGE"]?.takeIf { it in VIDEO_RANGES }?.let { parts += "VIDEO-RANGE=$it" }
            for ((attr, type) in GROUP_ATTRS) {
                val value = a[attr] ?: continue
                if (attr == "CLOSED-CAPTIONS" && value == "NONE") parts += "CLOSED-CAPTIONS=NONE" else parts += "$attr=\"${group(type, value)}\""
            }
            out += "#EXT-X-STREAM-INF:" + parts.joinToString(",")
            out += playlists.idFor(stableKey(chosen.target), chosen.target)
        }
        return finish(out, MASTER_TTL_MS)
    }

    /** Highest bandwidth at 1080 lines or below, else the highest; the first wins a tie. */
    private fun chooseVariant(variants: List<Variant>): Variant? {
        fun bandwidth(v: Variant) = v.attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
        fun height(v: Variant) = v.attrs["RESOLUTION"]?.takeIf { RESOLUTION.matches(it) }?.substringAfter('x')?.toIntOrNull()
        val fitting = variants.filter { (height(it) ?: 0) <= MAX_HEIGHT }
        return (fitting.ifEmpty { variants }).fold(null as Variant?) { best, v ->
            if (best == null || bandwidth(v) > bandwidth(best)) v else best
        }
    }

    private fun finish(out: List<String>, ttlMs: Long): Rewritten {
        if (out.any { it.contains("://") }) return UNSUPPORTED
        return Rewritten(join(out), ttlMs)
    }

    private fun rewriteMedia(lines: List<String>, base: HttpUrl, playlistId: String): Rewritten {
        if (lines.any { tagOf(it) == "#EXT-X-BYTERANGE" }) return UNSUPPORTED
        var targetSeconds = 0L
        var firstSequence: Long? = null
        var windowSeconds = 0.0
        val out = ArrayList<Any>()
        out += "#EXTM3U"
        val aux = HashSet<String>()
        synchronized(state) {
            for (line in lines.drop(1)) {
                if (!line.startsWith("#")) {
                    out += SegRef(base.resolve(line)?.toString() ?: return UNSUPPORTED)
                    continue
                }
                val tag = tagOf(line)
                val value = valueOf(line)
                when (tag) {
                    "#EXT-X-VERSION", "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-BITRATE" ->
                        if (INT.matches(value)) out += "$tag:$value"
                    "#EXT-X-TARGETDURATION" -> {
                        targetSeconds = value.takeIf { INT.matches(it) }?.toLongOrNull() ?: return UNSUPPORTED
                        out += "$tag:$targetSeconds"
                    }
                    "#EXT-X-MEDIA-SEQUENCE" -> {
                        val n = value.takeIf { INT.matches(it) }?.toLongOrNull() ?: return UNSUPPORTED
                        firstSequence = n
                        out += "$tag:$n"
                    }
                    "#EXT-X-PLAYLIST-TYPE" -> if (value == "VOD" || value == "EVENT") out += "$tag:$value"
                    "#EXT-X-PROGRAM-DATE-TIME" -> if (INSTANT.matches(value)) out += "$tag:$value"
                    "#EXT-X-START" -> startLine(value)?.let { out += it }
                    "#EXT-X-DISCONTINUITY", "#EXT-X-ENDLIST", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-GAP" -> out += tag
                    "#EXTINF" -> {
                        val duration = EXTINF_DURATION.find(value)?.value ?: "0"
                        windowSeconds += maxOf(duration.toDoubleOrNull() ?: 0.0, 0.0)
                        out += "#EXTINF:$duration,"
                    }
                    "#EXT-X-MAP" -> {
                        val attrs = attributes(line)
                        if (attrs.containsKey("BYTERANGE")) return UNSUPPORTED
                        val id = auxId(attrs["URI"], base, maps, aux) ?: return UNSUPPORTED
                        out += "#EXT-X-MAP:URI=\"$id\""
                    }
                    "#EXT-X-KEY" -> out += keyLine(attributes(line), base, aux) ?: return UNSUPPORTED
                    else -> {}
                }
            }
            auxByPlaylist[playlistId] = aux
            auxFetches.keys.retainAll(auxByPlaylist.values.flatten().toSet())
            val finished = assignSegments(out, playlistId, firstSequence, targetSeconds, windowSeconds)
            val ttl = if (targetSeconds > 0) targetSeconds * 500 else DEFAULT_TTL_MS
            return finish(finished, ttl)
        }
    }

    private fun startLine(value: String): String? {
        val attrs = attributes("#EXT-X-START:$value")
        val offset = attrs["TIME-OFFSET"]?.takeIf { SIGNED_DECIMAL.matches(it) } ?: return null
        val precise = attrs["PRECISE"]?.takeIf { it == "YES" || it == "NO" }
        return "#EXT-X-START:TIME-OFFSET=$offset" + (precise?.let { ",PRECISE=$it" } ?: "")
    }

    /** The key tag reprinted from validated values; null when it cannot be represented. */
    private fun keyLine(attrs: Map<String, String>, base: HttpUrl, aux: MutableSet<String>): String? {
        val method = attrs["METHOD"] ?: return null
        if (method == "NONE") return "#EXT-X-KEY:METHOD=NONE"
        if (method !in KEY_METHODS) return null
        val id = auxId(attrs["URI"], base, keys, aux) ?: return null
        val parts = arrayListOf("METHOD=$method", "URI=\"$id\"")
        attrs["IV"]?.let { if (IV.matches(it)) parts += "IV=$it" else return null }
        attrs["KEYFORMAT"]?.let { if (KEYFORMAT.matches(it)) parts += "KEYFORMAT=\"$it\"" else return null }
        attrs["KEYFORMATVERSIONS"]?.takeIf { KEYFORMAT_VERSIONS.matches(it) }?.let { parts += "KEYFORMATVERSIONS=\"$it\"" }
        return "#EXT-X-KEY:" + parts.joinToString(",")
    }

    /** Issues the id for an http(s) URI attribute; null when it is missing or not http(s). */
    private fun auxId(uri: String?, base: HttpUrl, table: IdTable, aux: MutableSet<String>): String? {
        val target = base.resolve(uri ?: return null)?.toString() ?: return null
        return table.idFor(stableKey(target), target).also { aux += it }
    }

    /**
     * Replaces the [SegRef] placeholders with ids that stay the same while a playlist lists the
     * segment, however its URL token rotates; an id lives for a grace period after it leaves the list.
     */
    private fun assignSegments(
        out: List<Any>,
        playlistId: String,
        firstSequence: Long?,
        targetSeconds: Long,
        windowSeconds: Double,
    ): List<String> {
        val now = clock()
        val current = HashSet<String>()
        val seen = HashMap<String, Int>()
        var sequence = firstSequence ?: 0L
        val lines = out.map { part ->
            if (part is String) return@map part
            val url = (part as SegRef).url
            val name = url.substringBefore('?').substringAfterLast('/')
            val base = if (firstSequence != null) "$playlistId#${sequence++}|$name" else "$playlistId|$name"
            val repeat = seen.merge(base, 1, Int::plus)!!
            val key = if (repeat == 1) base else "$base#$repeat"
            val id = segIdByKey[key] ?: "s-${nextSegment++}".also {
                segIdByKey[key] = it
                segKeyById[it] = key
            }
            segUrls[id] = url
            current += id
            id
        }
        val graceMs = maxOf(2 * targetSeconds * 1000, (windowSeconds * 1000).toLong(), DEFAULT_TTL_MS)
        val lastSeen = states.getOrPut(playlistId) { PlaylistState(HashMap()) }.lastSeen
        current.forEach { lastSeen[it] = now }
        val expired = lastSeen.filter { it.key !in current && now - it.value > graceMs }.keys
        expired.forEach { lastSeen.remove(it); dropSegment(it) }
        return lines
    }

    /** Parses `TAG:A=1,B="x,y"` into unquoted, trimmed values; quoted values may contain commas. */
    private fun attributes(line: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(line.substringAfter(':', "")).forEach {
            result.putIfAbsent(it.groupValues[1], it.groupValues[2].trim().removeSurrounding("\""))
        }
        return result
    }

    private fun join(lines: List<String>) = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

    /**
     * Identity of an upstream URL that survives rotating tokens: the path without token-like
     * segments, no host, no query.
     */
    private fun stableKey(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?')
        return parsed.pathSegments.filterNot(::tokenLike).joinToString("/")
    }

    private fun tokenLike(segment: String) =
        segment.length >= TOKEN_LENGTH && segment.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
            segment.any { it.isDigit() } && segment.any { it.isLetter() }

    private fun defaultType(id: String) = when {
        id.startsWith("s-") -> "video/mp2t"
        id.startsWith("m-") -> "video/mp4"
        else -> "application/octet-stream"
    }

    private companion object {
        val BOM = 0xFEFF.toChar().toString()
        const val PLAYLIST_TYPE = "application/vnd.apple.mpegurl"
        const val SEGMENT_CACHE = 12
        const val MIN_KEPT = 2
        const val MAX_HEIGHT = 1080
        const val TOKEN_LENGTH = 20
        const val BAD_GATEWAY = 502
        const val UNSUPPORTED_CODE = 502
        const val MAX_ITEM_BYTES = 32L * 1024 * 1024
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val DEFAULT_TTL_MS = 3_000L
        const val MASTER_TTL_MS = 30_000L
        val ATTRIBUTE = Regex("([A-Za-z0-9-]+)\\s*=\\s*(\"[^\"]*\"|[^,\"]*)")
        val UNSUPPORTED = Rewritten(null, 0L)
        val INT = Regex("\\d+")
        val DECIMAL = Regex("\\d+(\\.\\d+)?")
        val SIGNED_DECIMAL = Regex("-?\\d+(\\.\\d+)?")
        val EXTINF_DURATION = Regex("^-?\\d+(\\.\\d+)?")
        val RESOLUTION = Regex("\\d+x\\d+")
        val CODECS = Regex("[A-Za-z0-9.,\\-]+")
        val LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")
        val CHANNELS = Regex("[0-9/A-Z\\-]+")
        val INSTREAM = Regex("CC[1-4]|SERVICE\\d{1,2}")
        val IV = Regex("0[xX][0-9A-Fa-f]{1,32}")
        val KEYFORMAT = Regex("identity|com\\.apple\\.streamingkeydelivery|urn:uuid:[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")
        val KEYFORMAT_VERSIONS = Regex("[0-9/]+")
        val INSTANT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}(:?\\d{2})?)")
        val KEY_METHODS = setOf("AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR")
        val MEDIA_TYPES = setOf("AUDIO", "SUBTITLES", "CLOSED-CAPTIONS", "VIDEO")
        val MEDIA_NAMES = mapOf("AUDIO" to "Audio", "SUBTITLES" to "Subtitles", "CLOSED-CAPTIONS" to "Captions", "VIDEO" to "Video")
        val VIDEO_RANGES = setOf("SDR", "HLG", "PQ")
        val GROUP_ATTRS = listOf("AUDIO" to "AUDIO", "SUBTITLES" to "SUBTITLES", "CLOSED-CAPTIONS" to "CLOSED-CAPTIONS")
    }
}
