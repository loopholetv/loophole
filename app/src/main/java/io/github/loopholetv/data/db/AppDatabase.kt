package io.github.loopholetv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, ProgrammeEntity::class, EpgStatusEntity::class,
        EpgPendingEntity::class, FavouriteEntity::class, HistoryEntity::class, ChannelPrefsEntity::class,
        GroupPrefsEntity::class, EpgSourceEntity::class, EpgChannelEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun epgStatusDao(): EpgStatusDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchDao(): SearchDao
    abstract fun channelPrefsDao(): ChannelPrefsDao
    abstract fun groupPrefsDao(): GroupPrefsDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun epgChannelDao(): EpgChannelDao
    abstract fun overlayDao(): OverlayDao
    abstract fun maintenanceDao(): MaintenanceDao
}
