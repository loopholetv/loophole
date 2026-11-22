package io.github.loopholetv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface RecordDao {
    // --- reminders ---

    @Upsert suspend fun upsertReminder(r: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id") suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM reminders WHERE stop > :from ORDER BY start")
    fun observeReminders(from: Instant): Flow<List<ReminderEntity>>

    /** Reminders starting in (`after`, `until`]. */
    @Query("SELECT * FROM reminders WHERE start > :after AND start <= :until ORDER BY start")
    suspend fun remindersDue(until: Instant, after: Instant): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE playlistId = :playlistId AND channelKey = :channelKey AND start = :start")
    suspend fun reminderFor(playlistId: Long, channelKey: String, start: Instant): ReminderEntity?

    @Query("UPDATE reminders SET notifiedAt = :at WHERE id = :id") suspend fun markNotified(id: Long, at: Instant)

    // --- series rules ---

    @Upsert suspend fun upsertSeries(s: SeriesRuleEntity): Long

    /** Removes the rule with its not-yet-started or skipped recordings and its reminders not yet shown. Finished recordings stay. */
    @Transaction
    suspend fun deleteSeries(id: Long) {
        deleteScheduledOfSeries(id)
        deletePendingRemindersOfSeries(id)
        deleteSeriesRow(id)
    }

    @Query("DELETE FROM recordings WHERE seriesId = :id AND state IN ('SCHEDULED', 'SKIPPED')")
    suspend fun deleteScheduledOfSeries(id: Long)

    @Query("DELETE FROM reminders WHERE seriesId = :id AND notifiedAt IS NULL")
    suspend fun deletePendingRemindersOfSeries(id: Long)

    @Query("DELETE FROM series_rules WHERE id = :id") suspend fun deleteSeriesRow(id: Long)

    @Query("SELECT * FROM series_rules WHERE kind = :kind AND playlistId = :playlistId AND channelKey = :channelKey AND LOWER(TRIM(title)) = LOWER(:title)")
    suspend fun seriesFor(kind: SeriesKind, playlistId: Long, channelKey: String, title: String): SeriesRuleEntity?

    @Query("SELECT * FROM series_rules WHERE id = :id") suspend fun series(id: Long): SeriesRuleEntity?

    @Query("SELECT * FROM series_rules ORDER BY title") suspend fun allSeries(): List<SeriesRuleEntity>

    @Query("SELECT * FROM series_rules ORDER BY title") fun observeSeries(): Flow<List<SeriesRuleEntity>>

    // --- recordings ---

    /** Returns -1 when a recording of that showing already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecording(r: RecordingEntity): Long

    @Update suspend fun updateRecording(r: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun recording(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id") fun observeRecording(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings ORDER BY start DESC") fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE state IN (:s) ORDER BY start")
    suspend fun inState(vararg s: RecordingState): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE playlistId = :playlistId AND channelKey = :channelKey AND start = :start")
    suspend fun recordingFor(playlistId: Long, channelKey: String, start: Instant): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE seriesId = :seriesId ORDER BY start")
    suspend fun seriesEpisodes(seriesId: Long): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id") suspend fun deleteRecording(id: Long)

    @Query("UPDATE recordings SET positionMs = :ms WHERE id = :id") suspend fun setPosition(id: Long, ms: Long)

    // --- clean-up ---

    /** Drops bookings for [ids]: reminders, series rules and scheduled or skipped recordings, and points recordings sourced from them back at their own channel. Finished recordings stay. */
    @Transaction
    suspend fun deletePendingForPlaylists(ids: List<Long>) {
        deleteRemindersOf(ids)
        deleteSeriesOf(ids)
        deleteScheduledOf(ids)
        clearSourceOf(ids)
    }

    @Query("DELETE FROM reminders WHERE playlistId IN (:ids)") suspend fun deleteRemindersOf(ids: List<Long>)

    @Query("DELETE FROM series_rules WHERE playlistId IN (:ids)") suspend fun deleteSeriesOf(ids: List<Long>)

    @Query("DELETE FROM recordings WHERE playlistId IN (:ids) AND state IN ('SCHEDULED', 'SKIPPED')")
    suspend fun deleteScheduledOf(ids: List<Long>)

    /** Recordings that were to be made from a playlist that is going are made from their own channel again. */
    @Query("UPDATE recordings SET sourcePlaylistId = NULL, sourceChannelKey = NULL WHERE sourcePlaylistId IN (:ids)")
    suspend fun clearSourceOf(ids: List<Long>)
}
