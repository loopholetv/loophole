package io.github.loopholetv.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    @Test fun migrate1To4ViaAllMigrations() = runTest {
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

        helper.runMigrationsAndValidate(DB, 4, true, *ALL_MIGRATIONS).close()

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
        } finally {
            db.close()
        }
    }

    @Test fun migrate2To4ViaAllMigrations() = runTest {
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

        helper.runMigrationsAndValidate(DB, 4, true, *ALL_MIGRATIONS).close()

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
