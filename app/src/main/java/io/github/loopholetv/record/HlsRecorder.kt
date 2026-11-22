package io.github.loopholetv.record

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal sealed interface HlsAttach {
    /** The playlist is usable; [extension] is the file extension its segments call for. */
    data class Ready(val extension: String) : HlsAttach
    data object Lost : HlsAttach
    data object Unsupported : HlsAttach
}

internal sealed interface HlsEnd {
    data object Deadline : HlsEnd
    data object EndList : HlsEnd

    /** The playlist could not be fetched (or stopped moving) for too long: the caller should re-open the stream. */
    data object Lost : HlsEnd
    data object Unsupported : HlsEnd
}

/**
 * Follows a live HLS playlist and appends each segment once. Keeps its place across [attach] calls, so a
 * re-opened stream carries on without repeating segments. Segments are fetched with the same request
 * settings as the playlist ([newRequest]).
 */
internal class HlsRecorder(
    private val http: OkHttpClient,
    private val newRequest: (HttpUrl) -> Request,
    private val writer: RecordWriter,
    private val until: () -> Long,
    private val clock: () -> Long,
    private val retryDelay: suspend (Long) -> Unit,
) {
    var gaps = 0
        private set

    private class Key(val method: String, val uri: HttpUrl?, val iv: ByteArray?, val ivInvalid: Boolean)
    private class InitMap(val uri: HttpUrl)
    private class Seg(val seq: Long, val uri: HttpUrl, val key: Key?, val map: InitMap?)
    private class Media(val seqTagged: Boolean, val target: Int?, val end: Boolean, val segs: List<Seg>)

    private var mediaUrl: HttpUrl? = null
    private var pending: Media? = null
    private var nextSeq: Long? = null
    private val seenUris = LinkedHashSet<String>()
    private var initWritten: String? = null
    private val keys = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_KEYS
    }
    private var started = false
    private var lastProgress = clock()

    /** Takes the playlist [body] fetched from [url]; a master playlist is resolved to its best media playlist. */
    suspend fun attach(url: String, body: String): HlsAttach {
        var base = url.toHttpUrlOrNull() ?: return HlsAttach.Unsupported
        var text = body
        if ("#EXT-X-STREAM-INF" in text) {
            base = bestVariant(text, base) ?: return HlsAttach.Unsupported
            text = fetchText(base) ?: return HlsAttach.Lost
        }
        mediaUrl = base
        val media = parse(text, base)
        pending = media
        lastProgress = clock()
        return HlsAttach.Ready(if (media.segs.any { it.map != null }) "mp4" else "ts")
    }

    suspend fun run(): HlsEnd {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (clock() >= until()) return HlsEnd.Deadline
            val media = pending ?: mediaUrl?.let { u -> fetchText(u)?.let { parse(it, u) } }
            pending = null
            if (media == null) {
                if (++failures >= MAX_PLAYLIST_FAILURES) return HlsEnd.Lost
                retryDelay(BACKOFF_MS * failures)
                continue
            }
            failures = 0
            processSegments(media)?.let { return it }
            if (media.end) return HlsEnd.EndList
            if (clock() - lastProgress >= STALL_MS) return HlsEnd.Lost
            retryDelay(maxOf(MIN_POLL_MS, (media.target ?: DEFAULT_TARGET_S) * 500L))
        }
    }

    private suspend fun processSegments(media: Media): HlsEnd? {
        for (seg in select(media)) {
            currentCoroutineContext().ensureActive()
            if (clock() >= until()) return HlsEnd.Deadline
            val key = seg.key
            if (key != null && (key.method != "AES-128" || key.uri == null)) return HlsEnd.Unsupported
            if (writeSegment(seg)) lastProgress = clock() else gaps++
            if (media.seqTagged) nextSeq = seg.seq + 1 else rememberUri(seg)
        }
        return null
    }

    private fun select(media: Media): List<Seg> {
        if (!media.seqTagged) {
            val fresh = media.segs.filter { it.uri.toString() !in seenUris }
            if (started) return fresh
            started = true
            fresh.dropLast(MAX_FIRST_SEGMENTS).forEach(::rememberUri)
            return fresh.takeLast(MAX_FIRST_SEGMENTS)
        }
        val first = media.segs.firstOrNull()?.seq ?: return emptyList()
        val next = nextSeq ?: return media.segs.takeLast(MAX_FIRST_SEGMENTS)
        val last = media.segs.last().seq
        return when {
            last < next -> { // The server restarted its numbering.
                gaps++
                media.segs.takeLast(MAX_FIRST_SEGMENTS)
            }
            first > next -> {
                gaps++
                media.segs.takeLast(MAX_FIRST_SEGMENTS)
            }
            else -> media.segs.filter { it.seq >= next }
        }
    }

    private fun rememberUri(seg: Seg) {
        seenUris.add(seg.uri.toString())
        if (seenUris.size > MAX_SEEN_URIS) seenUris.remove(seenUris.first())
    }

    /** True when the segment was written whole; false when it was skipped. */
    private suspend fun writeSegment(seg: Seg): Boolean {
        seg.map?.let { map ->
            if (map.uri.toString() != initWritten) {
                val init = fetchBytes(map.uri, MAX_INIT_BYTES) ?: return false
                writer.write(init, 0, init.size)
                initWritten = map.uri.toString()
            }
        }
        val key = seg.key ?: return copySegment(seg.uri, null, seg.seq)
        if (key.ivInvalid) return false
        return copySegment(seg.uri, key, seg.seq)
    }

    private suspend fun copySegment(url: HttpUrl, key: Key?, seq: Long): Boolean {
        repeat(SEGMENT_TRIES) { attempt ->
            var started = false
            try {
                val cipher = key?.let { cipherFor(it, seq) ?: return false }
                http.fetch(newRequest(url)) { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val source = resp.body.source()
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = source.read(buf)
                        if (n < 0) break
                        started = true
                        if (cipher == null) {
                            writer.write(buf, 0, n)
                        } else {
                            cipher.update(buf, 0, n)?.let { writer.write(it, 0, it.size) }
                        }
                    }
                    if (cipher != null) cipher.doFinal().let { writer.write(it, 0, it.size) }
                }
                return true
            } catch (e: java.security.GeneralSecurityException) {
                key?.uri?.let { keys.remove(it.toString()) }
                return false
            } catch (e: IOException) {
                if (started) return false // Part of it is already in the file; a retry would repeat it.
                if (attempt + 1 < SEGMENT_TRIES) retryDelay(SEGMENT_RETRY_MS)
            }
        }
        return false
    }

    /** A decrypting cipher for the segment, or null when its key can't be had. */
    private suspend fun cipherFor(key: Key, seq: Long): Cipher? {
        val keyBytes = keyFor(key.uri!!) ?: return null
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(key.iv ?: sequenceIv(seq)))
        }
    }

    private suspend fun keyFor(uri: HttpUrl): ByteArray? {
        keys[uri.toString()]?.let { return it }
        val bytes = fetchBytes(uri, MAX_KEY_BYTES)?.takeIf { it.size == KEY_BYTES } ?: return null
        keys[uri.toString()] = bytes
        return bytes
    }

    private suspend fun fetchText(url: HttpUrl): String? = fetchBytes(url, MAX_PLAYLIST_BYTES)?.toString(Charsets.UTF_8)

    /** The whole body, or null when it can't be fetched or is longer than [limit] (never a truncated body). */
    private suspend fun fetchBytes(url: HttpUrl, limit: Long): ByteArray? = try {
        http.fetch(newRequest(url)) { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val source = resp.body.source()
            if (source.request(limit + 1)) throw IOException("Too large")
            source.buffer.readByteArray()
        }
    } catch (e: IOException) {
        null
    }

    private fun bestVariant(text: String, base: HttpUrl): HttpUrl? {
        val lines = text.lines().map { it.trim() }
        var best: HttpUrl? = null
        var bestBandwidth = -1L
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val target = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bandwidth = attributes(line)["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val resolved = base.resolve(target) ?: continue
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                best = resolved
            }
        }
        return best
    }

    private fun parse(text: String, base: HttpUrl): Media {
        var seq = 0L
        var seqTagged = false
        var target: Int? = null
        var end = false
        var key: Key? = null
        var map: InitMap? = null
        var expectUri = false
        var index = 0L
        val segs = ArrayList<Seg>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    seqTagged = true
                }
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("#EXT-X-ENDLIST") -> end = true
                line.startsWith("#EXT-X-KEY:") -> key = keyOf(attributes(line), base)
                line.startsWith("#EXT-X-MAP:") -> map = attributes(line)["URI"]?.let { base.resolve(it) }?.let { InitMap(it) }
                line.startsWith("#EXTINF:") -> expectUri = true
                line.startsWith("#") -> {}
                expectUri -> {
                    expectUri = false
                    base.resolve(line)?.let { segs += Seg(seq + index, it, key, map) }
                    index++
                }
            }
        }
        return Media(seqTagged, target, end, segs)
    }

    private fun keyOf(attrs: Map<String, String>, base: HttpUrl): Key? {
        val method = attrs["METHOD"] ?: return null
        if (method == "NONE") return null
        val rawIv = attrs["IV"]
        val iv = rawIv?.let(::parseIv)
        return Key(method, attrs["URI"]?.let { base.resolve(it) }, iv, ivInvalid = rawIv != null && iv == null)
    }

    private fun parseIv(value: String): ByteArray? {
        val hex = value.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length > 2 * KEY_BYTES || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val padded = hex.padStart(2 * KEY_BYTES, '0')
        return ByteArray(KEY_BYTES) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun sequenceIv(seq: Long): ByteArray {
        val iv = ByteArray(KEY_BYTES)
        for (i in 0 until Long.SIZE_BYTES) iv[KEY_BYTES - 1 - i] = (seq ushr (8 * i)).toByte()
        return iv
    }

    private fun attributes(line: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(line.substringAfter(':', "")).forEach {
            result.putIfAbsent(it.groupValues[1], it.groupValues[2].trim().removeSurrounding("\""))
        }
        return result
    }

    private companion object {
        val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        const val KEY_BYTES = 16
        const val COPY_BUFFER = 32 * 1024
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val MAX_KEY_BYTES = 64L
        const val MAX_INIT_BYTES = 4L * 1024 * 1024
        const val MAX_KEYS = 8
        const val MAX_FIRST_SEGMENTS = 10
        const val MAX_SEEN_URIS = 2_000
        const val MAX_PLAYLIST_FAILURES = 3
        const val BACKOFF_MS = 500L
        const val MIN_POLL_MS = 500L
        const val DEFAULT_TARGET_S = 4
        const val SEGMENT_TRIES = 2
        const val SEGMENT_RETRY_MS = 500L
        const val STALL_MS = 60_000L
    }
}
