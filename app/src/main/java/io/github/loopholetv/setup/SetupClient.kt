package io.github.loopholetv.setup

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The calling side of the setup routes ([SetupHost]). Only a TV on the local network is ever
 * contacted (checked before each call, and again by [LocalPeersOnly]); redirects are never
 * followed. Offer, ask and cancel are quick; the setup itself gets a long read timeout, since the
 * other TV only answers once its import has committed.
 *
 * Nothing here logs, and no failure carries a URL or message out.
 */
internal class SetupClient(http: OkHttpClient) {
    private val quick = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(LocalPeersOnly())
        .connectTimeout(QUICK_S, TimeUnit.SECONDS)
        .readTimeout(QUICK_S, TimeUnit.SECONDS)
        .writeTimeout(QUICK_S, TimeUnit.SECONDS)
        .callTimeout(QUICK_S * 2, TimeUnit.SECONDS)
        .build()
    private val bulk = quick.newBuilder()
        .readTimeout(BUNDLE_READ_S, TimeUnit.SECONDS)
        .writeTimeout(BUNDLE_WRITE_S, TimeUnit.SECONDS)
        .callTimeout(BUNDLE_CALL_S, TimeUnit.SECONDS)
        .build()

    /**
     * The other TV's answer: its status [code] and the fields of its JSON reply, or [UNREACHABLE]
     * (code 0) when there was none. Never printed with the request id.
     */
    class Answer(val code: Int, private val fields: SetupWire.Fields?) {
        val error: String? get() = fields?.string(SetupHost.ERROR)
        val id: String? get() = fields?.string("id")?.takeIf { SetupHost.ID.matches(it) }
        val left: Int? get() = fields?.int("left")?.takeIf { it > 0 }
        override fun toString() = "Answer(code=$code, error=$error)"
    }

    /** Asks [peer] to show a code for this TV ([fromId]); a 200 carries the request id. */
    suspend fun offer(peer: CastPeer, fromId: String): Answer =
        post(quick, peer, SetupHost.OFFER, null, SetupWire.write("from" to fromId))

    /** Asks [peer] to send its setup to this TV, which shows the code of request [id]. */
    suspend fun ask(peer: CastPeer, fromId: String, id: String): Answer =
        post(quick, peer, SetupHost.ASK, null, SetupWire.write("from" to fromId, "id" to id))

    /** Sends the [sealed] setup for [peer]'s request [id]. */
    suspend fun sendBundle(peer: CastPeer, id: String, sealed: ByteArray): Answer =
        post(bulk, peer, SetupHost.BUNDLE, id, sealed, OCTETS)

    /** Tells [peer] the copy of request [id] is over. Best effort. */
    suspend fun cancel(peer: CastPeer, id: String, reason: SetupHost.End) {
        post(quick, peer, SetupHost.CANCEL, id, SetupWire.write("reason" to reason.wire))
    }

    private suspend fun post(
        client: OkHttpClient,
        peer: CastPeer,
        route: String,
        id: String?,
        body: ByteArray,
        type: String = SetupWire.JSON,
    ): Answer = withContext(Dispatchers.IO) {
        val url = url(peer, route, id) ?: return@withContext UNREACHABLE
        try {
            val request = Request.Builder().url(url).post(body.toRequestBody(type.toMediaType())).build()
            client.newCall(request).await().use { response ->
                // The reply is read here, on IO, with the call's own timeouts.
                val bytes = response.body.source().let { source ->
                    if (source.request(MAX_REPLY_BYTES + 1)) null else source.readByteArray()
                }
                Answer(response.code, bytes?.let(SetupWire::parse))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // IOException or anything else OkHttp throws: no answer.
            UNREACHABLE
        }
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

    companion object {
        val UNREACHABLE = Answer(0, null)
        private const val OCTETS = "application/octet-stream"
        private const val QUICK_S = 5L
        private const val BUNDLE_READ_S = 90L
        private const val BUNDLE_WRITE_S = 60L
        private const val BUNDLE_CALL_S = 300L
        private const val MAX_REPLY_BYTES = 4_096L

        /** `http://<host>:<port><route>[?id=]`; null unless [peer] is on the local network. */
        private fun url(peer: CastPeer, route: String, id: String?): HttpUrl? {
            if (peer.port !in 1..65_535 || !isLocalIpv4Literal(peer.host)) return null
            val b = HttpUrl.Builder().scheme("http").host(peer.host).port(peer.port)
            route.trim('/').split('/').forEach { b.addPathSegment(it) }
            if (id != null) b.addQueryParameter("id", id)
            return b.build()
        }
    }
}
