package io.github.loopholetv.data.tmdb

import java.util.Locale

enum class TmdbKeyType { V3, V4 }

enum class TmdbKind { MOVIE, TV }

/** Where TMDB images are served from; a size and the stored image path follow. */
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"

/** [TMDB_IMAGE_BASE] at poster size. */
const val TMDB_POSTER_BASE = "${TMDB_IMAGE_BASE}w342"

/** [TMDB_IMAGE_BASE] at backdrop size. */
const val TMDB_BACKDROP_BASE = "${TMDB_IMAGE_BASE}w1280"

private val V3_KEY = Regex("[0-9a-fA-F]{32}")
private val V4_KEY = Regex("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

/** V3 keys are 32 hex characters; V4 read tokens are JWTs. Anything else is not a TMDB key. */
fun tmdbKeyType(key: String): TmdbKeyType? {
    val k = key.trim()
    return when {
        V3_KEY.matches(k) -> TmdbKeyType.V3
        V4_KEY.matches(k) -> TmdbKeyType.V4
        else -> null
    }
}

/** `lang-COUNTRY`; a missing country falls back to `en-US` for English, else the language's own region. */
fun tmdbLanguage(locale: Locale): String {
    val lang = locale.language
    val country = locale.country
    return when {
        country.isNotBlank() -> "$lang-$country"
        lang == "en" -> "en-US"
        else -> "$lang-${lang.uppercase()}"
    }
}

sealed interface TmdbResult<out T> {
    data class Ok<T>(val value: T) : TmdbResult<T>
    data object NotFound : TmdbResult<Nothing>
    data object BadKey : TmdbResult<Nothing>
    data class Failed(val retryAfterSec: Long?) : TmdbResult<Nothing>
}

data class Genre(val id: Int, val name: String)

data class FindResult(val movieIds: List<Long>, val tvIds: List<Long>)

data class SearchHit(
    val id: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val voteCount: Int?,
)

data class MovieDetails(
    val id: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double?,
    val voteCount: Int?,
    val runtime: Int?,
    val genreIds: List<Int>,
    val cast: List<String>,
)

data class TvDetails(
    val id: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double?,
    val voteCount: Int?,
    val numberOfSeasons: Int?,
    val seasonNumbers: List<Int>,
    val genreIds: List<Int>,
    val cast: List<String>,
)

data class EpisodeDetails(val number: Int, val name: String?, val overview: String?)

// --- parsing of Moshi's generic JSON tree ---

internal const val CAST_LIMIT = 3

internal typealias JsonObj = Map<*, *>

internal fun Any?.obj(): JsonObj? = this as? Map<*, *>
internal fun Any?.arr(): List<*> = this as? List<*> ?: emptyList<Any?>()
internal fun JsonObj.str(name: String): String? = (this[name] as? String)?.takeIf { it.isNotBlank() }
internal fun JsonObj.num(name: String): Double? = (this[name] as? Number)?.toDouble()
internal fun JsonObj.int(name: String): Int? = num(name)?.toInt()
internal fun JsonObj.long(name: String): Long? = num(name)?.toLong()

internal fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

internal fun JsonObj.genreIds(): List<Int> = this["genres"].arr().mapNotNull { it.obj()?.int("id") }

internal fun JsonObj.topCast(): List<String> =
    this["credits"].obj()?.get("cast").arr().mapNotNull { it.obj()?.str("name") }.take(CAST_LIMIT)

internal fun JsonObj.idList(name: String): List<Long> = this[name].arr().mapNotNull { it.obj()?.long("id") }

internal fun JsonObj.toSearchHit(titleKey: String, originalKey: String, dateKey: String): SearchHit? {
    val id = long("id") ?: return null
    val title = str(titleKey) ?: return null
    return SearchHit(id, title, str(originalKey), yearOf(str(dateKey)), int("vote_count"))
}

internal fun JsonObj.toMovie(): MovieDetails? {
    val id = long("id") ?: return null
    return MovieDetails(
        id = id,
        title = str("title") ?: return null,
        originalTitle = str("original_title"),
        year = yearOf(str("release_date")),
        overview = str("overview"),
        posterPath = str("poster_path"),
        backdropPath = str("backdrop_path"),
        voteAverage = num("vote_average"),
        voteCount = int("vote_count"),
        runtime = int("runtime"),
        genreIds = genreIds(),
        cast = topCast(),
    )
}

internal fun JsonObj.toTv(): TvDetails? {
    val id = long("id") ?: return null
    return TvDetails(
        id = id,
        title = str("name") ?: return null,
        originalTitle = str("original_name"),
        year = yearOf(str("first_air_date")),
        overview = str("overview"),
        posterPath = str("poster_path"),
        backdropPath = str("backdrop_path"),
        voteAverage = num("vote_average"),
        voteCount = int("vote_count"),
        numberOfSeasons = int("number_of_seasons"),
        seasonNumbers = this["seasons"].arr().mapNotNull { it.obj()?.int("season_number") },
        genreIds = genreIds(),
        cast = topCast(),
    )
}

internal fun JsonObj.toEpisode(): EpisodeDetails? =
    int("episode_number")?.let { EpisodeDetails(it, str("name"), str("overview")) }
