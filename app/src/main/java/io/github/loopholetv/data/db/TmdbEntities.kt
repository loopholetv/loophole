package io.github.loopholetv.data.db

import androidx.room.Entity
import androidx.room.Index
import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.TitleKind
import java.time.Instant

/**
 * Details of one TMDB movie or show. [cast] is the top cast names joined by ", ". Only TMDB image
 * paths are stored. The title index serves the show cards' same-name check.
 */
@Entity(tableName = "tmdb_titles", primaryKeys = ["tmdbKind", "tmdbId"], indices = [Index("tmdbKind", "title")])
data class TmdbTitleEntity(
    val tmdbKind: TmdbKind,
    val tmdbId: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val voteCount: Int?,
    val runtimeMinutes: Int?,
    val seasonCount: Int?,
    val cast: String?,
    val language: String,
    val fetchedAt: Instant,
)

/** Links a title to one of its genres. */
@Entity(
    tableName = "tmdb_title_genres",
    primaryKeys = ["tmdbKind", "tmdbId", "genreId"],
    indices = [Index("genreId")],
)
data class TmdbTitleGenreEntity(val tmdbKind: TmdbKind, val tmdbId: Long, val genreId: Int)

/** A genre name in the language it was fetched in. */
@Entity(tableName = "tmdb_genres", primaryKeys = ["tmdbKind", "genreId"])
data class TmdbGenreEntity(val tmdbKind: TmdbKind, val genreId: Int, val name: String, val language: String)

/** One episode of a TMDB show, by season and episode number. */
@Entity(tableName = "tmdb_episodes", primaryKeys = ["tvId", "season", "episode"])
data class TmdbEpisodeEntity(
    val tvId: Long,
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
)

/** The outcome of matching a playlist film or show against TMDB. */
enum class MatchState { MATCHED, NOT_FOUND, REJECTED }

/**
 * The match for one playlist film or show. No foreign key: [key] is a film key or a show key, and rows
 * are pruned when the title has been absent from its playlist for long enough.
 */
@Entity(
    tableName = "vod_matches",
    primaryKeys = ["playlistId", "kind", "key"],
    indices = [Index("fingerprint"), Index("tmdbKind", "tmdbId")],
)
data class VodMatchEntity(
    val playlistId: Long,
    val kind: TitleKind,
    val key: String,
    val fingerprint: String,
    val state: MatchState,
    val tmdbKind: TmdbKind?,
    val tmdbId: Long?,
    val badge: String?,
    val sourceName: String,
    val sourceTvgId: String?,
    val lookedUpAt: Instant,
    val lastSeenAt: Instant,
)
