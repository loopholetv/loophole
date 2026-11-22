package io.github.loopholetv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** What a series rule does for each matching showing. Stored by name (TEXT). */
enum class SeriesKind { RECORD, REMIND }

/** Where a recording is in its life. Stored by name (TEXT). */
enum class RecordingState { SCHEDULED, RECORDING, DONE, STOPPED, FAILED, SKIPPED, MISSED, CANCELLED }

/** Why a recording ended or did not run the way it was booked. Stored by name (TEXT). */
enum class RecordingReason {
    NONE, GAPS, STARTED_LATE, STORAGE_FULL, PROVIDER_BUSY, STORAGE_UNREACHABLE, CLASH, TV_OFF, USER, ERROR,
}

/**
 * A booked reminder. The programme fields are copied, so a guide refresh does not change a booking.
 * No foreign keys: rows are removed explicitly when their playlist goes.
 */
@Entity(
    tableName = "reminders",
    indices = [Index(value = ["playlistId", "channelKey", "start"], unique = true), Index("start")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val epgChannelId: String?,
    val title: String,
    val subTitle: String?,
    val episodeKey: String?,
    val start: Instant,
    val stop: Instant,
    val seriesId: Long? = null,
    val notifiedAt: Instant? = null,
)

/** A rule that books every future showing of [title] on a channel, as a recording or a reminder. */
@Entity(
    tableName = "series_rules",
    indices = [Index(value = ["kind", "playlistId", "channelKey", "title"], unique = true)],
)
data class SeriesRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SeriesKind,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val epgChannelId: String?,
    val title: String,
    /** Keep only this many finished episodes; null keeps them all. */
    val keepLast: Int? = null,
    val padBeforeMin: Int,
    val padAfterMin: Int,
    val createdAt: Instant,
)

/**
 * A booked or finished recording. The programme fields are copied at booking time. [sourcePlaylistId]
 * and [sourceChannelKey] are set when a clash is resolved by recording from another playlist;
 * [storageId] names the storage the file was written to.
 */
@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["playlistId", "channelKey", "start"], unique = true),
        Index("state"),
        Index("start"),
        Index("seriesId"),
    ],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val epgChannelId: String?,
    val title: String,
    val subTitle: String?,
    val episodeKey: String?,
    val description: String?,
    val start: Instant,
    val stop: Instant,
    val padBeforeMin: Int,
    val padAfterMin: Int,
    val seriesId: Long? = null,
    val sourcePlaylistId: Long? = null,
    val sourceChannelKey: String? = null,
    val state: RecordingState = RecordingState.SCHEDULED,
    val reason: RecordingReason = RecordingReason.NONE,
    val fileName: String? = null,
    val storageId: String? = null,
    val bytes: Long = 0,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val positionMs: Long = 0,
    val gaps: Int = 0,
    val createdAt: Instant,
    /** Set on a catch-up retry: the programme's real span, fetched from the channel's archive. */
    val archiveFrom: Instant? = null,
    val archiveUntil: Instant? = null,
)
