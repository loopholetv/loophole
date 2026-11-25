package io.github.loopholetv.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.tmdb.TmdbKind
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

private const val DB = "migration-test.db"

/** Schemas come from app/schemas, added to the debug assets in build.gradle.kts. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2KeepsDataAndAddsEpgTables() = runTest {
        helper.createDatabase(DB, 1).use { v1 ->
            v1.execSQL(
                """INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""",
            )
            v1.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder,
                   sortOrder, tvgId) VALUES (1, 7, 'k', 1, 'Ch', 'http://s.example.test/1', 'G', 0, 0, 'ch.id')""",
            )
        }

        // Validates the migrated schema against the exported schema 2.json.
        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals("Old", db.playlistDao().byId(7)!!.name)
            assertEquals("ch.id", db.channelDao().byId(1)!!.tvgId)
            db.epgStatusDao().upsert(EpgStatusEntity(7, Instant.EPOCH, 1, true, null))
            assertEquals(1, db.epgStatusDao().observe(7).first()!!.matchedChannels)
        } finally {
            db.close()
        }
    }

    @Test fun migrate2To3KeepsDataAndAddsTables() = runTest {
        helper.createDatabase(DB, 2).use { v2 ->
            v2.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v2.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder,
                   sortOrder, epgChannelId) VALUES (1, 7, 'k', 1, 'Ch', 'http://s.example.test/1', 'G', 0, 0, 'e')""",
            )
            v2.execSQL(
                """INSERT INTO programmes (epgChannelId, start, stop, title, generation)
                   VALUES ('e', 0, 9999999999999, 'Show', 1)""",
            )
            v2.execSQL("INSERT INTO epg_status (playlistId, updatedAt, matchedChannels, ok) VALUES (7, 0, 1, 1)")
        }

        // Validates the migrated schema against the exported schema 3.json.
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals("Old", db.playlistDao().byId(7)!!.name)
            val ch = db.channelDao().byId(1)!!
            assertEquals("e", ch.epgChannelId)
            assertEquals("Show", db.programmeDao().nowAndNext("e", Instant.EPOCH).single().title)
            assertEquals(1, db.epgStatusDao().get(7)!!.matchedChannels)

            db.favouriteDao().add(ch.ref, Instant.EPOCH)
            assertEquals(listOf(ChannelRef(7, "k")), db.favouriteDao().refs().first())
            db.historyDao().record(ch.ref, Instant.EPOCH, keep = 30)
            assertEquals(ch, db.historyDao().latest())
            assertEquals("Show", db.searchDao().programmes("%sho%", Instant.EPOCH, 10).single().programme.title)
        } finally {
            db.close()
        }
    }

    /**
     * A v0.2.0 install: one Xtream playlist with 300 channels in three groups, an extra EPG link
     * (with stray spaces), favourites, history, guide data and an EPG status. A second, M3U
     * playlist has a blank extra link, which must not become a source.
     */
    @Test fun migrate3To4KeepsEverythingAndAddsTables() = runTest {
        helper.createDatabase(DB, 3).use { v3 ->
            v3.execSQL(
                """INSERT INTO playlists (id, name, type, url, xtreamServer, xtreamUser, xtreamPass, epgUrlsFromSource,
                   manualEpgUrl, lastRefreshedAt, lastError) VALUES (7, 'My TV', 'XTREAM', NULL,
                   'http://example.test:8080', 'user1', 'pass1', '', ' http://example.test/epg.xml ', 1000, NULL)""",
            )
            v3.execSQL(
                """INSERT INTO playlists (id, name, type, url, epgUrlsFromSource, manualEpgUrl)
                   VALUES (8, 'Spare', 'M3U', 'http://example.test/list.m3u', 'http://example.test/a.xml', '   ')""",
            )
            for (n in 1..300) {
                val group = when {
                    n <= 100 -> "News"
                    n <= 200 -> "Sports"
                    else -> "Movies"
                }
                val epg = if (n % 3 == 0) "NULL" else "'e$n'"
                v3.execSQL(
                    """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, logo, groupTitle, groupOrder,
                       sortOrder, tvgId, epgChannelId) VALUES ($n, 7, '${1000 + n}', $n, 'Channel $n',
                       'http://example.test:8080/live/user1/pass1/${1000 + n}.ts', 'http://example.test/$n.png',
                       '$group', ${(n - 1) / 100}, ${n - 1}, 'tvg$n', $epg)""",
                )
            }
            v3.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder, sortOrder)
                   VALUES (301, 8, 'http://example.test/s.ts', 1, 'Spare 1', 'http://example.test/s.ts', 'G', 0, 0)""",
            )
            v3.execSQL("INSERT INTO favourites (playlistId, channelKey, position, addedAt) VALUES (7, '1005', 0, 10)")
            v3.execSQL("INSERT INTO favourites (playlistId, channelKey, position, addedAt) VALUES (7, '1009', 1, 11)")
            v3.execSQL("INSERT INTO history (playlistId, channelKey, watchedAt) VALUES (7, '1005', 20)")
            v3.execSQL("INSERT INTO history (playlistId, channelKey, watchedAt) VALUES (7, '1150', 19)")
            v3.execSQL(
                """INSERT INTO programmes (epgChannelId, start, stop, title, generation)
                   VALUES ('e5', 0, 9999999999999, 'Show', 1)""",
            )
            v3.execSQL("INSERT INTO epg_status (playlistId, updatedAt, matchedChannels, ok) VALUES (7, 0, 200, 1)")
        }

        // Validates the migrated schema against the exported schema 4.json.
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).use { v4 ->
            v4.query(
                """SELECT COUNT(*) FROM channels WHERE guideNumber <> number OR groupRank <> number
                   OR guideEpgId IS NOT epgChannelId OR visible <> 1 OR needsLink <> 0 OR epgShiftMinutes <> 0
                   OR customName IS NOT NULL OR customLogo IS NOT NULL""",
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            v4.query("SELECT playlistId, url, position FROM epg_sources").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(7L, c.getLong(0))
                assertEquals("http://example.test/epg.xml", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            // The import's fallback numbering filters by (playlistId, number).
            v4.query(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_channels_playlistId_number'",
            ).use { c ->
                assertTrue("index_channels_playlistId_number missing", c.moveToFirst())
                assertTrue(c.getString(0).contains("(`playlistId`, `number`)"))
            }
            v4.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'index_channels_number'").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            v4.query("SELECT COUNT(*) FROM playlists WHERE manualEpgUrl IS NOT NULL").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val p = db.playlistDao().byId(7)!!
            assertEquals("My TV", p.name)
            assertEquals(PlaylistType.XTREAM, p.type)
            assertEquals(listOf("http://example.test:8080", "user1", "pass1"), listOf(p.xtreamServer, p.xtreamUser, p.xtreamPass))
            assertEquals(Instant.ofEpochMilli(1000), p.lastRefreshedAt)
            assertTrue(p.enabled)
            assertTrue(p.useSourceEpg)
            assertFalse(p.rekeyPending)
            assertNull(p.manualEpgUrl)
            assertNull(p.updateHours)
            assertNull(p.updateOnStart)
            assertNull(p.userAgent)
            assertNull(p.mac)
            assertNull(p.portalToken)
            assertEquals("http://example.test/a.xml", db.playlistDao().byId(8)!!.epgUrlsFromSource)
            assertEquals(listOf(EpgSourceEntity(1, 7, "http://example.test/epg.xml", 0)), db.epgSourceDao().forPlaylist(7))
            assertTrue(db.epgSourceDao().forPlaylist(8).isEmpty())

            val c5 = db.channelDao().byId(5)!!
            assertEquals(listOf(5, 5, 5), listOf(c5.number, c5.guideNumber, c5.groupRank))
            assertEquals("e5", c5.guideEpgId)
            assertEquals("Channel 5", c5.displayName)
            assertEquals("http://example.test/5.png", c5.displayLogo)
            val c9 = db.channelDao().byId(9)!!
            assertEquals(listOf(9, 9, 9), listOf(c9.number, c9.guideNumber, c9.groupRank))
            assertNull(c9.guideEpgId)
            assertTrue(c9.visible)
            assertFalse(c9.needsLink)
            assertEquals(300, db.channelDao().channelsOf(7).size)
            // Groups are per playlist: playlist 7's groups, then playlist 8's.
            assertEquals(
                listOf(7L to "News", 7L to "Sports", 7L to "Movies", 8L to "G"),
                db.channelDao().groups().first().map { it.playlistId to it.title },
            )
            assertEquals(200, db.channelDao().countWithEpg(7))

            assertEquals(listOf(ChannelRef(7, "1005"), ChannelRef(7, "1009")), db.favouriteDao().refs().first())
            assertEquals(c5, db.historyDao().latest())
            assertEquals(listOf("1005", "1150"), db.historyDao().recent(10).first().map { it.key })
            assertEquals("Show", db.programmeDao().nowAndNext("e5", Instant.EPOCH).single().title)
            assertEquals(200, db.epgStatusDao().get(7)!!.matchedChannels)
            assertEquals("Show", db.searchDao().programmes("%sho%", Instant.EPOCH, 10).single().programme.title)

            // The new tables accept rows.
            db.channelPrefsDao().upsert(ChannelPrefsEntity(7, "1005", customName = "Five"))
            db.groupPrefsDao().upsert(GroupPrefsEntity(7, "News", sortMode = GroupSort.CUSTOM))
            db.epgSourceDao().add(7, "http://example.test/more.xml")
            db.epgChannelDao().upsertAll(listOf(EpgChannelEntity(7, "e5", "Five")))
            assertEquals("Five", db.channelPrefsDao().get(7, "1005")!!.customName)
            assertEquals(GroupSort.CUSTOM, db.groupPrefsDao().get(7, "News")!!.sortMode)
            assertEquals(listOf(0, 1), db.epgSourceDao().forPlaylist(7).map { it.position })
            assertEquals(1, db.epgChannelDao().count(7))
            // The manual-mapping rule applies to migrated rows too.
            db.channelPrefsDao().upsert(ChannelPrefsEntity(7, "1005", epgOverride = "x.uk"))
            db.channelDao().setEpgChannelId(5, "e5")
            assertEquals("x.uk", db.channelDao().byId(5)!!.guideEpgId)
        } finally {
            db.close()
        }
    }

    /**
     * A v0.3.2 install: playlist with options set, a channel with derived edits, a channel_prefs
     * row (hidden, renamed, shifted), a group_prefs row, a favourite, history and an EPG source.
     */
    @Test fun migrate4To5AddsPlaybackColumnsAndKeepsPrefs() = runTest {
        helper.createDatabase(DB, 4).use { v4 ->
            v4.execSQL(
                """INSERT INTO playlists (id, name, type, url, epgUrlsFromSource, enabled, userAgent, updateHours,
                   updateOnStart, useSourceEpg, rekeyPending) VALUES (7, 'My TV', 'M3U', 'http://example.test/list.m3u',
                   '', 1, 'UA/1', 6, 0, 1, 0)""",
            )
            v4.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder, sortOrder,
                   epgChannelId, visible, guideNumber, groupRank, customName, guideEpgId, epgShiftMinutes, needsLink)
                   VALUES (1, 7, 'b', 3, 'B', 'http://example.test/b.ts', 'News', 0, 0, 'b.uk', 0, 3, 3, 'Bee',
                   'b.uk', 60, 0)""",
            )
            v4.execSQL(
                """INSERT INTO channel_prefs (playlistId, channelKey, hidden, customName, customLogo, epgOverride,
                   epgShiftMinutes, position) VALUES (7, 'b', 1, 'Bee', NULL, NULL, 60, 2)""",
            )
            v4.execSQL(
                """INSERT INTO group_prefs (playlistId, groupTitle, hidden, customName, position, sortMode)
                   VALUES (7, 'News', 0, 'Headlines', 1, 'CUSTOM')""",
            )
            v4.execSQL("INSERT INTO favourites (playlistId, channelKey, position, addedAt) VALUES (7, 'b', 0, 10)")
            v4.execSQL("INSERT INTO history (playlistId, channelKey, watchedAt) VALUES (7, 'b', 20)")
            v4.execSQL("INSERT INTO epg_sources (id, playlistId, url, position) VALUES (1, 7, 'http://example.test/epg.xml', 0)")
        }

        // Validates the migrated schema against the exported schema 5.json.
        helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).use { v5 ->
            v5.query(
                """SELECT hidden, customName, epgShiftMinutes, position, videoDecoder, audioDecoder, externalPlayer,
                   aspectMode FROM channel_prefs WHERE playlistId = 7 AND channelKey = 'b'""",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("Bee", c.getString(1))
                assertEquals(60, c.getInt(2))
                assertEquals(2, c.getInt(3))
                for (i in 4..7) assertTrue("column $i not null", c.isNull(i))
            }
        }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals(
                ChannelPrefsEntity(7, "b", hidden = true, customName = "Bee", epgShiftMinutes = 60, position = 2),
                db.channelPrefsDao().get(7, "b"),
            )
            val p = db.playlistDao().byId(7)!!
            assertEquals(listOf<Any?>("My TV", "UA/1", 6, false), listOf(p.name, p.userAgent, p.updateHours, p.updateOnStart))
            val ch = db.channelDao().byId(1)!!
            assertEquals("Bee", ch.displayName)
            assertFalse(ch.visible)
            assertEquals(60, ch.epgShiftMinutes)
            assertEquals("b.uk", ch.guideEpgId)
            assertEquals(GroupPrefsEntity(7, "News", false, "Headlines", 1, GroupSort.CUSTOM), db.groupPrefsDao().get(7, "News"))
            assertEquals(listOf(ChannelRef(7, "b")), db.favouriteDao().refs().first())
            db.openHelper.readableDatabase.query("SELECT channelKey, watchedAt FROM history").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("b", c.getString(0))
                assertEquals(20L, c.getLong(1))
            }
            assertEquals(listOf("http://example.test/epg.xml"), db.epgSourceDao().forPlaylist(7).map { it.url })

            // The new columns hold enum names and round-trip.
            db.channelPrefsDao().upsert(
                db.channelPrefsDao().get(7, "b")!!.copy(
                    videoDecoder = DecoderChoice.SOFTWARE,
                    audioDecoder = DecoderChoice.HARDWARE,
                    externalPlayer = "ask",
                    aspectMode = AspectMode.RATIO_16_9,
                ),
            )
            val row = db.channelPrefsDao().get(7, "b")!!
            assertEquals(DecoderChoice.SOFTWARE, row.videoDecoder)
            assertEquals(DecoderChoice.HARDWARE, row.audioDecoder)
            assertEquals("ask", row.externalPlayer)
            assertEquals(AspectMode.RATIO_16_9, row.aspectMode)
            db.openHelper.readableDatabase.query("SELECT videoDecoder, aspectMode FROM channel_prefs").use { c ->
                c.moveToFirst()
                assertEquals("SOFTWARE", c.getString(0))
                assertEquals("RATIO_16_9", c.getString(1))
            }
        } finally {
            db.close()
        }
    }

    /** A v0.4.x install: an Xtream playlist with a catch-up channel, a hidden/zoomed channel pref, favourite, history and EPG source. */
    @Test fun migrate5To6AddsArchiveColumnsAndKeepsData() = runTest {
        helper.createDatabase(DB, 5).use { v5 ->
            v5.execSQL(
                """INSERT INTO playlists (id, name, type, url, xtreamServer, xtreamUser, xtreamPass, epgUrlsFromSource,
                   manualEpgUrl, lastRefreshedAt, lastError) VALUES (7, 'My TV', 'XTREAM', NULL,
                   'http://example.test:8080', 'user1', 'pass1', '', NULL, 1000, NULL)""",
            )
            v5.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder, sortOrder,
                   catchupType, catchupDays) VALUES (1, 7, '101', 1, 'One', 'http://example.test:8080/live/user1/pass1/101.ts',
                   'News', 0, 0, 'xc', 3)""",
            )
            v5.execSQL(
                """INSERT INTO channel_prefs (playlistId, channelKey, hidden, epgShiftMinutes, aspectMode)
                   VALUES (7, '101', 1, 0, 'ZOOM')""",
            )
            v5.execSQL("INSERT INTO favourites (playlistId, channelKey, position, addedAt) VALUES (7, '101', 0, 10)")
            v5.execSQL("INSERT INTO history (playlistId, channelKey, watchedAt) VALUES (7, '101', 20)")
            v5.execSQL("INSERT INTO epg_sources (id, playlistId, url, position) VALUES (1, 7, 'http://example.test/epg.xml', 0)")
        }

        // Validates the migrated schema against the exported schema 6.json.
        helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val p = db.playlistDao().byId(7)!!
            assertNull(p.xtreamTimeZone)
            assertNull(p.xtreamArchiveExt)
            assertEquals(listOf("My TV", "user1", "pass1"), listOf(p.name, p.xtreamUser, p.xtreamPass))
            val ch = db.channelDao().byId(1)!!
            assertEquals(0, ch.catchupCorrectionMinutes)
            assertEquals("xc", ch.catchupType)
            assertEquals(3, ch.catchupDays)
            val prefs = db.channelPrefsDao().get(7, "101")!!
            assertTrue(prefs.hidden)
            assertEquals(AspectMode.ZOOM, prefs.aspectMode)
            assertEquals(listOf(ChannelRef(7, "101")), db.favouriteDao().refs().first())
            assertEquals(listOf("http://example.test/epg.xml"), db.epgSourceDao().forPlaylist(7).map { it.url })
            db.openHelper.readableDatabase.query("SELECT channelKey, watchedAt FROM history").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("101", c.getString(0))
                assertEquals(20L, c.getLong(1))
            }
        } finally {
            db.close()
        }
    }

    @Test fun migrate6To7AddsVodTables() = runTest {
        helper.createDatabase(DB, 6).use { v6 ->
            v6.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v6.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder,
                   sortOrder) VALUES (1, 7, 'k', 4, 'Ch', 'http://s.example.test/1', 'G', 0, 0)""",
            )
        }

        // Validates the migrated schema against the exported schema 7.json.
        helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals("Ch", db.channelDao().byId(1)!!.name)
            assertTrue(db.vodWriteDao().filmKeys(7).isEmpty())
            assertTrue(db.vodWriteDao().episodeKeys(7).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun migrate7To8AddsLibraryTablesAndKeepsProgress() = runTest {
        helper.createDatabase(DB, 7).use { v7 ->
            v7.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v7.execSQL(
                """INSERT INTO vod_progress (kind, playlistId, `key`, positionMs, durationMs, watched, lastWatchedAt)
                   VALUES ('MOVIE', 7, 'k', 5000, 100000, 0, 42)""",
            )
        }

        // Validates the migrated schema against the exported schema 8.json.
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val row = db.vodProgressDao().get(VodKind.MOVIE, 7, "k")!!
            assertEquals(5000L, row.positionMs)
            assertEquals(Instant.ofEpochMilli(42), row.lastWatchedAt)
            assertTrue(db.vodLibraryDao().observeListKeys(TitleKind.FILM).first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun migrate8To9AddsRecordTablesAndColumns() = runTest {
        helper.createDatabase(DB, 8).use { v8 ->
            v8.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v8.execSQL(
                """INSERT INTO programmes (id, epgChannelId, start, stop, title, generation)
                   VALUES (1, 'e', 1000, 2000, 'Show', 1)""",
            )
        }

        // Validates the migrated schema against the exported schema 9.json.
        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { v9 ->
            v9.query("SELECT episodeKey FROM programmes WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
        }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertNull(db.playlistDao().byId(7)!!.connectionsAllowed)
            assertNull(db.playlistDao().byId(7)!!.accountMaxConnections)
            assertTrue(db.recordDao().observeRecordings().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun migrate9To10AddsPlaylistColumns() = runTest {
        helper.createDatabase(DB, 9).use { v9 ->
            v9.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
        }

        // Validates the migrated schema against the exported schema 10.json.
        helper.runMigrationsAndValidate(DB, 10, true, MIGRATION_9_10).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val dao = db.playlistDao()
            val old = dao.byId(7)!!
            assertNull(old.xtreamFormat)
            assertNull(old.accountStatus)
            assertNull(old.accountExpiresAt)
            dao.setXtreamFormat(7, "hls")
            dao.setAccountInfo(7, "Active", Instant.ofEpochSecond(1_900_000_000))
            val set = dao.byId(7)!!
            assertEquals("hls", set.xtreamFormat)
            assertEquals("Active", set.accountStatus)
            assertEquals(Instant.ofEpochSecond(1_900_000_000), set.accountExpiresAt)
            dao.setXtreamFormat(7, null)
            dao.setAccountInfo(7, null, null)
            assertNull(dao.byId(7)!!.xtreamFormat)
            assertNull(dao.byId(7)!!.accountExpiresAt)
        } finally {
            db.close()
        }
    }

    @Test fun migrate10To11AddsTmdbTables() = runTest {
        helper.createDatabase(DB, 10).use { v10 ->
            v10.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
        }

        // Validates the migrated schema against the exported schema 11.json.
        helper.runMigrationsAndValidate(DB, 11, true, MIGRATION_10_11).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals("Old", db.playlistDao().byId(7)!!.name)
            val dao = db.tmdbDao()
            val m = VodMatchEntity(
                7, TitleKind.FILM, "k", "fp", MatchState.MATCHED, TmdbKind.MOVIE, 1, null, "Name", null,
                Instant.ofEpochMilli(5), Instant.ofEpochMilli(5),
            )
            dao.upsertMatch(m)
            assertEquals(m, dao.match(7, TitleKind.FILM, "k"))
        } finally {
            db.close()
        }
    }

    @Test fun migrate11To12AddsXtreamColumns() = runTest {
        helper.createDatabase(DB, 11).use { v11 ->
            v11.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v11.execSQL(
                """INSERT INTO vod_films (playlistId, `key`, name, category, streamUrl, position, firstSeenAt)
                   VALUES (7, 'f', 'Film', 'C', 'http://s.test/f.mp4', 0, 5)""",
            )
            v11.execSQL(
                """INSERT INTO vod_episodes (playlistId, `key`, showKey, groupTitle, name, streamUrl, position, firstSeenAt)
                   VALUES (7, 'e', 'S', 'G', 'Ep', 'http://s.test/e.mp4', 0, 5)""",
            )
            v11.execSQL(
                """INSERT INTO vod_shows (playlistId, `key`, name, groupTitle, firstSeenAt, position)
                   VALUES (7, 'S', 'Show', 'G', 5, 0)""",
            )
        }

        // Validates the migrated schema against the exported schema 12.json.
        helper.runMigrationsAndValidate(DB, 12, true, MIGRATION_11_12).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val playlist = db.playlistDao().byId(7)!!
            assertTrue(playlist.xtreamVod)
            assertNull(playlist.vodError)
            db.playlistDao().setXtreamVod(7, false)
            db.playlistDao().setVodError(7, "Films and series couldn't be updated")
            assertFalse(db.playlistDao().byId(7)!!.xtreamVod)
            assertEquals("Films and series couldn't be updated", db.playlistDao().byId(7)!!.vodError)
            db.playlistDao().setVodError(7, null)
            assertNull(db.playlistDao().byId(7)!!.vodError)

            val write = db.vodWriteDao()
            val film = write.filmsByKeys(7, listOf("f")).single()
            assertNull(film.container)
            assertNull(film.providerYear)
            assertNull(film.providerRating)
            assertNull(write.episodesByKeys(7, listOf("e")).single().container)
            val show = db.vodBrowseDao().show(7, "S").first()!!
            assertEquals(ShowSource.M3U, show.source)
            assertEquals(
                listOf<Any?>(null, null, null, null, null, null, null, null, null),
                listOf(
                    show.tvgId, show.lastModified, show.episodesFetchedAt, show.plot, show.cast, show.genre,
                    show.rating, show.year, show.backdrop,
                ),
            )

            val details = ProviderDetailsEntity(7, "f", "Plot", "A, B", "Drama", 7.5, 1999, 136, "http://img.test/b.jpg", Instant.ofEpochMilli(9))
            db.xtreamVodDao().upsertProviderDetails(details)
            assertEquals(details, db.xtreamVodDao().providerDetailsNow(7, "f"))
        } finally {
            db.close()
        }
    }

    @Test fun migrate1To7ViaAllMigrations() = runTest {
        helper.createDatabase(DB, 1).use { v1 ->
            v1.execSQL(
                """INSERT INTO playlists (id, name, type, epgUrlsFromSource, manualEpgUrl)
                   VALUES (7, 'Old', 'M3U', '', 'http://example.test/epg.xml')""",
            )
            v1.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder,
                   sortOrder) VALUES (1, 7, 'k', 4, 'Ch', 'http://s.example.test/1', 'G', 0, 0)""",
            )
        }

        helper.runMigrationsAndValidate(DB, 7, true, *ALL_MIGRATIONS).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val ch = db.channelDao().byId(1)!!
            assertEquals("Ch", ch.name)
            assertEquals(4, ch.guideNumber)
            assertEquals(4, ch.groupRank)
            assertTrue(ch.visible)
            db.favouriteDao().add(ch.ref, Instant.EPOCH)
            assertEquals(1, db.favouriteDao().count().first())
            assertEquals(listOf("http://example.test/epg.xml"), db.epgSourceDao().forPlaylist(7).map { it.url })
            assertNull(db.playlistDao().byId(7)!!.manualEpgUrl)
            db.channelPrefsDao().upsert(ChannelPrefsEntity(7, "k", hidden = true))
            assertTrue(db.channelPrefsDao().get(7, "k")!!.hidden)
            db.channelPrefsDao().upsert(ChannelPrefsEntity(7, "k", aspectMode = AspectMode.ZOOM))
            assertEquals(AspectMode.ZOOM, db.channelPrefsDao().get(7, "k")!!.aspectMode)
        } finally {
            db.close()
        }
    }

    @Test fun migrate12To13KeepsEditsAndAddsLockTables() = runTest {
        helper.createDatabase(DB, 12).use { v12 ->
            v12.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v12.execSQL(
                """INSERT INTO channel_prefs (playlistId, channelKey, hidden, epgShiftMinutes)
                   VALUES (7, 'k', 1, 0)""",
            )
            v12.execSQL(
                """INSERT INTO group_prefs (playlistId, groupTitle, hidden, sortMode)
                   VALUES (7, 'G', 1, 'PLAYLIST')""",
            )
        }

        // Validates the migrated schema against the exported schema 13.json.
        helper.runMigrationsAndValidate(DB, 13, true, MIGRATION_12_13).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val channel = db.channelPrefsDao().get(7, "k")!!
            assertTrue(channel.hidden)
            assertFalse(channel.locked)
            val group = db.groupPrefsDao().forPlaylist(7).single()
            assertTrue(group.hidden)
            assertFalse(group.locked)
            assertEquals(0, db.rowCount("show_prefs"))
            assertEquals(1, db.rowCount("lock_state"))
            assertFalse(db.lockDao().unlocked().first())
            withContext(Dispatchers.IO) {
                db.query("SELECT id, unlocked FROM lock_state", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                    assertEquals(0, it.getInt(1))
                }
            }
        } finally {
            db.close()
        }
    }

    @Test fun migrate2To7ViaAllMigrations() = runTest {
        helper.createDatabase(DB, 2).use { v2 ->
            v2.execSQL("""INSERT INTO playlists (id, name, type, epgUrlsFromSource) VALUES (7, 'Old', 'M3U', '')""")
            v2.execSQL(
                """INSERT INTO channels (id, playlistId, `key`, number, name, streamUrl, groupTitle, groupOrder,
                   sortOrder, epgChannelId) VALUES (1, 7, 'k', 2, 'Ch', 'http://s.example.test/1', 'G', 0, 0, 'e')""",
            )
            v2.execSQL(
                """INSERT INTO programmes (epgChannelId, start, stop, title, generation)
                   VALUES ('e', 0, 9999999999999, 'Show', 1)""",
            )
        }

        helper.runMigrationsAndValidate(DB, 7, true, *ALL_MIGRATIONS).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val ch = db.channelDao().byId(1)!!
            assertEquals("e", ch.guideEpgId)
            assertEquals(2, ch.guideNumber)
            assertEquals("Show", db.programmeDao().nowAndNext("e", Instant.EPOCH).single().title)
            assertTrue(db.epgSourceDao().forPlaylist(7).isEmpty())
        } finally {
            db.close()
        }
    }
}
