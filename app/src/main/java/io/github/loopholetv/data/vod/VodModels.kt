package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity

data class FilmCard(val playlistId: Long, val key: String, val name: String, val logo: String?)

/** [playlistName] is set only when another enabled playlist has a show with the same name. */
data class ShowCard(val playlistId: Long, val key: String, val name: String, val logo: String?, val playlistName: String?)

data class CategoryRow(val title: String, val count: Int)

data class FilmDetail(val film: FilmEntity, val playlistName: String)

/** [number] null is the "Other" group: episodes without a season. */
data class Season(val number: Int?, val episodes: List<EpisodeEntity>)

/** Seasons ascending, "Other" last; episodes in playing order. */
data class ShowDetail(val show: ShowEntity, val seasons: List<Season>)

data class HiddenCategory(val title: String, val playlistIds: List<Long>)
