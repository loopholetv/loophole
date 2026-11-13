package io.github.loopholetv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** v1 → v2: adds the EPG tables (`programmes`, `epg_status`, `epg_pending`). SQL matches Room's schema 2 export. */
val MIGRATION_1_2: Migration = SqlMigration(
    1,
    2,
    """CREATE TABLE IF NOT EXISTS `programmes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
       `epgChannelId` TEXT NOT NULL, `start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, `title` TEXT NOT NULL,
       `subTitle` TEXT, `description` TEXT, `category` TEXT, `generation` INTEGER NOT NULL)""",
    "CREATE INDEX IF NOT EXISTS `index_programmes_epgChannelId_start` ON `programmes` (`epgChannelId`, `start`)",
    """CREATE TABLE IF NOT EXISTS `epg_status` (`playlistId` INTEGER NOT NULL, `updatedAt` INTEGER,
       `matchedChannels` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `message` TEXT, PRIMARY KEY(`playlistId`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE TABLE IF NOT EXISTS `epg_pending` (`generation` INTEGER NOT NULL, PRIMARY KEY(`generation`))",
)

/**
 * v2 → v3: adds `favourites` and `history` (keyed by playlistId + channel key) and the
 * `channels.epgChannelId` and `programmes(stop, title, epgChannelId, generation)` indices used by
 * programme search. SQL matches Room's schema 3 export.
 */
val MIGRATION_2_3: Migration = SqlMigration(
    2,
    3,
    """CREATE TABLE IF NOT EXISTS `favourites` (`playlistId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL,
       `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `channelKey`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_favourites_position` ON `favourites` (`position`)",
    """CREATE TABLE IF NOT EXISTS `history` (`playlistId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL,
       `watchedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `channelKey`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_history_watchedAt` ON `history` (`watchedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_channels_epgChannelId` ON `channels` (`epgChannelId`)",
    """CREATE INDEX IF NOT EXISTS `index_programmes_stop_title_epgChannelId_generation`
       ON `programmes` (`stop`, `title`, `epgChannelId`, `generation`)""",
)

/**
 * v3 → v4: playlist options and MAC portal columns; derived channel columns (seeded from the
 * existing number and guide id, so nothing moves); `channel_prefs`, `group_prefs`, `epg_sources`
 * and `epg_channels`. The old extra EPG link becomes the playlist's first EPG source. SQL matches
 * Room's schema 4 export.
 */
val MIGRATION_3_4: Migration = SqlMigration(
    3,
    4,
    "ALTER TABLE `playlists` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE `playlists` ADD COLUMN `userAgent` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `updateHours` INTEGER",
    "ALTER TABLE `playlists` ADD COLUMN `updateOnStart` INTEGER",
    "ALTER TABLE `playlists` ADD COLUMN `useSourceEpg` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE `playlists` ADD COLUMN `rekeyPending` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `playlists` ADD COLUMN `mac` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalEndpoint` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalToken` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalLogin` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalPassword` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalSerial` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalDeviceId` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalDeviceId2` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `portalSignature` TEXT",
    "ALTER TABLE `channels` ADD COLUMN `visible` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE `channels` ADD COLUMN `guideNumber` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `channels` ADD COLUMN `groupRank` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `channels` ADD COLUMN `customName` TEXT",
    "ALTER TABLE `channels` ADD COLUMN `customLogo` TEXT",
    "ALTER TABLE `channels` ADD COLUMN `guideEpgId` TEXT",
    "ALTER TABLE `channels` ADD COLUMN `epgShiftMinutes` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `channels` ADD COLUMN `needsLink` INTEGER NOT NULL DEFAULT 0",
    "UPDATE `channels` SET `guideNumber` = `number`, `groupRank` = `number`, `guideEpgId` = `epgChannelId`",
    "DROP INDEX IF EXISTS `index_channels_number`",
    "CREATE INDEX IF NOT EXISTS `index_channels_playlistId_number` ON `channels` (`playlistId`, `number`)",
    "DROP INDEX IF EXISTS `index_channels_epgChannelId`",
    """CREATE INDEX IF NOT EXISTS `index_channels_playlistId_groupTitle_groupRank`
       ON `channels` (`playlistId`, `groupTitle`, `groupRank`)""",
    "CREATE INDEX IF NOT EXISTS `index_channels_visible_guideNumber` ON `channels` (`visible`, `guideNumber`)",
    "CREATE INDEX IF NOT EXISTS `index_channels_guideEpgId` ON `channels` (`guideEpgId`)",
    """CREATE TABLE IF NOT EXISTS `channel_prefs` (`playlistId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL,
       `hidden` INTEGER NOT NULL, `customName` TEXT, `customLogo` TEXT, `epgOverride` TEXT,
       `epgShiftMinutes` INTEGER NOT NULL, `position` INTEGER, PRIMARY KEY(`playlistId`, `channelKey`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    """CREATE TABLE IF NOT EXISTS `group_prefs` (`playlistId` INTEGER NOT NULL, `groupTitle` TEXT NOT NULL,
       `hidden` INTEGER NOT NULL, `customName` TEXT, `position` INTEGER, `sortMode` TEXT NOT NULL,
       PRIMARY KEY(`playlistId`, `groupTitle`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    """CREATE TABLE IF NOT EXISTS `epg_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
       `playlistId` INTEGER NOT NULL, `url` TEXT NOT NULL, `position` INTEGER NOT NULL,
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_epg_sources_playlistId_position` ON `epg_sources` (`playlistId`, `position`)",
    """CREATE TABLE IF NOT EXISTS `epg_channels` (`playlistId` INTEGER NOT NULL, `epgId` TEXT NOT NULL,
       `displayName` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `epgId`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    """INSERT INTO `epg_sources` (`playlistId`, `url`, `position`)
       SELECT `id`, TRIM(`manualEpgUrl`), 0 FROM `playlists`
       WHERE `manualEpgUrl` IS NOT NULL AND TRIM(`manualEpgUrl`) <> ''""",
    "UPDATE `playlists` SET `manualEpgUrl` = NULL",
)

/**
 * v4 → v5: per-channel playback overrides on `channel_prefs` (video and audio decoder, external
 * player, aspect), all nullable so existing rows keep following the settings. SQL matches Room's
 * schema 5 export.
 */
val MIGRATION_4_5: Migration = SqlMigration(
    4,
    5,
    "ALTER TABLE `channel_prefs` ADD COLUMN `videoDecoder` TEXT",
    "ALTER TABLE `channel_prefs` ADD COLUMN `audioDecoder` TEXT",
    "ALTER TABLE `channel_prefs` ADD COLUMN `externalPlayer` TEXT",
    "ALTER TABLE `channel_prefs` ADD COLUMN `aspectMode` TEXT",
)

/**
 * v5 → v6: the provider's archive time zone and stream format on `playlists`, and a per-channel
 * archive time correction. SQL matches Room's schema 6 export.
 */
val MIGRATION_5_6: Migration = SqlMigration(
    5,
    6,
    "ALTER TABLE `playlists` ADD COLUMN `xtreamTimeZone` TEXT",
    "ALTER TABLE `playlists` ADD COLUMN `xtreamArchiveExt` TEXT",
    "ALTER TABLE `channels` ADD COLUMN `catchupCorrectionMinutes` INTEGER NOT NULL DEFAULT 0",
)

/**
 * v6 → v7: on-demand tables `vod_films`, `vod_episodes`, `vod_shows` and `vod_progress`. Progress
 * has no foreign key so it survives a refresh that drops the item. SQL matches Room's schema 7 export.
 */
val MIGRATION_6_7: Migration = SqlMigration(
    6,
    7,
    """CREATE TABLE IF NOT EXISTS `vod_films` (`playlistId` INTEGER NOT NULL, `key` TEXT NOT NULL,
       `name` TEXT NOT NULL, `category` TEXT NOT NULL, `logo` TEXT, `streamUrl` TEXT NOT NULL, `userAgent` TEXT,
       `referrer` TEXT, `tvgId` TEXT, `position` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL,
       PRIMARY KEY(`playlistId`, `key`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_vod_films_playlistId_category_position` ON `vod_films` (`playlistId`, `category`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_films_category_playlistId_position` ON `vod_films` (`category`, `playlistId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_films_firstSeenAt` ON `vod_films` (`firstSeenAt`)",
    """CREATE TABLE IF NOT EXISTS `vod_episodes` (`playlistId` INTEGER NOT NULL, `key` TEXT NOT NULL,
       `showKey` TEXT NOT NULL, `groupTitle` TEXT NOT NULL, `name` TEXT NOT NULL, `season` INTEGER, `episode` INTEGER,
       `logo` TEXT, `streamUrl` TEXT NOT NULL, `userAgent` TEXT, `referrer` TEXT, `tvgId` TEXT,
       `position` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `key`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_vod_episodes_playlistId_showKey` ON `vod_episodes` (`playlistId`, `showKey`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_episodes_firstSeenAt` ON `vod_episodes` (`firstSeenAt`)",
    """CREATE TABLE IF NOT EXISTS `vod_shows` (`playlistId` INTEGER NOT NULL, `key` TEXT NOT NULL,
       `name` TEXT NOT NULL, `groupTitle` TEXT NOT NULL, `category` TEXT, `logo` TEXT, `firstSeenAt` INTEGER NOT NULL,
       `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `key`),
       FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX IF NOT EXISTS `index_vod_shows_category_name` ON `vod_shows` (`category`, `name`)",
    "CREATE INDEX IF NOT EXISTS `index_vod_shows_name` ON `vod_shows` (`name`)",
    """CREATE TABLE IF NOT EXISTS `vod_progress` (`kind` TEXT NOT NULL, `playlistId` INTEGER NOT NULL,
       `key` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER, `watched` INTEGER NOT NULL,
       `lastWatchedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `playlistId`, `key`))""",
    "CREATE INDEX IF NOT EXISTS `index_vod_progress_lastWatchedAt` ON `vod_progress` (`lastWatchedAt`)",
)

val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

/** Runs [statements] in order, via either Room API (support database or driver connection). */
private class SqlMigration(from: Int, to: Int, private vararg val statements: String) : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
    override fun migrate(connection: SQLiteConnection) = statements.forEach { connection.execSQL(it) }
}
