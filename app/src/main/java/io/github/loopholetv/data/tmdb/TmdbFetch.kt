package io.github.loopholetv.data.tmdb

import io.github.loopholetv.data.db.TmdbEpisodeEntity
import io.github.loopholetv.data.db.TmdbTitleEntity
import java.time.Instant

/** Overviews missing in the TV's language are taken from this one. */
internal const val FALLBACK_LANGUAGE = "en-US"

/** Details ready to store: the title row, its genre ids and, for a show, every episode. */
internal class FetchedTitle(val title: TmdbTitleEntity, val genreIds: List<Int>, val episodes: List<TmdbEpisodeEntity>?)

internal sealed interface Fetch {
    data class Got(val value: FetchedTitle) : Fetch

    /** A fresh copy is already stored; nothing was requested. */
    data object Cached : Fetch

    /** TMDB has no such title, or it failed the caller's name check. */
    data object Missing : Fetch
    data object BadKey : Fetch

    /** A recording started; nothing more is requested. */
    data object Paused : Fetch
    data class Failed(val retryAfterSec: Long?) : Fetch
}

/** Fetches one title's details: a movie, or a show and every one of its seasons. Stops when [paused] turns true. */
internal class TmdbFetcher(private val client: TmdbDetailsSource, private val paused: () -> Boolean) {

    /** [accept] sees the localised and original title before anything else is requested. */
    suspend fun fetch(
        kind: TmdbKind,
        id: Long,
        language: String,
        now: Instant,
        accept: (title: String, originalTitle: String?) -> Boolean,
    ): Fetch = if (kind == TmdbKind.MOVIE) movie(id, language, now, accept) else show(id, language, now, accept)

    private suspend fun movie(id: Long, language: String, now: Instant, accept: (String, String?) -> Boolean): Fetch {
        if (paused()) return Fetch.Paused
        val m = when (val r = client.movie(id, language)) {
            is TmdbResult.Ok -> r.value
            else -> return r.notOk()
        }
        if (!accept(m.title, m.originalTitle)) return Fetch.Missing
        if (needsEnglish(m.overview, language) && paused()) return Fetch.Paused
        val overview = when (val r = overview(m.overview, language) { client.movie(id, FALLBACK_LANGUAGE).overview { it.overview } }) {
            is TmdbResult.Ok -> r.value
            else -> return r.notOk()
        }
        val row = TmdbTitleEntity(
            TmdbKind.MOVIE, id, m.title, m.originalTitle, m.year, overview, m.posterPath, m.backdropPath,
            m.voteAverage, m.voteCount, m.runtime, null, m.cast.joined(), language, now,
        )
        return Fetch.Got(FetchedTitle(row, m.genreIds, null))
    }

    private suspend fun show(id: Long, language: String, now: Instant, accept: (String, String?) -> Boolean): Fetch {
        if (paused()) return Fetch.Paused
        val s = when (val r = client.tv(id, language)) {
            is TmdbResult.Ok -> r.value
            else -> return r.notOk()
        }
        if (!accept(s.title, s.originalTitle)) return Fetch.Missing
        if (needsEnglish(s.overview, language) && paused()) return Fetch.Paused
        val overview = when (val r = overview(s.overview, language) { client.tv(id, FALLBACK_LANGUAGE).overview { it.overview } }) {
            is TmdbResult.Ok -> r.value
            else -> return r.notOk()
        }
        val episodes = mutableListOf<TmdbEpisodeEntity>()
        for (season in s.seasonNumbers.distinct()) {
            if (paused()) return Fetch.Paused
            when (val r = client.season(id, season, language)) {
                is TmdbResult.Ok -> r.value.distinctBy { it.number }.mapTo(episodes) {
                    TmdbEpisodeEntity(id, season, it.number, it.name, it.overview)
                }
                is TmdbResult.NotFound -> Unit
                else -> return r.notOk()
            }
        }
        val row = TmdbTitleEntity(
            TmdbKind.TV, id, s.title, s.originalTitle, s.year, overview, s.posterPath, s.backdropPath,
            s.voteAverage, s.voteCount, null, s.numberOfSeasons, s.cast.joined(), language, now,
        )
        return Fetch.Got(FetchedTitle(row, s.genreIds, episodes))
    }

    /** [local] when present (or already English), else the English overview; a missing English one is null. */
    private suspend fun overview(local: String?, language: String, english: suspend () -> TmdbResult<String?>): TmdbResult<String?> {
        if (!needsEnglish(local, language)) return TmdbResult.Ok(local)
        return when (val r = english()) {
            is TmdbResult.NotFound -> TmdbResult.Ok(null)
            else -> r
        }
    }

    private fun needsEnglish(local: String?, language: String) = local.isNullOrBlank() && language != FALLBACK_LANGUAGE

    private inline fun <T> TmdbResult<T>.overview(pick: (T) -> String?): TmdbResult<String?> = when (this) {
        is TmdbResult.Ok -> TmdbResult.Ok(pick(value))
        is TmdbResult.NotFound -> this
        is TmdbResult.BadKey -> this
        is TmdbResult.Failed -> this
    }

    private fun List<String>.joined(): String? = joinToString(", ").ifEmpty { null }
}

internal fun TmdbResult<*>.notOk(): Fetch = when (this) {
    is TmdbResult.NotFound -> Fetch.Missing
    is TmdbResult.BadKey -> Fetch.BadKey
    is TmdbResult.Failed -> Fetch.Failed(retryAfterSec)
    is TmdbResult.Ok -> error("not a failure")
}
