package io.github.loopholetv.data.xtream

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Token
import io.github.loopholetv.util.redact
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

class XtreamClient(private val http: OkHttpClient) {

    /** [userAgent] is sent as `User-Agent`; null or blank keeps OkHttp's own. Same below. */
    suspend fun authenticate(c: XtreamCredentials, userAgent: String? = null): XtreamAccount =
        execute(c, action = null, userAgent) { reader, _ ->
            if (reader.peek() != Token.BEGIN_OBJECT) throw XtreamProtocolException("Unexpected response shape")
            var user: InfoObject? = null
            var server: InfoObject? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (reader.peek() != Token.BEGIN_OBJECT) {
                    reader.skipValue()
                } else if (name == "user_info") {
                    user = readInfo(reader)
                } else if (name == "server_info") {
                    server = readInfo(reader)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            toAccount(user ?: throw XtreamProtocolException("Response has no user_info"), server)
        }

    suspend fun liveCategories(c: XtreamCredentials, userAgent: String? = null): List<XtreamCategory> =
        execute(c, "get_live_categories", userAgent) { reader, job ->
            val out = mutableListOf<XtreamCategory>()
            forEachObjectInArray(reader, job) { r ->
                val f = readFields(r)
                val id = f["category_id"]
                val name = f["category_name"]
                if (id != null && name != null) out += XtreamCategory(id, name)
            }
            out
        }

    /** Streams each valid channel to [onStream] as it is parsed; malformed entries are skipped. */
    suspend fun liveStreams(c: XtreamCredentials, userAgent: String? = null, onStream: (XtreamLiveStream) -> Unit) {
        execute(c, "get_live_streams", userAgent) { reader, job ->
            forEachObjectInArray(reader, job) { r ->
                val f = readFields(r)
                val id = f["stream_id"]?.toLongOrNull()
                val name = f["name"]
                if (id != null && name != null) {
                    onStream(
                        XtreamLiveStream(
                            streamId = id,
                            name = name,
                            num = f["num"].toIntLenient(),
                            icon = f["stream_icon"].blankToNull(),
                            epgChannelId = f["epg_channel_id"].blankToNull(),
                            categoryId = f["category_id"].blankToNull(),
                            tvArchive = f["tv_archive"].toIntLenient() == 1,
                            tvArchiveDays = f["tv_archive_duration"].toIntLenient(),
                        ),
                    )
                }
            }
        }
    }

    fun liveUrl(c: XtreamCredentials, streamId: Long): String = baseUrl(c).newBuilder()
        .addPathSegment("live")
        .addPathSegment(c.username)
        .addPathSegment(c.password)
        .addPathSegment("$streamId.ts")
        .build().toString()

    fun epgUrl(c: XtreamCredentials): String = baseUrl(c).newBuilder()
        .addPathSegment("xmltv.php")
        .addQueryParameter("username", c.username)
        .addQueryParameter("password", c.password)
        .build().toString()

    // --- transport ---

    private suspend fun <T> execute(
        c: XtreamCredentials,
        action: String?,
        userAgent: String?,
        parse: (JsonReader, Job) -> T,
    ): T {
        val secrets = listOf(c.username, c.password)
        val request = Request.Builder().url(buildUrl(c, action))
            .apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }
            .build()
        val call = http.newCall(request)
        return coroutineScope {
            // Sibling watcher: cancels the in-flight Call as soon as this scope starts cancelling
            // (immediately if already cancelled), even while the IO thread is blocked.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    try {
                        call.execute().use { response ->
                            when (response.code) {
                                401, 403 -> throw XtreamAuthException("Server rejected the credentials (HTTP ${response.code})")
                                !in 200..299 -> throw XtreamProtocolException("Unexpected HTTP ${response.code} from server")
                            }
                            try {
                                parse(JsonReader.of(response.body.source()), job)
                            } catch (e: JsonEncodingException) {
                                throw XtreamProtocolException("Server did not return valid JSON")
                            } catch (e: JsonDataException) {
                                throw XtreamProtocolException("Server returned unexpected JSON")
                            }
                        }
                    } catch (e: IOException) {
                        job.ensureActive()
                        // Raw exception deliberately not attached as cause: it may carry the credentialed URL.
                        throw XtreamNetworkException(redact(e.message ?: e.javaClass.simpleName, secrets))
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun baseUrl(c: XtreamCredentials): HttpUrl =
        c.server.toHttpUrlOrNull() ?: throw XtreamProtocolException("Invalid server address")

    private fun buildUrl(c: XtreamCredentials, action: String?): HttpUrl {
        return baseUrl(c).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()
    }

    // --- lenient JSON helpers ---

    /** Scalar fields of a login object plus its list of allowed output formats, if any. */
    private class InfoObject(val fields: Map<String, String>, val formats: List<String>)

    private fun readInfo(r: JsonReader): InfoObject {
        var formats: List<String> = emptyList()
        val fields = readFields(r) { name, reader ->
            if (name == "allowed_output_formats") {
                formats = buildList {
                    reader.beginArray()
                    while (reader.hasNext()) if (reader.peek() == Token.STRING) add(reader.nextString()) else reader.skipValue()
                    reader.endArray()
                }
            } else {
                reader.skipValue()
            }
        }
        return InfoObject(fields, formats)
    }

    private fun toAccount(user: InfoObject, server: InfoObject?): XtreamAccount {
        val f = user.fields
        if (f["auth"].toIntLenient() == 0) throw XtreamAuthException("Server rejected the credentials")
        val s = server?.fields.orEmpty()
        val formats = user.formats.ifEmpty { server?.formats.orEmpty() }
        return XtreamAccount(
            status = f["status"] ?: "",
            maxConnections = f["max_connections"].toIntLenient(),
            expiresAt = f["exp_date"]?.toLongOrNull()?.let { Instant.ofEpochSecond(it) },
            serverTimeZone = xtreamZone(s["timezone"], s["time_now"], s["timestamp_now"]?.toLongOrNull()),
            archiveExt = archiveExt(formats),
        )
    }

    /**
     * Iterates a top-level array, passing each object to [each]; non-object elements are skipped.
     * `null` and `{}` count as an empty list. Checks [job] for cancellation per element.
     */
    private fun forEachObjectInArray(reader: JsonReader, job: Job, each: (JsonReader) -> Unit) {
        when (reader.peek()) {
            Token.NULL -> { reader.nextNull<Unit>(); return }
            Token.BEGIN_OBJECT -> { reader.skipValue(); return }
            Token.BEGIN_ARRAY -> Unit
            else -> throw XtreamProtocolException("Expected a JSON array")
        }
        reader.beginArray()
        while (reader.hasNext()) {
            job.ensureActive()
            if (reader.peek() == Token.BEGIN_OBJECT) each(reader) else reader.skipValue()
        }
        reader.endArray()
    }

    /** Reads one object into scalar fields rendered as strings; nested values are skipped. */
    private fun readFields(r: JsonReader, onArray: (String, JsonReader) -> Unit = { _, reader -> reader.skipValue() }): Map<String, String> {
        val out = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                Token.STRING, Token.NUMBER -> out[name] = r.nextString()
                Token.BOOLEAN -> out[name] = if (r.nextBoolean()) "1" else "0"
                Token.NULL -> r.nextNull<Unit>()
                Token.BEGIN_ARRAY -> onArray(name, r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return out
    }

    private fun String?.toIntLenient(): Int? {
        val s = this?.trim() ?: return null
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
}
