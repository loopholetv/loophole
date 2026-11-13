package io.github.loopholetv.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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
)

/** A show derived from its episodes; rebuilt on every refresh. */
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
