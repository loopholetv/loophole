package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.TmdbEpisodeEntity
import io.github.loopholetv.data.db.TmdbTitleEntity

/** A film as cards show it: TMDB's title and poster when matched. [badge] is the quality tag (e.g. "4K") of a matched film. */
data class FilmCard(val playlistId: Long, val key: String, val name: String, val logo: String?, val badge: String? = null)

/**
 * A show as cards show it, like [FilmCard]. [playlistName] is set only when another enabled
 * playlist has a show shown under the same name.
 */
data class ShowCard(
    val playlistId: Long,
    val key: String,
    val name: String,
    val logo: String?,
    val playlistName: String?,
    val badge: String? = null,
)

/** Whether a My list or dismissal entry is a film or a show; stored by name. */
enum class TitleKind { FILM, SHOW }

data class CategoryRow(val title: String, val count: Int)

data class FilmDetail(val film: FilmEntity, val playlistName: String)

/** [number] null is the "Other" group: episodes without a season. */
data class Season(val number: Int?, val episodes: List<EpisodeEntity>)

/** Seasons ascending, "Other" last; episodes in playing order. */
data class ShowDetail(val show: ShowEntity, val seasons: List<Season>)

/**
 * TMDB's details for a matched film or show: its [genres] by name, the quality [badge] the name
 * cleaner found, and, for a show, the [episodes] its season lookups found.
 */
data class TmdbDetail(
    val title: TmdbTitleEntity,
    val genres: List<String>,
    val badge: String?,
    val episodes: List<TmdbEpisodeEntity> = emptyList(),
)

data class HiddenCategory(val title: String, val playlistIds: List<Long>)
