package io.github.loopholetv.record

import android.os.SystemClock
import io.github.loopholetv.cast.CastHost
import io.github.loopholetv.player.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

sealed interface RecordOutcome {
    data class Finished(val bytes: Long, val gaps: Int) : RecordOutcome
    data class Refused(val code: Int?) : RecordOutcome
    data object StorageFull : RecordOutcome
    data class StorageError(val message: String) : RecordOutcome
    data class Failed(val message: String) : RecordOutcome
}

const val STORAGE_ERROR_MESSAGE = "Couldn't write to the storage."
const val STREAM_UNREACHABLE_MESSAGE = "Couldn't reach the stream."
const val STREAM_ADDRESS_MESSAGE = "The stream address isn't valid."
const val STREAM_ENCRYPTION_MESSAGE = "This stream's encryption isn't supported."
const val RECORD_FAILED_MESSAGE = "Recording failed."

private val RECONNECT_BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
private const val SNIFF_BYTES = 188L * 4
private const val COPY_BUFFER = 32 * 1024
private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024

/**
 * Runs [block] on an IO thread with the response to [request]. If the caller is cancelled the call is
 * cancelled too, so a read blocked on a quiet connection ends at once and the connection is released.
 */
internal suspend fun <T> OkHttpClient.fetch(request: Request, block: suspend (Response) -> T): T {
    val call = newCall(request)
    return coroutineScope {
        val work = async(Dispatchers.IO) { call.execute().use { block(it) } }
        try {
            work.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }
}

/**
 * Copies a live stream into storage until a deadline. MPEG-TS (and any other plain byte stream) is copied as
 * it arrives; an HLS playlist is followed by [HlsRecorder]. The caller's cancellation closes the upstream
 * and the file, keeps what was written, and propagates; only reaching the deadline returns [RecordOutcome.Finished].
 * The deadline is checked between reads, so a stream that goes silent is cut off by the client's read timeout.
 */
open class Recorder(
    private val http: OkHttpClient,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    /** "mp4" when [first] opens like an MP4 file (or, with no bytes, [url] names one); otherwise "ts". */
    fun extensionFor(first: ByteArray, url: String): String {
        if (first.size >= FTYP_END && String(first, FTYP_AT, FTYP.length, Charsets.ISO_8859_1) == FTYP) return "mp4"
        if (first.isEmpty()) {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "mp4"
        }
        return "ts"
    }

    /**
     * Records until [until] (an elapsed-time deadline, read again after every chunk so it can be extended).
     * [onFormat] is told the file extension once, before the first [openSink], so the caller can name the file.
     * [onDropped] is told, each time a connection that delivered data ends, whether it ended cleanly (the
     * stream's own end) or broke; it runs before the recorder decides to reconnect.
     */
    open suspend fun record(
        open: suspend () -> ResolvedStream,
        until: () -> Long,
        openSink: suspend () -> RecordSink,
        keepAlive: suspend () -> Unit = {},
        onFormat: (String) -> Unit = {},
        onDropped: (clean: Boolean) -> Unit = {},
    ): RecordOutcome = coroutineScope {
        val ping = launch {
            try {
                keepAlive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed ping never stops the recording; the provider decides what to do about the session.
            }
        }
        val writer = RecordWriter(openSink, clock)
        try {
            val outcome = Session(open, until, writer, onFormat, onDropped).run()
            ping.cancel()
            closing(writer, outcome)
        } catch (e: StorageFullException) {
            RecordOutcome.StorageFull
        } catch (e: StorageUnreachableException) {
            RecordOutcome.StorageError(STORAGE_ERROR_MESSAGE)
        } finally {
            ping.cancel()
            withContext(NonCancellable) { runCatching { writer.close() } }
        }
    }

    private fun closing(writer: RecordWriter, outcome: RecordOutcome): RecordOutcome = try {
        writer.close()
        outcome
    } catch (e: StorageFullException) {
        RecordOutcome.StorageFull
    } catch (e: StorageWriteException) {
        RecordOutcome.StorageError(STORAGE_ERROR_MESSAGE)
    }

    private sealed interface Attempt {
        /** The deadline passed, or the stream ended for good. */
        data object Done : Attempt

        /** The stream ended or broke; [gotData] says whether this attempt delivered anything. */
        data class Dropped(val gotData: Boolean, val clean: Boolean = false, val wrote: Boolean = gotData) : Attempt
        data class Refused(val code: Int?) : Attempt
        data class Playlist(val url: String, val text: String) : Attempt
        data class Fatal(val outcome: RecordOutcome) : Attempt
    }

    private inner class Session(
        val open: suspend () -> ResolvedStream,
        val until: () -> Long,
        val writer: RecordWriter,
        val onFormat: (String) -> Unit,
        val onDropped: (Boolean) -> Unit,
    ) {
        var haveData = false
        var needsGap = false
        var reconnectGaps = 0
        var formatSent = false
        var hls: HlsRecorder? = null
        var current: ResolvedStream? = null

        fun finished(): RecordOutcome =
            RecordOutcome.Finished(writer.bytes, reconnectGaps + writer.gaps + (hls?.gaps ?: 0))

        suspend fun run(): RecordOutcome {
            var tries = 0
            var backoff = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (haveData && clock() >= until()) return finished()
                var attempt = attemptOnce()
                if (attempt is Attempt.Playlist) attempt = followPlaylist(attempt)
                when (attempt) {
                    is Attempt.Fatal -> return attempt.outcome
                    Attempt.Done -> return finished()
                    is Attempt.Refused, is Attempt.Dropped -> {
                        val delivered = attempt is Attempt.Dropped && attempt.gotData
                        if (attempt is Attempt.Dropped && attempt.wrote) onDropped(attempt.clean)
                        if (!haveData) {
                            if (++tries >= REFUSED_TRIES || clock() >= until()) return failure(attempt)
                            retryDelay(REFUSED_WINDOW_MS / (REFUSED_TRIES - 1))
                        } else {
                            if (clock() >= until()) return finished()
                            if (delivered) backoff = 0
                            retryDelay(RECONNECT_BACKOFF_MS[minOf(backoff, RECONNECT_BACKOFF_MS.size - 1)])
                            backoff++
                            needsGap = true
                        }
                    }
                    is Attempt.Playlist -> error("followed above")
                }
            }
        }

        fun failure(last: Attempt): RecordOutcome = when (last) {
            is Attempt.Refused -> RecordOutcome.Refused(last.code)
            else -> RecordOutcome.Failed(STREAM_UNREACHABLE_MESSAGE)
        }

        /** Counts the gap a reconnect left, now that the new connection has delivered something. */
        fun connected() {
            haveData = true
            if (needsGap) {
                needsGap = false
                reconnectGaps++
            }
        }

        suspend fun attemptOnce(): Attempt {
            val stream = try {
                open().also { current = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Attempt.Dropped(false)
            }
            val request = try {
                requestFor(stream, stream.url)
            } catch (e: IllegalArgumentException) {
                return Attempt.Fatal(RecordOutcome.Failed(STREAM_ADDRESS_MESSAGE))
            }
            var gotData = false
            return try {
                http.fetch(request) { resp -> handle(resp) { gotData = true } }
            } catch (e: IOException) {
                Attempt.Dropped(gotData)
            }
        }

        suspend fun handle(resp: Response, onData: () -> Unit): Attempt {
            if (!resp.isSuccessful) return Attempt.Refused(resp.code)
            val source = resp.body.source()
            source.request(SNIFF_BYTES)
            val head = source.buffer.snapshot(minOf(SNIFF_BYTES, source.buffer.size).toInt()).toByteArray()
            val type = resp.header("Content-Type").orEmpty().lowercase()
            if (CastHost.looksLikePlaylist(head)) {
                if (source.request(MAX_PLAYLIST_BYTES + 1)) throw IOException("Playlist too large")
                val text = source.buffer.readByteArray().toString(Charsets.UTF_8)
                return Attempt.Playlist(resp.request.url.toString(), text)
            }
            if (!CastHost.looksLikeTs(head) && CastHost.looksLikeErrorPage(head, type)) return Attempt.Refused(null)
            announce(extensionFor(head, resp.request.url.toString()))
            writer.tsMode = CastHost.looksLikeTs(head)
            if (needsGap) writer.markDiscontinuity()
            connected()
            val buf = ByteArray(COPY_BUFFER)
            var wrote = false
            while (true) {
                val n = source.read(buf)
                // An empty body is not the stream's end: only a connection that wrote something ended cleanly.
                if (n < 0) return Attempt.Dropped(true, clean = wrote, wrote = wrote)
                writer.write(buf, 0, n)
                wrote = true
                onData()
                if (clock() >= until()) return Attempt.Done
            }
        }

        suspend fun followPlaylist(p: Attempt.Playlist): Attempt {
            val stream = current ?: return Attempt.Dropped(false)
            val follower = hls ?: HlsRecorder(
                http, { requestFor(current ?: stream, it.toString()) }, writer, until, clock, retryDelay,
            ).also { hls = it }
            return when (val attached = follower.attach(p.url, p.text)) {
                HlsAttach.Lost -> Attempt.Dropped(false)
                HlsAttach.Unsupported -> Attempt.Fatal(RecordOutcome.Failed(RECORD_FAILED_MESSAGE))
                is HlsAttach.Ready -> {
                    announce(attached.extension)
                    // The recorder's own sequence accounting says whether a reconnect missed anything.
                    haveData = true
                    needsGap = false
                    writer.tsMode = false
                    when (follower.run()) {
                        HlsEnd.Deadline, HlsEnd.EndList -> Attempt.Done
                        HlsEnd.Lost -> Attempt.Dropped(true)
                        HlsEnd.Unsupported -> Attempt.Fatal(RecordOutcome.Failed(STREAM_ENCRYPTION_MESSAGE))
                    }
                }
            }
        }

        fun announce(extension: String) {
            if (formatSent) return
            formatSent = true
            onFormat(extension)
        }

        fun requestFor(stream: ResolvedStream, url: String): Request = Request.Builder().url(url).apply {
            if (stream.userAgent.isNotBlank()) header("User-Agent", stream.userAgent)
            stream.headers.forEach { (k, v) -> header(k, v) }
        }.build()
    }

    private companion object {
        const val FTYP = "ftyp"
        const val FTYP_AT = 4
        const val FTYP_END = FTYP_AT + 4
    }
}
