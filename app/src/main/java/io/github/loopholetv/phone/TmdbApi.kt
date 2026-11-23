package io.github.loopholetv.phone

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.data.tmdb.KeyStatus
import java.io.IOException

/**
 * The phone page's TMDB key route. Every reply is `{"set","last4","status"}`; the key itself is
 * never sent back. A key that TMDB refused still counts as set (it is stored).
 */
class TmdbApi(
    private val last4: suspend () -> String?,
    private val status: () -> KeyStatus,
    private val save: suspend (raw: String) -> KeyStatus,
    private val remove: suspend () -> Unit,
    /** Checks a stored key nobody has checked since the app started. */
    private val recheck: suspend () -> Unit,
) {
    fun register(server: PhoneServer) {
        server.api("GET", TMDB, handler = ::get)
        server.api("PUT", TMDB, MAX_BODY, ::put)
        server.api("DELETE", TMDB, handler = ::delete)
    }

    private suspend fun get(req: HttpRequest, res: HttpResponse) {
        if (req.path != TMDB) return res.sendError(404, "not_found")
        if (last4() != null && status() == KeyStatus.NONE) recheck()
        reply(res)
    }

    private suspend fun put(req: HttpRequest, res: HttpResponse) {
        if (req.path != TMDB) return res.sendError(404, "not_found")
        if (req.headers["content-type"]?.startsWith("application/json", ignoreCase = true) != true) {
            return res.sendError(400, "bad_request")
        }
        val key = try {
            readKey(req)
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        } ?: return res.sendError(400, "bad_request")
        save(key)
        reply(res)
    }

    private suspend fun delete(req: HttpRequest, res: HttpResponse) {
        if (req.path != TMDB) return res.sendError(404, "not_found")
        remove()
        reply(res)
    }

    private suspend fun reply(res: HttpResponse) {
        val tail = last4()
        val current = if (tail == null) KeyStatus.NONE else status()
        res.sendJson(200) {
            serializeNulls = true
            beginObject()
            name("set").value(tail != null)
            name("last4").value(tail)
            name("status").value(current.name)
            endObject()
        }
    }

    /** `{"key":"…"}` → the string; null without a string key. Other fields are ignored. */
    private fun readKey(req: HttpRequest): String? {
        var key: String? = null
        req.jsonBody().use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "key" && r.peek() == JsonReader.Token.STRING) key = r.nextString() else r.skipValue()
            }
            r.endObject()
        }
        return key
    }

    private companion object {
        const val TMDB = "/api/tmdb"
        const val MAX_BODY = 2L * 1024
    }
}
