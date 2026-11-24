package io.github.loopholetv.ui.vod

import io.github.loopholetv.data.tmdb.TMDB_BACKDROP_BASE
import io.github.loopholetv.data.tmdb.TMDB_POSTER_BASE
import io.github.loopholetv.data.vod.TmdbDetail
import java.util.Locale

/**
 * The details a film or show page shows: TMDB's for a matched title, else the provider's
 * ([fromTmdb] false, so there is no match to reject). [runtimeMinutes] is set for films and
 * [seasonCount] for shows. [badge] is the quality tag (e.g. "4K") the playlist name carried.
 */
data class TitleDetailsUi(
    val year: Int?,
    val runtimeMinutes: Int?,
    val seasonCount: Int?,
    val rating: Double?,
    val genres: List<String>,
    val overview: String?,
    val cast: List<String>,
    val backdropUrl: String?,
    val badge: String?,
    val fromTmdb: Boolean = true,
)

private const val SEPARATOR = " · "

/** "1999 · 2 h 16 min · ★ 8.2 · Action, Sci-Fi"; a show has "3 seasons" for the running time. Missing parts are left out. */
fun formatMeta(d: TitleDetailsUi): String = listOfNotNull(
    d.year?.toString(),
    d.runtimeMinutes?.takeIf { it > 0 }?.let(::runtimeText),
    d.seasonCount?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "season" else "seasons"}" },
    d.rating?.takeIf { it > 0.0 }?.let { "★ " + String.format(Locale.ROOT, "%.1f", it) },
    d.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString(SEPARATOR)

/** "45 min", "2 h" or "2 h 16 min". */
private fun runtimeText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

internal fun TmdbDetail.toUi(): TitleDetailsUi = TitleDetailsUi(
    year = title.year,
    runtimeMinutes = title.runtimeMinutes,
    seasonCount = title.seasonCount,
    rating = title.rating,
    genres = genres,
    overview = title.overview?.takeIf { it.isNotBlank() },
    cast = title.cast.orEmpty().split(", ").map { it.trim() }.filter { it.isNotEmpty() },
    backdropUrl = title.backdropPath?.let { TMDB_BACKDROP_BASE + it },
    badge = badge,
)

/** TMDB's poster for a matched title, or the playlist's [fallback]. */
internal fun TmdbDetail?.posterOr(fallback: String?): String? = this?.title?.posterPath?.let { TMDB_POSTER_BASE + it } ?: fallback

/**
 * The provider's own details (an Xtream show or film), or null when it gives none. Genres may be
 * split by `,`, `/` or `|`; cast by `,`. Only a web address is used as the backdrop.
 */
fun providerDetailsUi(
    plot: String?,
    cast: String?,
    genre: String?,
    rating: Double?,
    year: Int?,
    runtimeMinutes: Int?,
    backdrop: String?,
): TitleDetailsUi? {
    val d = TitleDetailsUi(
        year = year?.takeIf { it > 0 },
        runtimeMinutes = runtimeMinutes?.takeIf { it > 0 },
        seasonCount = null,
        rating = rating?.takeIf { it > 0.0 },
        genres = genre.splitList(GENRE_SEPARATORS),
        overview = plot?.trim()?.takeIf { it.isNotEmpty() },
        cast = cast.splitList(CAST_SEPARATORS),
        backdropUrl = backdrop?.trim()?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) },
        badge = null,
        fromTmdb = false,
    )
    val empty = d.year == null && d.runtimeMinutes == null && d.rating == null && d.genres.isEmpty() &&
        d.overview == null && d.cast.isEmpty() && d.backdropUrl == null
    return d.takeUnless { empty }
}

private val GENRE_SEPARATORS = charArrayOf(',', '/', '|')
private val CAST_SEPARATORS = charArrayOf(',')

private fun String?.splitList(separators: CharArray): List<String> =
    this?.split(*separators)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
