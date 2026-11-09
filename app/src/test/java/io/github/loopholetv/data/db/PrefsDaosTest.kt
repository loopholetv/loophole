package io.github.loopholetv.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsDaosTest {
    private lateinit var db: AppDatabase
    private var p = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    @Test fun setEpgChannelIdKeepsManualOverride() = runTest {
        val ch = db.insertChannels(channel(p, "a", 1, epgChannelId = "a.uk")).getValue("a")
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", epgOverride = "x.uk"))

        db.channelDao().setEpgChannelId(ch.id, null)

        val read = db.channelDao().byId(ch.id)!!
        assertNull(read.epgChannelId)
        assertEquals("x.uk", read.guideEpgId)

        db.channelDao().setEpgChannelId(ch.id, "matched.uk")
        assertEquals("matched.uk", db.channelDao().byId(ch.id)!!.epgChannelId)
        assertEquals("x.uk", db.channelDao().byId(ch.id)!!.guideEpgId)
    }

    @Test fun setEpgChannelIdWithoutOverrideMirrors() = runTest {
        val ch = db.insertChannels(channel(p, "a", 1)).getValue("a")
        // A prefs row with no EPG override (e.g. only a custom name) must not block the mirror.
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", customName = "Mine"))

        db.channelDao().setEpgChannelId(ch.id, "a.uk")
        assertEquals("a.uk", db.channelDao().byId(ch.id)!!.guideEpgId)

        db.channelDao().setEpgChannelId(ch.id, null)
        assertNull(db.channelDao().byId(ch.id)!!.guideEpgId)
    }

    @Test fun deletingPlaylistCascadesPrefsSourcesAndGuideChannels() = runTest {
        val other = db.addPlaylist("Other")
        for (id in listOf(p, other)) {
            db.channelPrefsDao().upsert(ChannelPrefsEntity(id, "a", hidden = true))
            db.groupPrefsDao().upsert(GroupPrefsEntity(id, "G", hidden = true))
            db.epgSourceDao().add(id, "http://example.test/epg.xml")
            db.epgChannelDao().upsertAll(listOf(EpgChannelEntity(id, "a.uk", "A")))
        }

        db.playlistDao().delete(p)

        for (table in listOf("channel_prefs", "group_prefs", "epg_sources", "epg_channels")) {
            assertEquals(table, 1, db.rowCount(table))
        }
        assertEquals(1, db.channelPrefsDao().forPlaylist(other).size)
        assertEquals(1, db.groupPrefsDao().forPlaylist(other).size)
        assertEquals(1, db.epgSourceDao().forPlaylist(other).size)
        assertEquals(1, db.epgChannelDao().count(other))
    }

    @Test fun prefsOutliveTheirChannelRow() = runTest {
        val ch = db.insertChannels(channel(p, "a", 1)).getValue("a")
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", customName = "Mine", position = 3))

        db.channelDao().deleteByIds(listOf(ch.id))

        assertNull(db.channelDao().byId(ch.id))
        assertEquals(ChannelPrefsEntity(p, "a", customName = "Mine", position = 3), db.channelPrefsDao().get(p, "a"))
    }

    @Test fun epgSourcesAppendInOrder() = runTest {
        val other = db.addPlaylist("Other")
        db.epgSourceDao().add(p, "http://example.test/a.xml")
        db.epgSourceDao().add(other, "http://example.test/z.xml")
        db.epgSourceDao().add(p, "http://example.test/b.xml")

        val sources = db.epgSourceDao().forPlaylist(p)
        assertEquals(listOf("http://example.test/a.xml", "http://example.test/b.xml"), sources.map { it.url })
        assertEquals(listOf(0, 1), sources.map { it.position })
        assertEquals(sources, db.epgSourceDao().observe(p).first())
        assertEquals(listOf(0), db.epgSourceDao().forPlaylist(other).map { it.position })

        db.epgSourceDao().delete(sources[0].id)
        db.epgSourceDao().add(p, "http://example.test/c.xml")
        assertEquals(
            listOf("http://example.test/b.xml" to 1, "http://example.test/c.xml" to 2),
            db.epgSourceDao().forPlaylist(p).map { it.url to it.position },
        )
    }

    @Test fun guideChannelSearchEscapesAndOrders() = runTest {
        val other = db.addPlaylist("Other")
        db.epgChannelDao().replaceForPlaylist(
            p,
            listOf(
                EpgChannelEntity(p, "club.500", "500 Club"),
                EpgChannelEntity(p, "zeta", "zeta 50% off"),
                EpgChannelEntity(p, "alpha", "Alpha 50%"),
                EpgChannelEntity(p, "pct50%.id", "Plain"),
                EpgChannelEntity(p, "beta", "beta 50%"),
            ),
        )
        db.epgChannelDao().upsertAll(listOf(EpgChannelEntity(other, "o", "Other 50%")))

        val hits = db.epgChannelDao().search(p, searchPattern("50%")!!, limit = 10)

        // "500 Club" has no literal "%"; the id column matches too; order ignores case.
        assertEquals(listOf("Alpha 50%", "beta 50%", "Plain", "zeta 50% off"), hits.map { it.displayName })
        assertEquals(2, db.epgChannelDao().search(p, searchPattern("50%")!!, limit = 2).size)
        // Underscore is literal too.
        assertTrue(db.epgChannelDao().search(p, searchPattern("a_")!!, limit = 10).isEmpty())
    }

    @Test fun replaceForPlaylistSwapsRowsInChunks() = runTest {
        val other = db.addPlaylist("Other")
        db.epgChannelDao().upsertAll(listOf(EpgChannelEntity(p, "old", "Old"), EpgChannelEntity(other, "keep", "Keep")))

        val rows = (1..1234).map { EpgChannelEntity(p, "id$it", "Name $it") }
        db.epgChannelDao().replaceForPlaylist(p, rows)

        assertEquals(1234, db.epgChannelDao().count(p))
        assertEquals(1, db.epgChannelDao().count(other))
        assertTrue(db.epgChannelDao().search(p, searchPattern("Old")!!, 10).isEmpty())
    }

    @Test fun channelPrefsQueries() = runTest {
        val dao = db.channelPrefsDao()
        assertNull(dao.observe(p, "a").first())
        assertNull(dao.maxPositiveShift())

        dao.upsert(ChannelPrefsEntity(p, "a", epgOverride = "a.uk", epgShiftMinutes = -60))
        dao.upsert(ChannelPrefsEntity(p, "b", epgShiftMinutes = 90))
        dao.upsert(ChannelPrefsEntity(p, "c", hidden = true))
        assertEquals(90, dao.maxPositiveShift())
        assertEquals(listOf("a"), dao.epgOverrides(p).map { it.channelKey })
        assertEquals(listOf("a", "b", "c"), dao.forPlaylist(p).map { it.channelKey }.sorted())
        assertEquals(-60, dao.observe(p, "a").first()!!.epgShiftMinutes)

        dao.upsert(ChannelPrefsEntity(p, "a", hidden = true))
        assertNull(dao.get(p, "a")!!.epgOverride)

        dao.delete(p, "a")
        assertNull(dao.get(p, "a"))
    }

    @Test fun epgOverridesAndMaxPositiveShift() = runTest {
        val dao = db.channelPrefsDao()
        dao.upsert(ChannelPrefsEntity(p, "o", epgOverride = "o.uk"))
        dao.upsert(ChannelPrefsEntity(p, "n", customName = "No override"))
        assertEquals(listOf(ChannelPrefsEntity(p, "o", epgOverride = "o.uk")), dao.epgOverrides(p))
        assertNull(dao.maxPositiveShift())

        dao.upsert(ChannelPrefsEntity(p, "m", epgShiftMinutes = -60))
        dao.upsert(ChannelPrefsEntity(p, "q", epgShiftMinutes = 120))
        assertEquals(120, dao.maxPositiveShift())
    }

    @Test fun onlyNegativeShiftsHaveNoPositiveMax() = runTest {
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", epgShiftMinutes = -30))
        assertNull(db.channelPrefsDao().maxPositiveShift())
    }

    @Test fun groupPrefsQueries() = runTest {
        val dao = db.groupPrefsDao()
        dao.upsert(GroupPrefsEntity(p, "News", customName = "Nieuws", sortMode = GroupSort.NAME))
        assertEquals(GroupSort.NAME, dao.get(p, "News")!!.sortMode)
        assertEquals(1, dao.forPlaylist(p).size)
        dao.delete(p, "News")
        assertNull(dao.get(p, "News"))
    }

    @Test fun isDefaultOnlyForUneditedRows() {
        assertTrue(ChannelPrefsEntity(1, "k").isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", hidden = true).isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", customName = "x").isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", customLogo = "x").isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", epgOverride = "x").isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", epgShiftMinutes = 30).isDefault())
        assertFalse(ChannelPrefsEntity(1, "k", position = 0).isDefault())

        assertTrue(GroupPrefsEntity(1, "G").isDefault())
        assertFalse(GroupPrefsEntity(1, "G", hidden = true).isDefault())
        assertFalse(GroupPrefsEntity(1, "G", customName = "x").isDefault())
        assertFalse(GroupPrefsEntity(1, "G", position = 0).isDefault())
        assertFalse(GroupPrefsEntity(1, "G", sortMode = GroupSort.CUSTOM).isDefault())
    }

    @Test fun displayValuesPreferCustom() {
        val ch = channel(p, "a", 1, name = "BBC One", logo = "http://example.test/a.png")
        assertEquals("BBC One", ch.displayName)
        assertEquals("http://example.test/a.png", ch.displayLogo)
        val edited = ch.copy(customName = "One", customLogo = "http://example.test/b.png")
        assertEquals("One", edited.displayName)
        assertEquals("http://example.test/b.png", edited.displayLogo)
    }

    @Test fun newChannelsGetDerivedDefaults() = runTest {
        val ch = db.insertChannels(channel(p, "a", 7, epgChannelId = "a.uk")).getValue("a")
        assertTrue(ch.visible)
        assertEquals(7, ch.guideNumber)
        assertEquals(7, ch.groupRank)
        assertEquals("a.uk", ch.guideEpgId)
        assertEquals(0, ch.epgShiftMinutes)
        assertFalse(ch.needsLink)
        assertNull(ch.customName)
        assertNull(ch.customLogo)
    }

    @Test fun fallbackNumberingUsesPlaylistNumberIndex() = runTest {
        val plan = withContext(Dispatchers.IO) {
            db.query("EXPLAIN QUERY PLAN SELECT id FROM channels WHERE playlistId = 1 AND number IN (3, 4)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(3)) }
            }
        }
        assertTrue("plan: $plan", plan.any { it.contains("index_channels_playlistId_number") })
    }

    @Test fun derivedColumnsMirrorSourcesByDefault() {
        val ch = ChannelEntity(
            playlistId = 1, key = "k", number = 42, name = "N", streamUrl = "http://s.example.test/k", logo = null,
            groupTitle = "G", groupOrder = 0, sortOrder = 3, tvgId = null, tvgName = null, userAgent = null,
            referrer = null, catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = "n.uk",
        )
        assertEquals(42, ch.guideNumber)
        assertEquals(42, ch.groupRank)
        assertEquals("n.uk", ch.guideEpgId)
        assertNull(channel(1, "x", 5).guideEpgId)
    }

    @Test fun playlistWrites() = runTest {
        val dao = db.playlistDao()
        val fresh = dao.byId(p)!!
        assertTrue(fresh.enabled)
        assertTrue(fresh.useSourceEpg)
        assertFalse(fresh.rekeyPending)
        assertNull(fresh.updateHours)
        assertNull(fresh.updateOnStart)

        dao.rename(p, "Renamed")
        dao.setEnabled(p, false)
        dao.setUserAgent(p, "UA/1")
        dao.setUpdateOptions(p, hours = 12, onStart = false)
        dao.setUseSourceEpg(p, false)
        dao.setRekeyPending(p, true)
        dao.setPortalSession(p, endpoint = "http://portal.example.test/stalker_portal/server/load.php", token = "T0K")

        val read = dao.byId(p)!!
        assertEquals("Renamed", read.name)
        assertFalse(read.enabled)
        assertEquals("UA/1", read.userAgent)
        assertEquals(12, read.updateHours)
        assertEquals(false, read.updateOnStart)
        assertFalse(read.useSourceEpg)
        assertTrue(read.rekeyPending)
        assertEquals("http://portal.example.test/stalker_portal/server/load.php", read.portalEndpoint)
        assertEquals("T0K", read.portalToken)

        dao.setUpdateOptions(p, hours = null, onStart = null)
        dao.setPortalSession(p, endpoint = null, token = null)
        dao.update(dao.byId(p)!!.copy(type = PlaylistType.STALKER, mac = "00:1A:79:00:00:01"))
        val stalker = dao.byId(p)!!
        assertNull(stalker.updateHours)
        assertNull(stalker.updateOnStart)
        assertNull(stalker.portalToken)
        assertEquals(PlaylistType.STALKER, stalker.type)
        assertEquals("00:1A:79:00:00:01", stalker.mac)
    }
}
