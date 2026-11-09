package io.github.loopholetv.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.Instant

/**
 * Stored by name (TEXT). For [M3U_FILE] `url` holds the `content://` URI or absolute path; for
 * [STALKER] `url` holds the portal address as typed.
 */
enum class PlaylistType { M3U, XTREAM, M3U_FILE, STALKER }

/** How a group's channels are ordered in the guide. Stored by name (TEXT). */
enum class GroupSort { PLAYLIST, NAME, CUSTOM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    val url: String?,
    val xtreamServer: String?,
    val xtreamUser: String?,
    val xtreamPass: String?,
    /** EPG URLs announced by the source, newline-joined (empty when none). */
    val epgUrlsFromSource: String,
    /** Moved into `epg_sources` by MIGRATION_3_4; kept as a column, no longer read once EPG sources are. */
    val manualEpgUrl: String?,
    val lastRefreshedAt: Instant?,
    /** Already redacted. */
    val lastError: String?,
    /** A turned-off playlist's channels are hidden everywhere and it is not refreshed. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Playlist user agent (null = use the general one). */
    val userAgent: String? = null,
    /** Update interval in hours; null = "Same as default". */
    val updateHours: Int? = null,
    /** Update on app start; null = "Same as default". */
    val updateOnStart: Boolean? = null,
    /** Whether the playlist's own announced guide is used. */
    @ColumnInfo(defaultValue = "1") val useSourceEpg: Boolean = true,
    /** The address or login changed: the next refresh carries edits over by name and group. */
    @ColumnInfo(defaultValue = "0") val rekeyPending: Boolean = false,
    // --- MAC portal (STALKER) only; every one of these is a secret for redact() ---
    val mac: String? = null,
    /** The working `load.php` endpoint found by the probe. */
    val portalEndpoint: String? = null,
    val portalToken: String? = null,
    val portalLogin: String? = null,
    val portalPassword: String? = null,
    val portalSerial: String? = null,
    val portalDeviceId: String? = null,
    val portalDeviceId2: String? = null,
    val portalSignature: String? = null,
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "key"], unique = true),
        Index(value = ["playlistId", "groupTitle", "sortOrder"]),
        Index(value = ["playlistId", "groupTitle", "groupRank"]),
        // The import's fallback numbering filters by (playlistId, number).
        Index(value = ["playlistId", "number"]),
        Index(value = ["visible", "guideNumber"]),
        // Programme search joins programmes to channels on the guide id.
        Index(value = ["guideEpgId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Xtream stream id, or the stream URL for M3U. Unique within a playlist. */
    val key: String,
    val number: Int,
    val name: String,
    val streamUrl: String,
    val logo: String?,
    /** "Uncategorised" when the source has no group. */
    val groupTitle: String,
    /** Position of the group's first appearance in the source (0-based). */
    val groupOrder: Int,
    /** Position of the channel in the source (0-based). */
    val sortOrder: Int,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
    val catchupType: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    /** Resolved later by the EPG matcher. */
    val epgChannelId: String?,
    // --- Derived columns: recomputed from the playlist, channel_prefs and group_prefs (never the only copy of an edit) ---
    /** Playlist enabled, and neither the channel nor its group hidden. */
    @ColumnInfo(defaultValue = "1") val visible: Boolean = true,
    /**
     * The number shown and dialled (unique across enabled playlists). Defaults to [number] in Kotlin
     * so directly built rows match an unedited single playlist; the SQL default stays a constant.
     */
    @ColumnInfo(defaultValue = "0") val guideNumber: Int = number,
    /** Position within its group under the group's sort mode (defaults to [number], like the migration). */
    @ColumnInfo(defaultValue = "0") val groupRank: Int = number,
    val customName: String? = null,
    val customLogo: String? = null,
    /** The guide id actually used: the manual override, else [epgChannelId] (the default). */
    val guideEpgId: String? = epgChannelId,
    /** Guide time shift in minutes. */
    @ColumnInfo(defaultValue = "0") val epgShiftMinutes: Int = 0,
    /** MAC portal channel whose stream link must be created at play time. */
    @ColumnInfo(defaultValue = "0") val needsLink: Boolean = false,
)

val ChannelEntity.displayName: String get() = customName ?: name
val ChannelEntity.displayLogo: String? get() = customLogo ?: logo

/**
 * A user's edits to one channel, keyed like favourites so they survive refreshes. A row whose
 * channel has vanished is kept: the edit returns with the channel.
 */
@Entity(
    tableName = "channel_prefs",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelPrefsEntity(
    val playlistId: Long,
    val channelKey: String,
    val hidden: Boolean = false,
    val customName: String? = null,
    val customLogo: String? = null,
    /** Manual guide channel id (wins over the matcher). */
    val epgOverride: String? = null,
    val epgShiftMinutes: Int = 0,
    /** Manual position within its group (used by [GroupSort.CUSTOM]). */
    val position: Int? = null,
) {
    /** True when the row holds no edit (it can be deleted). */
    fun isDefault(): Boolean = this == ChannelPrefsEntity(playlistId, channelKey)
}

/** A user's edits to one group, keyed by its original title. */
@Entity(
    tableName = "group_prefs",
    primaryKeys = ["playlistId", "groupTitle"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupPrefsEntity(
    val playlistId: Long,
    val groupTitle: String,
    val hidden: Boolean = false,
    val customName: String? = null,
    val position: Int? = null,
    val sortMode: GroupSort = GroupSort.PLAYLIST,
) {
    /** True when the row holds no edit (it can be deleted). */
    fun isDefault(): Boolean = this == GroupPrefsEntity(playlistId, groupTitle)
}

/** An added guide link of a playlist; [position] orders them (first source with a channel wins). */
@Entity(
    tableName = "epg_sources",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId", "position"])],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val url: String,
    val position: Int,
)

/** A channel listed by one of a playlist's guide sources (for choosing a guide manually). */
@Entity(
    tableName = "epg_channels",
    primaryKeys = ["playlistId", "epgId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpgChannelEntity(
    val playlistId: Long,
    val epgId: String,
    val displayName: String,
)

@Entity(
    tableName = "programmes",
    indices = [
        Index(value = ["epgChannelId", "start"]),
        // Programme search: ranges on `stop > now` and tests title / channel / generation from the
        // index before touching the row.
        Index(value = ["stop", "title", "epgChannelId", "generation"]),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** XMLTV channel id (as spelled in the guide). */
    val epgChannelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val category: String?,
    /** Refresh that wrote the row; older generations are swapped out only after a refresh succeeds. */
    val generation: Long,
)

/**
 * A programme generation still being written (or orphaned by a killed refresh). Its rows are hidden
 * from reads until the refresh commits and removes the marker.
 */
@Entity(tableName = "epg_pending")
data class EpgPendingEntity(@PrimaryKey val generation: Long)

@Entity(
    tableName = "epg_status",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpgStatusEntity(
    @PrimaryKey val playlistId: Long,
    /** Last successful guide refresh (kept as-is when a later refresh fails). */
    val updatedAt: Instant?,
    /** Channels of the playlist that have a guide mapping. */
    val matchedChannels: Int,
    val ok: Boolean,
    /**
     * Already redacted. When [ok]: null if every guide source loaded, else "(N of M guide sources
     * failed)" (the working sources were committed). When not [ok]: why the refresh failed.
     */
    val message: String?,
)

/**
 * A channel's identity across playlist refreshes: the row id changes when a key vanishes and
 * returns, the (playlistId, key) pair does not.
 */
data class ChannelRef(val playlistId: Long, val key: String)

val ChannelEntity.ref: ChannelRef get() = ChannelRef(playlistId, key)

/**
 * A favourite channel, kept by [ChannelRef] so it survives refreshes; while the channel is absent
 * from its playlist the joins simply hide it. [position] orders the list (new ones go last).
 */
@Entity(
    tableName = "favourites",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["position"])],
)
data class FavouriteEntity(
    val playlistId: Long,
    val channelKey: String,
    val position: Long,
    val addedAt: Instant,
)

/** When a channel was last watched; one row per channel, trimmed to the newest few. */
@Entity(
    tableName = "history",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["watchedAt"])],
)
data class HistoryEntity(
    val playlistId: Long,
    val channelKey: String,
    val watchedAt: Instant,
)

/**
 * A programme search result with the lowest-numbered visible channel that carries it. The channel
 * fields are display values (custom name, guide number, custom logo); [programme]'s times are as
 * stored, and the channel's guide time shift is [shiftMinutes] (shown time = stored + shift).
 */
data class ProgrammeHit(
    @Embedded val programme: ProgrammeEntity,
    val channelId: Long,
    val channelName: String,
    val channelNumber: Int,
    val channelLogo: String?,
    val shiftMinutes: Int,
)

/**
 * A guide group: one group of one playlist, with its visible channel count. [title] is the
 * original group title (the scope key), [label] the name shown (the custom name when renamed).
 */
data class GroupRow(
    val playlistId: Long,
    val title: String,
    val label: String,
    /**
     * `COALESCE(group_prefs.position, MIN(groupOrder))`: mixes the user's move index and the source
     * order, so it is not monotonic. Use the list order (already sorted) for display and moves.
     */
    @ColumnInfo(name = "order") val order: Int,
    val count: Int,
    val playlistName: String,
    val sortMode: GroupSort,
)

/** A group in the Settings editor, hidden ones included; [count] counts every channel. */
data class EditorGroupRow(
    val title: String,
    val label: String,
    val hidden: Boolean,
    val renamed: Boolean,
    val count: Int,
    /** Same mixed value as [GroupRow.order]: use the list order, not this, for display and moves. */
    @ColumnInfo(name = "order") val order: Int,
    val sortMode: GroupSort,
)

/**
 * A channel in the Settings editor. [hidden] is the channel's own edit (`channel_prefs.hidden`),
 * not [ChannelEntity.visible], which is also false for a hidden group or a turned-off playlist.
 */
data class EditorChannelRow(
    @Embedded val channel: ChannelEntity,
    val hidden: Boolean,
)

/** Projection: number of channels in a playlist. */
data class PlaylistChannelCount(val playlistId: Long, val count: Int)

/** Projection used to keep row ids (and the EPG match) stable across refreshes. */
data class ChannelKeyId(val id: Long, val key: String, val epgChannelId: String?)

/** Projection used by the EPG refresh to match channels and detect mapping changes. */
data class ChannelEpgRow(
    val id: Long,
    val tvgId: String?,
    val tvgName: String?,
    val name: String,
    val epgChannelId: String?,
)

class Converters {
    @TypeConverter fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
