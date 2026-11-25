package io.github.loopholetv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import java.time.Instant

/** A film from a playlist entry typed `movie`. Identified by playlist and [key] (the stream URL without query). */
@Entity(
    tableName = "vod_films",
    primaryKeys = ["playlistId", "key"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId", "category", "position"), Index("category", "playlistId", "position"), Index("firstSeenAt")],
)
data class FilmEntity(
    val playlistId: Long,
    val key: String,
    val name: String,
    val category: String,
    val logo: String?,
    val streamUrl: String,
    val userAgent: String?,
    val referrer: String?,
    val tvgId: String?,
    val position: Int,
    val firstSeenAt: Instant,
    /** Xtream: the stream's file extension, used to build its URL at play time. */
    val container: String? = null,
    /** Xtream: the year and rating from the film list. */
    val providerYear: Int? = null,
    val providerRating: Double? = null,
)

/** One episode from a playlist entry typed `series`, grouped under the show [showKey]. */
@Entity(
    tableName = "vod_episodes",
    primaryKeys = ["playlistId", "key"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId", "showKey"), Index("firstSeenAt")],
)
data class EpisodeEntity(
    val playlistId: Long,
    val key: String,
    val showKey: String,
    val groupTitle: String,
    val name: String,
    val season: Int?,
    val episode: Int?,
    val logo: String?,
    val streamUrl: String,
    val userAgent: String?,
    val referrer: String?,
    val tvgId: String?,
    val position: Int,
    val firstSeenAt: Instant,
    /** Xtream: the stream's file extension, used to build its URL at play time. */
    val container: String? = null,
)

/** Where a show row comes from: grouped from M3U episodes, or listed by an Xtream account. */
enum class ShowSource { M3U, XTREAM }

/** A show: derived from its episodes and rebuilt on every refresh ([ShowSource.M3U]), or listed by an Xtream account. */
@Entity(
    tableName = "vod_shows",
    primaryKeys = ["playlistId", "key"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("category", "name"), Index("name")],
)
data class ShowEntity(
    val playlistId: Long,
    val key: String,
    val name: String,
    val groupTitle: String,
    val category: String?,
    val logo: String?,
    val firstSeenAt: Instant,
    val position: Int,
    @ColumnInfo(defaultValue = "M3U") val source: ShowSource = ShowSource.M3U,
    /** Xtream: the TMDB hint the provider gives. */
    val tvgId: String? = null,
    /** Xtream: the provider's last-modified value; a change marks the episodes stale. */
    val lastModified: String? = null,
    /** Xtream: when the episodes were last fetched; null = never, or stale. */
    val episodesFetchedAt: Instant? = null,
    // Xtream: details from the show list.
    val plot: String? = null,
    val cast: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val backdrop: String? = null,
)

/** Film details from the Xtream provider. No foreign key: pruned along with the film. */
@Entity(tableName = "vod_provider_details", primaryKeys = ["playlistId", "key"])
data class ProviderDetailsEntity(
    val playlistId: Long,
    val key: String,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val rating: Double?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val backdrop: String?,
    val fetchedAt: Instant,
)

/** Watch progress for a film or episode. No foreign key: progress outlives a refresh that drops the item. */
@Entity(
    tableName = "vod_progress",
    primaryKeys = ["kind", "playlistId", "key"],
    indices = [Index("lastWatchedAt")],
)
data class VodProgressEntity(
    val kind: VodKind,
    val playlistId: Long,
    val key: String,
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val lastWatchedAt: Instant,
)

/** A film or show on My list; [key] is the film key or the show key. No foreign key, like progress. */
@Entity(
    tableName = "vod_my_list",
    primaryKeys = ["kind", "playlistId", "key"],
    indices = [Index("addedAt")],
)
data class MyListEntity(
    val kind: TitleKind,
    val playlistId: Long,
    val key: String,
    val addedAt: Instant,
)

/** A film or show hidden from Continue watching until it is watched again. No foreign key, like progress. */
@Entity(
    tableName = "vod_continue_dismissed",
    primaryKeys = ["kind", "playlistId", "key"],
)
data class ContinueDismissalEntity(
    val kind: TitleKind,
    val playlistId: Long,
    val key: String,
    val dismissedAt: Instant,
)

/** A user's edits to one show, keyed by its show key. Rows follow their playlist. */
@Entity(
    tableName = "show_prefs",
    primaryKeys = ["playlistId", "showKey"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ShowPrefsEntity(
    val playlistId: Long,
    val showKey: String,
    /** Hidden behind the PIN until unlocked. */
    val locked: Boolean = false,
)

/** The single row (id 1) saying whether locked items are currently unlocked. */
@Entity(tableName = "lock_state")
data class LockStateEntity(
    @PrimaryKey val id: Int = 1,
    val unlocked: Boolean = false,
)
