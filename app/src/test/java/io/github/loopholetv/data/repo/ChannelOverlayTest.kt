package io.github.loopholetv.data.repo

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.inMemoryDb
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
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class ChannelOverlayTest {
    private lateinit var db: AppDatabase
    private lateinit var overlay: ChannelOverlay
    private lateinit var edits: ChannelEdits
    private val playlists = mutableListOf<OverlayPlaylist>()

    @Before fun setUp() {
        db = inMemoryDb()
        overlay = ChannelOverlay(db)
        edits = ChannelEdits(db, overlay)
    }

    @After fun tearDown() {
        playlists.forEach { it.close() }
        db.close()
    }

    private fun playlist(name: String = "P") = OverlayPlaylist(db, name).also { playlists += it }

    @Test fun overridesSurviveRefresh() = runTest(timeout = 60.seconds) {
        val p = playlist()
        p.add(
            listOf(
                Entry("A", "News", 1), Entry("B", "News", 2), Entry("C", "News", 3),
                Entry("D", "Sport", 4), Entry("E", "Sport", 5), Entry("F", "Sport", 6), Entry("G", "Sport", 7),
            ),
        )
        edits.setHidden(p.ref("A"), true)
        edits.rename(p.ref("B"), "Bee")
        edits.setLogo(p.ref("C"), "http://logo.example.test/c.png")
        edits.setEpgOverride(p.ref("D"), "x.uk")
        edits.setShift(p.ref("E"), 60)
        assertTrue(edits.move(p.channel("G"), -1)) // Sport: D, E, G, F
        assertEquals(listOf("D", "E", "G", "F"), p.order("Sport"))

        // Reordered, renumbered and regrouped: A and B move to "Misc", the numbers change.
        p.refresh(
            listOf(
                Entry("F", "Sport", 20), Entry("E", "Sport", 21), Entry("D", "Sport", 22), Entry("G", "Sport", 23),
                Entry("C", "News", 30), Entry("B", "Misc", 40), Entry("A", "Misc", 41),
            ),
        )

        val a = p.channel("A")
        assertFalse(a.visible)
        assertEquals(41, a.number)
        assertEquals(41, a.guideNumber)
        assertEquals("Bee", p.channel("B").customName)
        assertEquals("Misc", p.channel("B").groupTitle)
        assertEquals("http://logo.example.test/c.png", p.channel("C").customLogo)
        assertEquals("x.uk", p.channel("D").guideEpgId)
        assertEquals(60, p.channel("E").epgShiftMinutes)
        // The custom order is kept by key, not by the source order.
        assertEquals(listOf("D", "E", "G", "F"), p.order("Sport"))
        // Unedited channels are visible and carry the new numbers.
        assertTrue(p.all().filter { it.name != "A" }.all { it.visible })
        assertEquals(listOf(20, 21, 22, 23, 30, 40, 41), p.all().map { it.guideNumber })
    }

    @Test fun returningChannelGetsItsEditsBack() = runTest(timeout = 60.seconds) {
        val p = playlist()
        p.add(entries("A", "B", "C"))
        val b = p.ref("B")
        edits.rename(b, "Bee")
        p.refresh(entries("A", "C"))
        assertNull(p.channelOrNull("B"))
        assertEquals("Bee", db.channelPrefsDao().get(b.playlistId, b.key)?.customName)
        p.refresh(entries("A", "B", "C"))
        assertEquals("Bee", p.channel("B").customName)
    }

    @Test fun hiddenChannelKeepsNumberSlot() = runTest(timeout = 60.seconds) {
        val p = playlist()
        p.add(entries("A", "B", "C"))
        edits.setHidden(p.ref("B"), true)
        assertEquals(listOf(1, 2, 3), p.all().map { it.guideNumber })
        assertEquals(listOf(true, false, true), p.all().map { it.visible })
        p.refresh(entries("A", "B", "C"))
        assertEquals(listOf(1, 2, 3), p.all().map { it.guideNumber })
        assertEquals(listOf(true, false, true), p.all().map { it.visible })
    }

    @Test fun hiddenGroupHidesItsChannels() = runTest(timeout = 60.seconds) {
        val p = playlist()
        p.add(listOf(Entry("A", "News"), Entry("B", "Sport"), Entry("C", "Sport")))
        edits.setGroupHidden(p.id, "Sport", true)
        assertEquals(listOf(true, false, false), p.all().map { it.visible })
        p.refresh(listOf(Entry("A", "News"), Entry("B", "Sport"), Entry("C", "Sport"), Entry("D", "Sport")))
        assertEquals(listOf(true, false, false, false), p.all().map { it.visible })
        edits.setGroupHidden(p.id, "Sport", false)
        assertTrue(p.all().all { it.visible })
        assertNull(db.groupPrefsDao().get(p.id, "Sport"))
    }

    @Test fun disabledPlaylistHidesEverythingAndKeepsEdits() = runTest(timeout = 60.seconds) {
        val p = playlist()
        p.add(entries("A", "B", "C"))
        edits.rename(p.ref("B"), "Bee")
        edits.setHidden(p.ref("C"), true)
        edits.setPlaylistEnabled(p.id, false)
        assertTrue(p.all().none { it.visible })
        assertEquals("Bee", p.channel("B").customName)
        edits.setPlaylistEnabled(p.id, true)
        assertEquals(listOf(true, true, false), p.all().map { it.visible })
        assertEquals("Bee", p.channel("B").customName)
    }

    @Test fun secondPlaylistContinuesNumbering() = runTest(timeout = 60.seconds) {
        val p1 = playlist("P1")
        val p2 = playlist("P2")
        p1.add(entries("A", "B", "C"))
        p2.add(entries("X", "Y"))
        assertEquals(listOf(1, 2, 3), p1.all().map { it.guideNumber })
        assertEquals(listOf(4, 5), p2.all().map { it.guideNumber })
        assertEquals(listOf(1, 2), p2.all().map { it.number })

        edits.setPlaylistEnabled(p1.id, false)
        assertEquals(listOf(1, 2), p2.all().map { it.guideNumber })
        assertEquals(listOf(1, 2, 3), p1.all().map { it.guideNumber })

        edits.setPlaylistEnabled(p1.id, true)
        assertEquals(listOf(4, 5), p2.all().map { it.guideNumber })

        // P1 grows on refresh → P2 moves along (the import renumbers every enabled playlist).
        p1.refresh(entries("A", "B", "C", "D"))
        assertEquals(listOf(5, 6), p2.all().map { it.guideNumber })
    }

    @Test fun deletingAPlaylistClosesUpTheNumbers() = runTest(timeout = 60.seconds) {
        val p1 = playlist("P1")
        val p2 = playlist("P2")
        p1.add(entries("A", "B", "C"))
        p2.add(entries("X", "Y"))
        assertEquals(listOf(4, 5), p2.all().map { it.guideNumber })
        p1.repo.delete(p1.id)
        assertEquals(listOf(1, 2), p2.all().map { it.guideNumber })
    }

    @Test fun numberOffsetsPure() {
        assertEquals(mapOf(1L to 0, 2L to 1360, 3L to 1410), numberOffsets(listOf(1L to 1360, 2L to 50, 3L to 9)))
        assertEquals(emptyMap<Long, Int>(), numberOffsets(emptyList()))
        assertEquals(mapOf(5L to 0), numberOffsets(listOf(5L to 0)))
    }

    @Test fun refreshAllRederivesEveryPlaylist() = runTest(timeout = 60.seconds) {
        val p1 = playlistRow("P1")
        val p2 = playlistRow("P2")
        db.channelDao().insertAll(listOf(row(p1, "a", 1, "G"), row(p1, "b", 2, "G"), row(p2, "x", 1, "H")))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p1, "b", hidden = true, customName = "Bee"))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p2, "H", hidden = true))
        overlay.refreshAll()
        val c1 = db.channelDao().channelsOf(p1)
        val c2 = db.channelDao().channelsOf(p2)
        assertEquals(listOf(true, false), c1.map { it.visible })
        assertEquals("Bee", c1[1].customName)
        assertEquals(listOf(false), c2.map { it.visible })
        assertEquals(listOf(3), c2.map { it.guideNumber })
    }

    @Test fun rankGroupFollowsTheGroupsSortMode() = runTest(timeout = 60.seconds) {
        val p = playlistRow("P")
        db.channelDao().insertAll(listOf(row(p, "k1", 1, "G", "zulu"), row(p, "k2", 2, "G", "Alpha"), row(p, "k3", 3, "G", "mike")))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "G", sortMode = GroupSort.NAME))
        db.withTransaction { overlay.rankGroup(p, "G") }
        assertEquals(listOf("k2", "k3", "k1"), ranked(p))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "G", sortMode = GroupSort.PLAYLIST))
        db.withTransaction { overlay.rankGroup(p, "G") }
        assertEquals(listOf("k1", "k2", "k3"), ranked(p))
        assertEquals(listOf(1, 2, 3), db.channelDao().channelsOf(p).map { it.groupRank })
    }

    @Test fun tenThousandChannelsApplyFast() = runTest(timeout = 120.seconds) {
        val p = playlistRow("Big")
        val rows = (0 until 10_000).map { i -> row(p, "k$i", i + 1, "Group ${i / 500}", "Channel ${10_000 - i}") }
        rows.chunked(500).forEach { db.channelDao().insertAll(it) }
        (0 until 300).forEach { i ->
            val k = i * 33
            db.channelPrefsDao().upsert(
                ChannelPrefsEntity(
                    p, "k$k", hidden = i % 3 == 0, customName = if (i % 3 == 1) "Renamed $i" else null,
                    epgOverride = if (i % 5 == 0) "o$i" else null, epgShiftMinutes = if (i % 7 == 0) 60 else 0,
                    position = if (k < 500) 500 - k else null,
                ),
            )
        }
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Group 0", sortMode = GroupSort.CUSTOM))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Group 1", sortMode = GroupSort.NAME))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Group 2", hidden = true))

        val started = System.nanoTime()
        db.withTransaction {
            overlay.applyPlaylist(p)
            overlay.renumber()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("applyPlaylist + renumber took $ms ms", ms < 3_000)

        val all = db.channelDao().channelsOf(p)
        val hiddenKeys = (0 until 300).filter { it % 3 == 0 }.map { "k${it * 33}" }.toSet()
        val expectedHidden = rows.filter { it.groupTitle == "Group 2" || it.key in hiddenKeys }.map { it.key }.toSet()
        assertEquals(expectedHidden, all.filter { !it.visible }.map { it.key }.toSet())
        assertEquals("Renamed 1", all.single { it.key == "k33" }.customName)
        assertEquals(60, all.single { it.key == "k0" }.epgShiftMinutes)
        assertEquals("o0", all.single { it.key == "k0" }.guideEpgId)
        // CUSTOM "Group 0": k495 has the lowest position (5); NAME "Group 1": "Channel 9001" (k999) sorts first.
        assertEquals("k495", all.filter { it.groupTitle == "Group 0" }.minBy { it.groupRank }.key)
        assertEquals("k999", all.filter { it.groupTitle == "Group 1" }.minBy { it.groupRank }.key)
    }

    private suspend fun ranked(p: Long) = db.channelDao().channelsOf(p).sortedBy { it.groupRank }.map { it.key }

    private suspend fun playlistRow(name: String): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name, type = PlaylistType.M3U, url = null, xtreamServer = null, xtreamUser = null,
            xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
        ),
    )

    private fun row(playlistId: Long, key: String, number: Int, group: String, name: String = key) = ChannelEntity(
        playlistId = playlistId, key = key, number = number, name = name, streamUrl = "http://s.example.test/$key",
        logo = null, groupTitle = group, groupOrder = 0, sortOrder = number - 1, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = null,
    )
}
