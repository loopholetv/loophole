package io.github.loopholetv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, ProgrammeEntity::class, EpgStatusEntity::class,
        EpgPendingEntity::class, FavouriteEntity::class, HistoryEntity::class, ChannelPrefsEntity::class,
        GroupPrefsEntity::class, EpgSourceEntity::class, EpgChannelEntity::class,
        FilmEntity::class, EpisodeEntity::class, ShowEntity::class, VodProgressEntity::class,
        MyListEntity::class, ContinueDismissalEntity::class,
        ReminderEntity::class, SeriesRuleEntity::class, RecordingEntity::class,
        TmdbTitleEntity::class, TmdbTitleGenreEntity::class, TmdbGenreEntity::class, TmdbEpisodeEntity::class,
        VodMatchEntity::class, ProviderDetailsEntity::class, ShowPrefsEntity::class, LockStateEntity::class,
    ],
    version = 13,
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
    abstract fun setupDao(): SetupDao
    abstract fun vodWriteDao(): VodWriteDao
    abstract fun vodBrowseDao(): VodBrowseDao
    abstract fun vodProgressDao(): VodProgressDao
    abstract fun vodLibraryDao(): VodLibraryDao
    abstract fun recordDao(): RecordDao
    abstract fun tmdbDao(): TmdbDao
    abstract fun vodFilterDao(): VodFilterDao
    abstract fun xtreamVodDao(): XtreamVodDao
    abstract fun lockDao(): LockDao
}

/** Seeds the single `lock_state` row (locked) when the database file is first created. */
val LOCK_STATE_SEED: RoomDatabase.Callback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO `lock_state` (`id`, `unlocked`) VALUES (1, 0)")
    }
}
