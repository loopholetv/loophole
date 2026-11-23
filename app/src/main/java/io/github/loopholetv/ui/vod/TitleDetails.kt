package io.github.loopholetv.ui.vod

import io.github.loopholetv.data.tmdb.TMDB_BACKDROP_BASE
import io.github.loopholetv.data.tmdb.TMDB_POSTER_BASE
import io.github.loopholetv.data.vod.TmdbDetail
import java.util.Locale

/**
 * The TMDB details a film or show page shows for a matched title. [runtimeMinutes] is set for
 * films and [seasonCount] for shows. [badge] is the quality tag (e.g. "4K") the playlist name carried.
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
