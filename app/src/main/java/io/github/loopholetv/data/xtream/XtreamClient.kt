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

    suspend fun vodCategories(c: XtreamCredentials, userAgent: String? = null): List<XtreamCategory> =
        categories(c, "get_vod_categories", userAgent)

    suspend fun seriesCategories(c: XtreamCredentials, userAgent: String? = null): List<XtreamCategory> =
        categories(c, "get_series_categories", userAgent)

    private suspend fun categories(c: XtreamCredentials, action: String, userAgent: String?): List<XtreamCategory> =
        execute(c, action, userAgent) { reader, job ->
            val out = mutableListOf<XtreamCategory>()
            forEachObjectInArray(reader, job, objectAsList = true) { r ->
                val f = readFields(r)
                val id = f["category_id"]
                val name = f["category_name"]
                if (id != null && name != null) out += XtreamCategory(id, name)
            }
            out
        }

    /** Streams each valid film to [onFilm] as it is parsed; entries without an id or a name are skipped. */
    suspend fun vodStreams(c: XtreamCredentials, userAgent: String? = null, onFilm: (XtreamFilm) -> Unit) {
        execute(c, "get_vod_streams", userAgent) { reader, job ->
            forEachObjectInArray(reader, job, objectAsList = true) { r ->
                val f = readFields(r)
                val id = f["stream_id"].toIdLenient()
                val name = (f["name"].blankToNull() ?: f["title"].blankToNull())?.trim()
                if (id != null && name != null) {
                    onFilm(
                        XtreamFilm(
                            streamId = id,
                            name = name,
                            icon = f["stream_icon"].blankToNull(),
                            categoryId = f["category_id"].blankToNull(),
                            container = f["container_extension"].blankToNull(),
                            tmdbId = (f["tmdb"] ?: f["tmdb_id"]).toTmdbId(),
                            year = yearOf(f),
                            rating = f["rating"].toRating(),
                        ),
                    )
                }
            }
        }
    }

    /** Streams each valid show to [onShow] as it is parsed; entries without an id or a name are skipped. */
    suspend fun series(c: XtreamCredentials, userAgent: String? = null, onShow: (XtreamShow) -> Unit) {
        execute(c, "get_series", userAgent) { reader, job ->
            forEachObjectInArray(reader, job, objectAsList = true) { r ->
                val (f, backdrop) = readFieldsWithBackdrop(r)
                val id = f["series_id"].toIdLenient()
                val name = (f["name"].blankToNull() ?: f["title"].blankToNull())?.trim()
                if (id != null && name != null) {
                    onShow(
                        XtreamShow(
                            seriesId = id,
                            name = name,
                            cover = f["cover"].blankToNull(),
                            categoryId = f["category_id"].blankToNull(),
                            plot = f["plot"].blankToNull(),
                            cast = f["cast"].blankToNull(),
                            genre = f["genre"].blankToNull(),
                            rating = f["rating"].toRating(),
                            year = yearOf(f),
                            backdrop = backdrop,
                            tmdbId = (f["tmdb"] ?: f["tmdb_id"]).toTmdbId(),
                            lastModified = f["last_modified"].blankToNull(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Every episode of one show, from `episodes` keyed by season or given as a flat list. A reply that
     * isn't an object, or one holding a login answer (`user_info`), is a refusal, never "no episodes".
     */
    suspend fun seriesInfo(c: XtreamCredentials, seriesId: Long, userAgent: String? = null): List<XtreamEpisode> =
        execute(c, "get_series_info", userAgent, listOf("series_id" to seriesId.toString())) { reader, job ->
            val out = mutableListOf<XtreamEpisode>()
            if (reader.peek() != Token.BEGIN_OBJECT) throw XtreamProtocolException(REFUSED_DETAILS)
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "user_info") throw XtreamProtocolException(REFUSED_DETAILS)
                if (name != "episodes") { reader.skipValue(); continue }
                when (reader.peek()) {
                    Token.BEGIN_ARRAY -> readEpisodes(reader, job, null, out)
                    Token.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val season = reader.nextName().toIntLenient()
                            if (reader.peek() == Token.BEGIN_ARRAY || reader.peek() == Token.BEGIN_OBJECT) {
                                readEpisodes(reader, job, season, out)
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out
        }

    private fun readEpisodes(reader: JsonReader, job: Job, keySeason: Int?, out: MutableList<XtreamEpisode>) {
        forEachObjectInArray(reader, job, objectAsList = true) { r ->
            var cover: String? = null
            val f = readFields(r, onObject = { name, nested ->
                if (name == "info") cover = readFields(nested)["movie_image"].blankToNull() else nested.skipValue()
            })
            val id = f["id"].toIdLenient() ?: return@forEachObjectInArray
            out += XtreamEpisode(
                id = id,
                season = f["season"].toIntLenient() ?: keySeason,
                episode = f["episode_num"].toIntLenient(),
                title = f["title"].blankToNull(),
                container = f["container_extension"].blankToNull(),
                cover = cover,
            )
        }
    }

    /**
     * Details of one film; a reply without `info` gives an all-null result. A reply that isn't an
     * object, or one holding a login answer (`user_info`), is a refusal, never "no details".
     */
    suspend fun vodInfo(c: XtreamCredentials, vodId: Long, userAgent: String? = null): XtreamFilmInfo =
        execute(c, "get_vod_info", userAgent, listOf("vod_id" to vodId.toString())) { reader, _ ->
            var result = XtreamFilmInfo(null, null, null, null, null, null, null)
            if (reader.peek() != Token.BEGIN_OBJECT) throw XtreamProtocolException(REFUSED_DETAILS)
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "user_info") throw XtreamProtocolException(REFUSED_DETAILS)
                if (name == "info" && reader.peek() == Token.BEGIN_OBJECT) {
                    val (f, backdrop) = readFieldsWithBackdrop(reader)
                    result = XtreamFilmInfo(
                        plot = (f["plot"].blankToNull() ?: f["description"].blankToNull()),
                        cast = (f["cast"].blankToNull() ?: f["actors"].blankToNull()),
                        genre = f["genre"].blankToNull(),
                        rating = f["rating"].toRating(),
                        year = yearOf(f),
                        runtimeMinutes = runtimeMinutes(f),
                        backdrop = backdrop,
                    )
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            result
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
        params: List<Pair<String, String>> = emptyList(),
        parse: (JsonReader, Job) -> T,
    ): T {
        val secrets = listOf(c.username, c.password)
        val request = Request.Builder().url(buildUrl(c, action, params))
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

    private fun buildUrl(c: XtreamCredentials, action: String?, params: List<Pair<String, String>>): HttpUrl {
        return baseUrl(c).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
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

    /** Epoch seconds; absent, empty or 0 while the login reports a status is an unlimited account. */
    private fun expiry(f: Map<String, String>): Instant? {
        val raw = f["exp_date"]?.trim().orEmpty()
        if (raw.isEmpty() || raw == "0") return if (f["status"] != null) NEVER_EXPIRES else null
        return raw.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
    }

    private fun toAccount(user: InfoObject, server: InfoObject?): XtreamAccount {
        val f = user.fields
        if (f["auth"].toIntLenient() == 0) throw XtreamAuthException("Server rejected the credentials")
        val s = server?.fields.orEmpty()
        val formats = user.formats.ifEmpty { server?.formats.orEmpty() }
        return XtreamAccount(
            status = f["status"] ?: "",
            maxConnections = f["max_connections"].toIntLenient(),
            expiresAt = expiry(f),
            serverTimeZone = xtreamZone(s["timezone"], s["time_now"], s["timestamp_now"]?.toLongOrNull()),
            archiveExt = archiveExt(formats),
        )
    }

    /**
     * Iterates a top-level array, passing each object to [each]; non-object elements are skipped.
     * `null` and `{}` count as an empty list. With [objectAsList] an object is read as a list of its
     * object values (non-object values are skipped); one holding `user_info` is a refusal (a login
     * answer), never an empty list. Checks [job] for cancellation per element.
     */
    private fun forEachObjectInArray(
        reader: JsonReader,
        job: Job,
        objectAsList: Boolean = false,
        each: (JsonReader) -> Unit,
    ) {
        when (reader.peek()) {
            Token.NULL -> { reader.nextNull<Unit>(); return }
            Token.BEGIN_OBJECT -> {
                if (!objectAsList) { reader.skipValue(); return }
                reader.beginObject()
                while (reader.hasNext()) {
                    job.ensureActive()
                    if (reader.nextName() == "user_info") throw XtreamProtocolException("The server refused the list")
                    if (reader.peek() == Token.BEGIN_OBJECT) each(reader) else reader.skipValue()
                }
                reader.endObject()
                return
            }
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

    /**
     * Reads one object into scalar fields rendered as strings. Nested arrays go to [onArray] and
     * nested objects to [onObject]; both must consume the value, and by default skip it.
     */
    private fun readFields(
        r: JsonReader,
        onObject: (String, JsonReader) -> Unit = { _, reader -> reader.skipValue() },
        onArray: (String, JsonReader) -> Unit = { _, reader -> reader.skipValue() },
    ): Map<String, String> {
        val out = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                Token.STRING, Token.NUMBER -> out[name] = r.nextString()
                Token.BOOLEAN -> out[name] = if (r.nextBoolean()) "1" else "0"
                Token.NULL -> r.nextNull<Unit>()
                Token.BEGIN_ARRAY -> onArray(name, r)
                Token.BEGIN_OBJECT -> onObject(name, r)
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

    /** Scalar fields plus `backdrop_path`, which is a string or an array whose first string counts. */
    private fun readFieldsWithBackdrop(r: JsonReader): Pair<Map<String, String>, String?> {
        var first: String? = null
        val f = readFields(r, onArray = { name, reader ->
            if (name == "backdrop_path") {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (first == null && reader.peek() == Token.STRING) first = reader.nextString().blankToNull() else reader.skipValue()
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        })
        return f to (f["backdrop_path"].blankToNull() ?: first)
    }

    /** A positive id given as a number or a numeric string (`12`, `"12"`, `"12.0"`). */
    private fun String?.toIdLenient(): Long? {
        val s = this?.trim() ?: return null
        val n = s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        return n?.takeIf { it > 0 }
    }

    private fun String?.toTmdbId(): Long? {
        val s = this?.trim() ?: return null
        return if (s.isNotEmpty() && s.all { it in '0'..'9' }) s.toLongOrNull()?.takeIf { it > 0 } else null
    }

    private fun String?.toRating(): Double? =
        this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it != 0.0 }

    private fun asYear(s: String?): Int? {
        val t = s?.trim() ?: return null
        if (t.length < 4 || !t.take(4).all { it in '0'..'9' }) return null
        return t.take(4).toInt().takeIf { it in 1900..2100 }
    }

    /** `year` when it is a plain 4-digit year, else the year of the first release date field present. */
    private fun yearOf(f: Map<String, String>): Int? {
        f["year"]?.trim()?.takeIf { it.length == 4 }?.let { y -> asYear(y)?.let { return it } }
        return listOf("releaseDate", "release_date", "releasedate").firstNotNullOfOrNull { asYear(f[it]) }
    }

    /** Running time in minutes: `duration_secs`, else a `HH:MM:SS` `duration`, else `episode_run_time`. */
    private fun runtimeMinutes(f: Map<String, String>): Int? {
        f["duration_secs"]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?.let { return Math.round(it / 60.0).toInt().takeIf { m -> m > 0 } }
        f["duration"]?.let { d ->
            val parts = d.trim().split(":").map { it.toIntOrNull() ?: return@let }
            if (parts.size == 3) {
                val secs = parts[0] * 3600 + parts[1] * 60 + parts[2]
                if (secs > 0) return Math.round(secs / 60.0).toInt().takeIf { m -> m > 0 }
            }
        }
        return f["episode_run_time"].toIntLenient()?.takeIf { it > 0 }
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    private companion object {
        const val REFUSED_DETAILS = "The server refused the request"
    }
}
