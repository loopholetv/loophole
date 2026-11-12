package io.github.loopholetv.cast

import io.github.loopholetv.cast.net.isLocalIpv4Literal
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/** The casting TV already has as many followers as it takes. */
class CastFullException : IOException("cast is full")

/** The casting TV refused with [code] (for example, a token or cast that is no longer valid). */
class CastRefusedException(val code: Int) : IOException("refused with $code")

/**
 * A following TV's side of another TV's cast: its event stream, answering its heartbeats, and
 * leaving it.
 *
 * Only a peer on the local network is ever contacted: the host is checked right before each request.
 * Redirects are never followed. Nothing here logs a URL, path or token.
 */
open class CastClient(http: OkHttpClient) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val leaveHttp = this.http.newBuilder().callTimeout(LEAVE_TIMEOUT_S, TimeUnit.SECONDS).build()

    /**
     * [peer]'s events, as this follower ([selfId]). Lines that aren't events are skipped. Completes
     * after [CastEvent.Stopped]; throws [CastFullException] when the cast is full, another
     * [IOException] on any other refusal, a read timeout, a broken connection or an end without
     * [CastEvent.Stopped]. Cancelling the collector closes the connection.
     */
    open fun events(peer: CastPeer, selfId: String): Flow<CastEvent> = flow {
        val url = castUrl(peer)?.addPathSegment("events")?.addQueryParameter("id", selfId)?.build()
            ?: throw IOException("not a local peer")
        val alive = castUrl(peer)?.addPathSegment("alive")?.addQueryParameter("id", selfId)?.build()
            ?: throw IOException("not a local peer")
        val call = http.newCall(Request.Builder().url(url).build())
        coroutineScope {
            // A blocking read only ends when the call is cancelled.
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                read(call) {
                    if (it is CastEvent.Heartbeat) acknowledge(alive)
                    emit(it)
                }
            } finally {
                closer.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend inline fun read(call: Call, emit: (CastEvent) -> Unit) {
        call.execute().use { response ->
            if (response.code == HTTP_FULL) throw CastFullException()
            if (!response.isSuccessful) throw CastRefusedException(response.code)
            val source = response.body.source()
            while (true) {
                // Throws at the end of the stream, or on a line too long to be an event.
                val line = source.readUtf8LineStrict(MAX_LINE_BYTES)
                val event = CastJson.event(line) ?: continue
                emit(event)
                if (event is CastEvent.Stopped) return
            }
        }
    }

    /**
     * Answers a heartbeat, so the casting TV can tell this follower is still there (a TV that loses
     * power never closes its connection). Fire and forget: a lost answer only counts as silence.
     */
    private fun acknowledge(alive: HttpUrl) {
        leaveHttp.newCall(Request.Builder().url(alive).post(ByteArray(0).toRequestBody()).build()).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) = response.close()
            },
        )
    }

    /** Tells [peer] this follower ([selfId]) is gone. Best effort: failures are ignored. */
    open suspend fun leave(peer: CastPeer, selfId: String) {
        val url = castUrl(peer)?.addPathSegment("leave")?.addQueryParameter("id", selfId)?.build() ?: return
        withContext(Dispatchers.IO) {
            try {
                leaveHttp.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
                    .execute()
                    .close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // The casting TV notices on its own when this connection goes.
            } catch (_: RuntimeException) {
                // Same.
            }
        }
    }

    companion object {
        private const val HTTP_FULL = 503
        private const val MAX_LINE_BYTES = 8_192L
        private const val LEAVE_TIMEOUT_S = 3L
        private val TOKEN = Regex("""[A-Za-z0-9_-]{1,64}""")
        private val SEGMENT = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}""")

        /** True when [host] is an IPv4 address on the local network (no name lookup). */
        fun isLocalHost(host: String): Boolean = isLocalIpv4Literal(host)

        /** `http://<host>:<port>/c/<token>` of [peer]'s cast; null unless [peer] is local and casting. */
        private fun castUrl(peer: CastPeer): HttpUrl.Builder? {
            val token = peer.token?.takeIf { TOKEN.matches(it) } ?: return null
            if (peer.port !in 1..65_535 || !isLocalHost(peer.host)) return null
            return HttpUrl.Builder().scheme("http").host(peer.host).port(peer.port)
                .addPathSegment("c").addPathSegment(token)
        }

        /**
         * The address of a relay path from [peer]'s [CastEvent.Playing], checked right before
         * playing it: null unless [peer] is local and [path] is one or two plain segments under
         * that cast's own `/c/<token>/`.
         */
        fun mediaUrl(peer: CastPeer, path: String, selfId: String? = null): String? {
            val base = castUrl(peer) ?: return null
            val rest = path.removePrefix("/c/${peer.token}/").takeIf { it != path } ?: return null
            val segments = rest.split('/')
            if (segments.size !in 1..2 || segments.any { !SEGMENT.matches(it) || it.trim('.').isEmpty() || ".." in it }) {
                return null
            }
            segments.forEach { base.addPathSegment(it) }
            // Lets the host replace this TV's earlier TS reader instead of counting both.
            if (selfId != null && segments.last().endsWith(".ts")) base.addQueryParameter("id", selfId)
            return base.build().toString()
        }
    }
}
