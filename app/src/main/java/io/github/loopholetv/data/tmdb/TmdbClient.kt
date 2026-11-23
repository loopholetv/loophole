package io.github.loopholetv.data.tmdb

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** The lookups plus the detail requests the background sync makes; [TmdbClient] is the production implementation. */
interface TmdbDetailsSource : TmdbLookup {
    suspend fun movie(id: Long, language: String): TmdbResult<MovieDetails>
    suspend fun tv(id: Long, language: String): TmdbResult<TvDetails>
    suspend fun season(tvId: Long, season: Int, language: String): TmdbResult<List<EpisodeDetails>>
    suspend fun genres(kind: TmdbKind, language: String): TmdbResult<List<Genre>>
}

/**
 * Minimal TMDB v3 API client. [key] is read for every request and may be a V3 key or a V4 read token.
 * The key is never put in an exception, a log line or [toString]; all failures collapse to
 * [TmdbResult.Failed] without their messages.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl(),
    private val key: suspend () -> String?,
    private val nanoClock: () -> Long = System::nanoTime,
) : TmdbDetailsSource {
    private val paceLock = Mutex()
    private var lastRequestNanos: Long? = null

    suspend fun configuration(): TmdbResult<Unit> = get("configuration", emptyMap()) { }

    override suspend fun findImdb(id: String, language: String): TmdbResult<FindResult> =
        get("find/$id", mapOf("external_source" to "imdb_id", "language" to language)) { root ->
            FindResult(root.idList("movie_results"), root.idList("tv_results"))
        }

    override suspend fun searchMovie(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>> =
        get("search/movie", search(query, year?.let { "year" to it }, language)) { root ->
            root["results"].arr().mapNotNull { it.obj()?.toSearchHit("title", "original_title", "release_date") }
        }

    override suspend fun searchTv(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>> =
        get("search/tv", search(query, year?.let { "first_air_date_year" to it }, language)) { root ->
            root["results"].arr().mapNotNull { it.obj()?.toSearchHit("name", "original_name", "first_air_date") }
        }

    override suspend fun movie(id: Long, language: String): TmdbResult<MovieDetails> =
        get("movie/$id", details(language)) { it.toMovie() }

    override suspend fun tv(id: Long, language: String): TmdbResult<TvDetails> =
        get("tv/$id", details(language)) { it.toTv() }

    override suspend fun season(tvId: Long, season: Int, language: String): TmdbResult<List<EpisodeDetails>> =
        get("tv/$tvId/season/$season", mapOf("language" to language)) { root ->
            root["episodes"].arr().mapNotNull { it.obj()?.toEpisode() }
        }

    override suspend fun genres(kind: TmdbKind, language: String): TmdbResult<List<Genre>> {
        val path = if (kind == TmdbKind.MOVIE) "genre/movie/list" else "genre/tv/list"
        return get(path, mapOf("language" to language)) { root ->
            root["genres"].arr().mapNotNull { g ->
                val o = g.obj()
                val id = o?.int("id")
                val name = o?.str("name")
                if (id != null && name != null) Genre(id, name) else null
            }
        }
    }

    override fun toString(): String = "TmdbClient"

    private fun search(query: String, year: Pair<String, Int>?, language: String): Map<String, String> =
        buildMap {
            put("query", query)
            put("language", language)
            if (year != null) put(year.first, year.second.toString())
        }

    private fun details(language: String) = mapOf("language" to language, "append_to_response" to "credits")

    // --- transport ---

    /** Spaces requests at least [PACE_MS] apart, across concurrent callers. */
    private suspend fun pace() = paceLock.withLock {
        lastRequestNanos?.let { last ->
            val waitMs = PACE_MS - (nanoClock() - last) / 1_000_000L
            if (waitMs > 0) delay(waitMs)
        }
        lastRequestNanos = nanoClock()
    }

    private suspend fun <T> get(
        path: String,
        params: Map<String, String>,
        parse: (JsonObj) -> T?,
    ): TmdbResult<T> {
        val request = try {
            val rawKey = key()?.trim().orEmpty()
            val type = tmdbKeyType(rawKey) ?: return TmdbResult.BadKey
            val url = baseUrl.newBuilder().apply {
                path.split('/').forEach { addPathSegment(it) }
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                if (type == TmdbKeyType.V3) addQueryParameter("api_key", rawKey)
            }.build()
            Request.Builder().url(url).apply {
                if (type == TmdbKeyType.V4) header("Authorization", "Bearer $rawKey")
            }.build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Messages can echo the key or URL, so none is propagated.
            return TmdbResult.Failed(null)
        }
        pace()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                // Exception messages can carry the request URL, so none are read or propagated.
                override fun onFailure(call: Call, e: IOException) {
                    cont.resume(TmdbResult.Failed(null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { read(it, parse) }
                    } catch (e: Exception) {
                        TmdbResult.Failed(null)
                    }
                    cont.resume(result)
                }
            })
        }
    }

    /**
     * Only TMDB's own verdict on a title is [TmdbResult.NotFound]: HTTP 404 or 422, or a JSON object
     * without what the title needs (e.g. no title), so one bad title is set aside instead of failing every
     * run. Anything a network login page, proxy or outage could send (other 4xx, 5xx, a body that is not a
     * JSON object) says nothing about the title and is [TmdbResult.Failed].
     */
    private fun <T> read(response: Response, parse: (JsonObj) -> T?): TmdbResult<T> = when (response.code) {
        in 200..299 -> {
            val root = try {
                JsonReader.of(response.body.source()).readJsonValue().obj()
            } catch (e: JsonEncodingException) {
                null
            } catch (e: JsonDataException) {
                null
            }
            when {
                root == null -> TmdbResult.Failed(null)
                else -> parse(root)?.let { TmdbResult.Ok(it) } ?: TmdbResult.NotFound
            }
        }
        401 -> TmdbResult.BadKey
        404, 422 -> TmdbResult.NotFound
        429 -> TmdbResult.Failed(retryAfterSeconds(response.header("Retry-After")))
        else -> TmdbResult.Failed(null)
    }

    /** Integer seconds only, clamped to 1..3600; a missing, HTTP-date or junk value gives the default. */
    private fun retryAfterSeconds(header: String?): Long =
        header?.trim()?.toLongOrNull()?.coerceIn(1L, 3600L) ?: DEFAULT_RETRY_SEC

    private companion object {
        const val PACE_MS = 50L
        const val DEFAULT_RETRY_SEC = 10L
    }
}
