package io.github.loopholetv.data.tmdb

import kotlin.math.abs

/** The lookups the matcher needs; [TmdbClient] is the production implementation. */
interface TmdbLookup {
    suspend fun findImdb(id: String, language: String): TmdbResult<FindResult>
    suspend fun searchMovie(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>>
    suspend fun searchTv(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>>
}

sealed interface MatchOutcome {
    data class Matched(val kind: TmdbKind, val id: Long) : MatchOutcome
    data object NotFound : MatchOutcome
    data object BadKey : MatchOutcome
    data class Failed(val retryAfterSec: Long?) : MatchOutcome
}

private val IMDB_ID = Regex("tt\\d+")
private val NUMERIC_ID = Regex("\\d+")
private const val YEAR_TOLERANCE = 1

/**
 * Decides which TMDB id a playlist title is. Prefers a provider id, then a strict name and year search
 * (the year in the name, else the provider's [matchFilm]/[matchShow] `year`):
 * only the top hit is considered, and only when its title is exactly the cleaned name. No match is better
 * than a wrong one.
 */
class TmdbMatcher(private val lookup: TmdbLookup) {

    suspend fun matchFilm(name: String, tvgId: String?, language: String, year: Int? = null): MatchOutcome {
        val id = tvgId?.trim().orEmpty()
        if (IMDB_ID.matches(id)) {
            when (val found = lookup.findImdb(id, language)) {
                is TmdbResult.Ok -> found.value.movieIds.firstOrNull()?.let {
                    return MatchOutcome.Matched(TmdbKind.MOVIE, it)
                }
                is TmdbResult.BadKey -> return MatchOutcome.BadKey
                is TmdbResult.Failed -> return MatchOutcome.Failed(found.retryAfterSec)
                is TmdbResult.NotFound -> Unit
            }
        }
        return searchByName(name, TmdbKind.MOVIE, language, year)
    }

    suspend fun matchShow(name: String, tvgId: String?, language: String, year: Int? = null): MatchOutcome {
        val id = tvgId?.trim().orEmpty()
        if (NUMERIC_ID.matches(id)) {
            id.toLongOrNull()?.let { return MatchOutcome.Matched(TmdbKind.TV, it) }
        }
        if (IMDB_ID.matches(id)) {
            when (val found = lookup.findImdb(id, language)) {
                is TmdbResult.Ok -> found.value.tvIds.firstOrNull()?.let {
                    return MatchOutcome.Matched(TmdbKind.TV, it)
                }
                is TmdbResult.BadKey -> return MatchOutcome.BadKey
                is TmdbResult.Failed -> return MatchOutcome.Failed(found.retryAfterSec)
                is TmdbResult.NotFound -> Unit
            }
        }
        return searchByName(name, TmdbKind.TV, language, year)
    }

    /** [providerYear] is used only when the name carries no year of its own. */
    private suspend fun searchByName(name: String, kind: TmdbKind, language: String, providerYear: Int?): MatchOutcome {
        val clean = cleanTitle(name)
        if (clean.query.isBlank()) return MatchOutcome.NotFound
        val year = clean.year ?: providerYear
        val search: suspend (String, Int?) -> TmdbResult<List<SearchHit>> = { q, y ->
            if (kind == TmdbKind.MOVIE) lookup.searchMovie(q, y, language) else lookup.searchTv(q, y, language)
        }

        var hits = when (val r = search(clean.query, year)) {
            is TmdbResult.Ok -> r.value
            is TmdbResult.NotFound -> emptyList()
            is TmdbResult.BadKey -> return MatchOutcome.BadKey
            is TmdbResult.Failed -> return MatchOutcome.Failed(r.retryAfterSec)
        }
        if (hits.isEmpty() && year != null) {
            hits = when (val r = search(clean.query, null)) {
                is TmdbResult.Ok -> r.value
                is TmdbResult.NotFound -> emptyList()
                is TmdbResult.BadKey -> return MatchOutcome.BadKey
                is TmdbResult.Failed -> return MatchOutcome.Failed(r.retryAfterSec)
            }
        }
        accepted(hits.firstOrNull(), clean.query, year)?.let { return MatchOutcome.Matched(kind, it.id) }

        // A bare trailing number may belong to the title ("Wonder Woman 1984").
        if (clean.year != null && !clean.yearBracketed) {
            val withYear = "${clean.query} ${clean.year}"
            when (val r = search(withYear, null)) {
                is TmdbResult.Ok -> {
                    val top = r.value.firstOrNull()
                    if (top != null && titleMatches(top, withYear)) return MatchOutcome.Matched(kind, top.id)
                }
                is TmdbResult.NotFound -> Unit
                is TmdbResult.BadKey -> return MatchOutcome.BadKey
                is TmdbResult.Failed -> return MatchOutcome.Failed(r.retryAfterSec)
            }
        }
        return MatchOutcome.NotFound
    }

    private fun accepted(top: SearchHit?, query: String, year: Int?): SearchHit? {
        if (top == null || !titleMatches(top, query)) return null
        val hitYear = top.year
        if (year != null && hitYear != null && abs(year - hitYear) > YEAR_TOLERANCE) return null
        return top
    }

    private fun titleMatches(hit: SearchHit, query: String): Boolean {
        val q = normaliseTitle(query)
        if (q.isEmpty()) return false
        return q == normaliseTitle(hit.title) || hit.originalTitle?.let { q == normaliseTitle(it) } == true
    }
}
