package io.github.loopholetv.phone

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.EOFException
import okio.Buffer

/** Sends a JSON body written by [write] with [code] and any extra [headers]. */
internal fun HttpResponse.sendJson(
    code: Int,
    headers: Map<String, String> = emptyMap(),
    write: JsonWriter.() -> Unit,
) {
    val buffer = Buffer()
    JsonWriter.of(buffer).use { it.write() }
    val bytes = buffer.readByteArray()
    start(code, JSON_TYPE, bytes.size.toLong(), headers)
    body.write(bytes)
}

/** `{"error":"<error>"}` with [code]. */
internal fun HttpResponse.sendError(code: Int, error: String) = sendJson(code) {
    beginObject()
    name("error").value(error)
    endObject()
}

/** A status with no body. */
internal fun HttpResponse.sendEmpty(code: Int) = start(code, null, 0)

/** The whole request body (at most the route's limit, which the server has already checked). */
internal fun HttpRequest.readBytes(): ByteArray {
    if (bodyLength == 0L) return ByteArray(0)
    val out = ByteArrayOutputStream(bodyLength.toInt())
    val buf = ByteArray(4096)
    while (true) {
        val n = body.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    if (out.size().toLong() != bodyLength) throw EOFException()
    return out.toByteArray()
}

/** A reader over the request body; reading malformed JSON throws an IOException. */
internal fun HttpRequest.jsonBody(): JsonReader = JsonReader.of(Buffer().write(readBytes()))

/** The value of cookie [name] in a `Cookie` header, or null when it isn't there. */
internal fun cookieValue(header: String?, name: String): String? {
    if (header.isNullOrEmpty()) return null
    for (part in header.split(';')) {
        val eq = part.indexOf('=')
        if (eq < 0) continue
        if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * Whether a request reached this server by one of its own addresses: [host] (the `Host` header) is
 * in [allowed]. Refuses DNS-rebinding pages, whose requests carry their own name.
 */
internal fun hostAllowed(host: String?, allowed: Set<String>): Boolean = host != null && host in allowed

/**
 * Whether a POST came from the phone page itself: it carries `X-Loophole: 1`, which a plain form
 * or another site can't send without a preflight, and an `Origin`, when there is one, of this host.
 */
internal fun postAllowed(headers: Map<String, String>): Boolean {
    if (headers["x-loophole"] != "1") return false
    val origin = headers["origin"] ?: return true
    val host = headers["host"] ?: return false
    return origin == "http://$host"
}

private const val JSON_TYPE = "application/json; charset=utf-8"
