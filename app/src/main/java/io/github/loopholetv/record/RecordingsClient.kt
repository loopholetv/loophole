package io.github.loopholetv.record

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonDataException
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.net.LocalPeersOnly
import io.github.loopholetv.cast.net.isLocalIpv4Literal
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** One recording on another TV, as its list reports it. Times are epoch milliseconds. */
data class RemoteRecording(
    val id: Long,
    val title: String,
    val channel: String,
    val start: Long,
    val stop: Long,
    val state: String,
    val reason: String,
    val bytes: Long,
    val recording: Boolean,
    val durationMs: Long,
    /** The serving TV locks this recording's channel. Only meaningful when [lockKnown]. */
    val locked: Boolean = false,
    /** The list carried a `locked` field; an older TV's does not, and its recordings are matched by channel name. */
    val lockKnown: Boolean = false,
) {
    companion object {
        /** Null on anything that isn't a list of recordings. Unknown keys are ignored. */
        internal fun parseList(json: String): List<RemoteRecording>? = try {
            val r = JsonReader.of(Buffer().writeUtf8(json))
            val out = ArrayList<RemoteRecording>()
            r.beginArray()
            while (r.hasNext()) out += parseOne(r)
            r.endArray()
            out
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

        private fun parseOne(r: JsonReader): RemoteRecording {
            val m = HashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                when (r.peek()) {
                    JsonReader.Token.STRING -> m[key] = r.nextString()
                    JsonReader.Token.NUMBER -> m[key] = r.nextLong()
                    JsonReader.Token.BOOLEAN -> m[key] = r.nextBoolean()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            fun long(k: String) = m[k] as? Long ?: throw JsonDataException(k)
            fun text(k: String) = m[k] as? String ?: throw JsonDataException(k)
            return RemoteRecording(
                id = long("id"), title = text("title"), channel = text("channel"), start = long("start"),
                stop = long("stop"), state = text("state"), reason = text("reason"), bytes = long("bytes"),
                recording = m["recording"] as? Boolean ?: throw JsonDataException("recording"),
                durationMs = long("durationMs"),
                locked = m["locked"] as? Boolean ?: false,
                lockKnown = m["locked"] is Boolean,
            )
        }
    }
}

/**
 * The calling side of [RecordingsRoute]. Only a TV on the local network is ever contacted (checked
 * before each call, and again by [LocalPeersOnly]); redirects are never followed. Nothing here
 * logs, and no failure carries a URL or message out.
 */
class RecordingsClient(http: OkHttpClient) {
    private val client = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(LocalPeersOnly())
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S * 2, TimeUnit.SECONDS)
        .build()

    /** [peer]'s recordings; empty when it has none, can't be reached or refuses. */
    suspend fun list(peer: CastPeer): List<RemoteRecording> = withContext(Dispatchers.IO) {
        val url = url(peer, "list") ?: return@withContext emptyList()
        try {
            client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
                if (response.code != 200) return@use emptyList()
                val source = response.body.source()
                if (source.request(MAX_REPLY_BYTES + 1)) return@use emptyList()
                RemoteRecording.parseList(source.readUtf8()) ?: emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The address of recording [id] on [peer] for the player; null unless it is on the local network and shares recordings. */
    fun fileUrl(peer: CastPeer, id: Long): String? = url(peer, "file", id.toString())?.toString()

    private fun url(peer: CastPeer, vararg segments: String): HttpUrl? {
        val rec = peer.rec?.takeIf { it.isNotEmpty() } ?: return null
        if (peer.port !in 1..65_535 || !isLocalIpv4Literal(peer.host)) return null
        val b = HttpUrl.Builder().scheme("http").host(peer.host).port(peer.port)
            .addPathSegment(RecordingsRoute.PREFIX.trim('/')).addPathSegment(rec)
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }

    private companion object {
        const val TIMEOUT_S = 8L
        const val MAX_REPLY_BYTES = 2L * 1024 * 1024
    }
}
