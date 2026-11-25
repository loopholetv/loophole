package io.github.loopholetv.record

import com.squareup.moshi.JsonWriter
import io.github.loopholetv.cast.CastHost
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.record.storage.RecordingStorage
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okio.Buffer

/**
 * This TV's recordings, served over HTTP under `/r/<token>/` to this TV's own player and to other
 * Loophole TVs on the local network.
 *
 * - `GET list`: a JSON array, one object per recording that has a file (see [RemoteRecording]); `locked`
 *   marks those whose channel is locked on this TV, whether or not the PIN is entered here.
 * - `GET file/<id>`: the file, whole or as one `Range` (`bytes=N-` or `bytes=N-M`). The size is
 *   read from the storage once per request, so a file still being written is served up to the
 *   length it has at that moment; a player that reaches it asks again.
 *
 * [token] is random per app start and travels in the path; a wrong one gets 401. Nothing here
 * logs, and no response names a file or a path.
 */
class RecordingsRoute(
    private val recordings: suspend () -> List<RecordingEntity>,
    private val recording: suspend (Long) -> RecordingEntity?,
    private val storageFor: suspend (RecordingEntity) -> RecordingStorage?,
    /** The port the server is bound to, or null while it is stopped. */
    private val port: () -> Int? = { null },
    /** Whether other TVs may be shown recordings (the "allow joining" setting); this TV's own player is always served. */
    private val allowShare: () -> Boolean = { true },
    val token: String = newToken(),
    /** Whether this TV's own flags lock the recording's channel, whatever the PIN state; marks it in the list. */
    private val channelLocked: suspend (RecordingEntity) -> Boolean = { false },
) {
    fun install(server: LocalHttpServer) {
        server.route("GET", PREFIX) { req, res -> handle(req, res) }
    }

    /** The loopback address of [id]'s file for this TV's own player; null while the server is down. */
    fun localFileUrl(id: Long): String? = port()?.let { "http://127.0.0.1:$it$PREFIX/$token/file/$id" }

    /** [token] while [rows] holds a recording that can be served, otherwise null. */
    fun announced(scope: CoroutineScope, rows: Flow<List<RecordingEntity>>): StateFlow<String?> =
        rows.map { list -> list.any(::servable) }
            .distinctUntilChanged()
            .map { has -> if (has) token else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    internal suspend fun handle(req: HttpRequest, res: HttpResponse) {
        if (!req.remote.isLoopbackAddress && !allowShare()) return res.empty(404)
        val parts = req.path.removePrefix("$PREFIX/").split('/')
        if (!CastHost.sameSecret(parts[0], token)) return res.empty(401)
        val rest = parts.drop(1)
        when {
            rest == listOf("list") -> list(res)
            rest.size == 2 && rest[0] == "file" -> file(req, res, rest[1].toLongOrNull() ?: return res.empty(404))
            else -> res.empty(404)
        }
    }

    private suspend fun list(res: HttpResponse) {
        val body = Buffer()
        JsonWriter.of(body).use { w ->
            w.beginArray()
            recordings().filter(::servable).forEach { r ->
                val locked = channelLocked(r)
                w.beginObject()
                w.name("id").value(r.id)
                w.name("title").value(r.title)
                w.name("channel").value(r.channelName)
                w.name("start").value(r.start.toEpochMilli())
                w.name("stop").value(r.stop.toEpochMilli())
                w.name("state").value(r.state.name)
                w.name("reason").value(r.reason.name)
                w.name("bytes").value(r.bytes)
                w.name("recording").value(r.state == RecordingState.RECORDING)
                w.name("durationMs").value(durationMs(r))
                w.name("locked").value(locked)
                w.endObject()
            }
            w.endArray()
        }
        val bytes = body.readByteArray()
        res.start(200, JSON, bytes.size.toLong())
        res.body.write(bytes)
    }

    private suspend fun file(req: HttpRequest, res: HttpResponse, id: Long) {
        val row = recording(id)?.takeIf(::servable) ?: return res.empty(404)
        val name = row.fileName ?: return res.empty(404)
        val storage = storageFor(row) ?: return res.empty(404)
        val size = try {
            storage.size(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return res.empty(UNAVAILABLE)
        } ?: return res.empty(404)
        val type = if (name.endsWith(".mp4", ignoreCase = true)) MP4 else TS
        when (val range = parseRange(req.headers["range"], size)) {
            ByteRange.Unsatisfiable -> res.start(416, null, 0, mapOf("Content-Range" to "bytes */$size"))
            ByteRange.Whole -> send(res, storage, name, 0, size, 200, type, mapOf("Accept-Ranges" to "bytes"))
            is ByteRange.Part -> send(
                res, storage, name, range.first, range.last - range.first + 1, 206, type,
                mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.first}-${range.last}/$size"),
            )
        }
    }

    private suspend fun send(
        res: HttpResponse,
        storage: RecordingStorage,
        name: String,
        offset: Long,
        length: Long,
        code: Int,
        type: String,
        headers: Map<String, String>,
    ) {
        val input = try {
            storage.openRead(name, offset)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return res.empty(UNAVAILABLE)
        }
        input.use {
            res.start(code, type, length, headers)
            val buf = ByteArray(CHUNK)
            var left = length
            try {
                while (left > 0) {
                    val n = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    res.body.write(buf, 0, n)
                    left -= n
                }
            } catch (_: IOException) {
                // The player went away, or the storage dropped: the connection closes short.
            }
        }
    }

    private fun HttpResponse.empty(code: Int) = start(code, null, 0)

    internal sealed interface ByteRange {
        data object Whole : ByteRange
        data object Unsatisfiable : ByteRange
        data class Part(val first: Long, val last: Long) : ByteRange
    }

    companion object {
        const val PREFIX = "/r"
        private const val JSON = "application/json"
        private const val TS = "video/mp2t"
        private const val MP4 = "video/mp4"
        private const val UNAVAILABLE = 503
        private const val CHUNK = 64 * 1024
        private const val TOKEN_BYTES = 16
        private const val HEX = "0123456789abcdef"

        /** Rows that can be played: finished, still being written, or failed part-way, and with a file. */
        internal fun servable(r: RecordingEntity) = playable(r)

        internal fun durationMs(r: RecordingEntity): Long =
            ((r.endedAt ?: r.stop).toEpochMilli() - (r.startedAt ?: r.start).toEpochMilli()).coerceAtLeast(0)

        /**
         * `bytes=N-` and `bytes=N-M` ([M] cut to the end). Anything else, including a suffix or a
         * list of ranges, is ignored and the whole file is sent; N at or past [size] is unsatisfiable.
         */
        internal fun parseRange(header: String?, size: Long): ByteRange {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.trim()
                ?: return ByteRange.Whole
            val bounds = spec.split('-')
            if (bounds.size != 2 || spec.contains(',')) return ByteRange.Whole
            val first = bounds[0].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return ByteRange.Whole
            val last = bounds[1].trim().let { if (it.isEmpty()) Long.MAX_VALUE else it.toLongOrNull() }
                ?.takeIf { it >= first } ?: return ByteRange.Whole
            if (first >= size) return ByteRange.Unsatisfiable
            return ByteRange.Part(first, minOf(last, size - 1))
        }

        fun newToken(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return buildString(TOKEN_BYTES * 2) {
                b.forEach { append(HEX[(it.toInt() shr 4) and 0x0F]).append(HEX[it.toInt() and 0x0F]) }
            }
        }
    }
}
