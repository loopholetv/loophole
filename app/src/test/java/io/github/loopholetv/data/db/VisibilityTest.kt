package io.github.loopholetv.data.db

import io.github.loopholetv.data.repo.ScopedChannels
import kotlinx.coroutines.flow.first
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
import java.time.Instant

/**
 * Hidden channels (`visible = 0`: hidden themselves, in a hidden group or in a turned-off playlist)
 * never come back through any channel read, and groups and zapping are per playlist.
 */
@RunWith(RobolectricTestRunner::class)
class VisibilityTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ChannelDao
    private lateinit var scoped: ScopedChannels
    private val now = Instant.ofEpochSecond(1_000_000)

    @Before fun setUp() {
        db = inMemoryDb()
        dao = db.channelDao()
        scoped = ScopedChannels(db.channelDao(), db.favouriteDao())
    }

    @After fun tearDown() = db.close()

    private fun List<ChannelEntity>.keys() = map { it.key }

    private fun programme(epg: String, title: String) = ProgrammeEntity(
        epgChannelId = epg, start = now.minusSeconds(60), stop = now.plusSeconds(3600), title = title,
        subTitle = null, description = null, category = null, generation = 1,
    )

    @Test fun hiddenChannelUnreachableEverywhere() = runTest {
        val p1 = db.addPlaylist("One")
        val p2 = db.addPlaylist("Two")
        db.playlistDao().setEnabled(p2, false)
        val p3 = db.addPlaylist("Three")
        val ch = db.insertChannels(
            channel(p1, "a", 1, "A News", epgChannelId = "ea", groupTitle = "News", groupRank = 1),
            channel(p1, "b", 2, "B Sport", groupTitle = "Sport", groupRank = 1),
            channel(p1, "h", 3, "H News", epgChannelId = "eh", groupTitle = "News", groupRank = 2, visible = false),
            channel(p1, "c", 4, "C News", groupTitle = "News", groupRank = 3),
            // Turned-off playlist: its channel shares guide number 5 with "d".
            channel(p2, "x", 1, "X News", epgChannelId = "ex", groupTitle = "News", guideNumber = 5, visible = false),
            channel(p3, "d", 1, "D News", groupTitle = "News", guideNumber = 5, groupRank = 1),
            channel(p3, "e", 2, "E News", groupTitle = "News", guideNumber = 6, groupRank = 2),
        )
        fun c(key: String) = ch.getValue(key)
        val hidden = setOf("h", "x")
        val news1 = ChannelScope.Group(p1, "News")

        // Lists
        assertEquals(listOf("a", "b", "c", "d", "e"), dao.allChannels().loadAll().keys())
        assertEquals(listOf("a", "c"), dao.groupChannels(p1, "News").loadAll().keys())
        assertEquals(emptyList<String>(), dao.groupChannels(p2, "News").loadAll().keys())
        assertEquals(
            listOf(Triple(p1, "News", 2), Triple(p1, "Sport", 1), Triple(p3, "News", 2)),
            dao.groups().first().map { Triple(it.playlistId, it.title, it.count) },
        )

        // Number entry
        assertNull(dao.byNumber(3))
        assertEquals("d", dao.byNumber(5)?.key)

        // Zapping in All channels, both directions and through the wrap
        assertEquals("c", dao.neighbour(c("b"), null, +1)?.key)
        assertEquals("b", dao.neighbour(c("c"), null, -1)?.key)
        assertEquals("d", dao.neighbour(c("c"), null, +1)?.key)
        assertEquals("a", dao.neighbour(c("e"), null, +1)?.key)
        assertEquals("e", dao.neighbour(c("a"), null, -1)?.key)
        assertEquals("c", dao.neighbour(c("h"), null, +1)?.key) // from a hidden current
        for (dir in listOf(+1, -1)) {
            val walk = generateSequence(c("a")) { kotlinx.coroutines.runBlocking { dao.neighbour(it, null, dir) } }
                .take(12).toList().keys()
            assertTrue("All $dir: $walk", walk.none { it in hidden })
            val group = generateSequence(c("a")) { kotlinx.coroutines.runBlocking { dao.neighbour(it, news1, dir) } }
                .take(6).toList().keys()
            assertEquals(listOf("a", "c", "a", "c", "a", "c"), group)
        }
        assertEquals(listOf("a", "b", "c", "d", "e"), dao.around(c("c"), null, 10).keys())
        assertEquals(listOf("a", "c"), dao.around(c("a"), news1, 10).keys())
        assertEquals(listOf("a", "c"), dao.around(c("c"), news1, 10).keys())

        // Favourites
        var t = 0L
        listOf("h", "x", "a").forEach { db.favouriteDao().add(c(it).ref, Instant.ofEpochMilli(++t)) }
        assertEquals(listOf("a"), db.favouriteDao().channels().loadAll().keys())
        assertEquals(1, db.favouriteDao().count().first())
        assertEquals("a", db.favouriteDao().neighbour(c("a"), +1)?.key)
        assertEquals("a", db.favouriteDao().neighbour(c("a"), -1)?.key)
        assertEquals("a", db.favouriteDao().neighbour(c("h"), +1)?.key)
        assertEquals(listOf("a"), db.favouriteDao().around(c("a"), 5).keys())
        assertEquals(listOf("a"), scoped.pages(ChannelScope.Favourites).loadAll().keys())
        // Through the seam the player uses.
        assertEquals("a", scoped.neighbour(c("a"), ChannelScope.Favourites, +1)?.key)
        assertEquals("a", scoped.neighbour(c("h"), ChannelScope.Favourites, -1)?.key)
        assertEquals(listOf("a"), scoped.around(c("a"), ChannelScope.Favourites, 5).keys())

        // History
        listOf("a", "h", "x").forEach { db.historyDao().record(c(it).ref, Instant.ofEpochMilli(++t), keep = 10) }
        assertEquals("a", db.historyDao().latest()?.key)
        assertEquals(listOf("a"), db.historyDao().recent(10).first().keys())

        // Search
        val pattern = searchPattern("news")!!
        assertEquals(listOf("a", "c", "d", "e"), db.searchDao().channels(pattern, 10).keys())
        db.programmeDao().insertAll(listOf(programme("ea", "News A"), programme("eh", "News H"), programme("ex", "News X")))
        assertEquals(listOf("News A"), db.searchDao().programmes(pattern, now, 10).map { it.programme.title })

        // contains()
        assertTrue(scoped.contains(c("a"), ChannelScope.All))
        assertFalse(scoped.contains(c("h"), ChannelScope.All))
        assertFalse(scoped.contains(c("x"), ChannelScope.All))
        assertTrue(scoped.contains(c("a"), ChannelScope.Favourites))
        assertFalse(scoped.contains(c("h"), ChannelScope.Favourites))
        assertFalse(scoped.contains(c("x"), ChannelScope.Favourites))
        assertTrue(scoped.contains(c("a"), news1))
        assertFalse(scoped.contains(c("h"), news1))
        assertFalse(scoped.contains(c("d"), news1)) // same title, other playlist
        assertFalse(scoped.contains(c("x"), ChannelScope.Group(p2, "News")))
        assertTrue(scoped.contains(c("d"), ChannelScope.Group(p3, "News")))
    }

    @Test fun groupsSeparatePerPlaylistWithCustomLabelsAndOrder() = runTest {
        val p1 = db.addPlaylist("One")
        val p3 = db.addPlaylist("Three")
        db.insertChannels(
            channel(p1, "n1", 1, groupTitle = "News"),
            channel(p1, "s1", 2, groupTitle = "Sport").copy(groupOrder = 1),
            channel(p3, "n3", 1, groupTitle = "News", guideNumber = 3),
            channel(p3, "n3b", 2, groupTitle = "News", guideNumber = 4),
            channel(p3, "m3", 3, groupTitle = "Movies", guideNumber = 5).copy(groupOrder = 1),
        )
        db.groupPrefsDao().upsert(GroupPrefsEntity(p1, "News", customName = "Headlines", position = 5, sortMode = GroupSort.NAME))
        assertEquals(
            listOf(
                // A group with a custom position comes before the unpositioned ones.
                GroupRow(p1, "News", "Headlines", 5, 1, "One", GroupSort.NAME),
                GroupRow(p1, "Sport", "Sport", 1, 1, "One", GroupSort.PLAYLIST),
                GroupRow(p3, "News", "News", 0, 2, "Three", GroupSort.PLAYLIST),
                GroupRow(p3, "Movies", "Movies", 1, 1, "Three", GroupSort.PLAYLIST),
            ),
            dao.groups().first(),
        )
    }

    @Test fun allScopeZapsInGuideNumberOrder() = runTest {
        val p1 = db.addPlaylist("One")
        val p3 = db.addPlaylist("Three")
        val ch = db.insertChannels(
            channel(p1, "1", 1), channel(p1, "2", 2), channel(p1, "3", 3),
            // The "Three" playlist's own numbers 1..2 continue on as guide numbers 4..5.
            channel(p3, "4", 1, guideNumber = 4), channel(p3, "5", 2, guideNumber = 5),
        )
        fun c(key: String) = ch.getValue(key)
        assertEquals(listOf("1", "2", "3", "4", "5"), dao.allChannels().loadAll().keys())
        assertEquals("4", dao.neighbour(c("3"), null, +1)?.key)
        assertEquals("1", dao.neighbour(c("5"), null, +1)?.key)
        assertEquals("5", dao.neighbour(c("1"), null, -1)?.key)
        assertEquals("4", dao.byNumber(4)?.key)
        assertEquals("1", dao.byNumber(1)?.key)
        assertEquals(listOf("2", "3", "4", "5"), dao.around(c("4"), null, 2).keys())
    }

    @Test fun groupScopeZapsInGroupRankOrder() = runTest {
        val p = db.addPlaylist()
        val ch = db.insertChannels(
            channel(p, "k1", 1, groupTitle = "News", groupRank = 3),
            channel(p, "k2", 2, groupTitle = "News", groupRank = 1),
            channel(p, "k3", 3, groupTitle = "News", groupRank = 2),
            channel(p, "k4", 4, groupTitle = "Sport", groupRank = 0),
        )
        fun c(key: String) = ch.getValue(key)
        val news = ChannelScope.Group(p, "News")
        assertEquals(listOf("k2", "k3", "k1"), dao.groupChannels(p, "News").loadAll().keys())
        assertEquals(listOf("k2", "k3", "k1"), scoped.pages(news).loadAll().keys())
        assertEquals("k3", dao.neighbour(c("k2"), news, +1)?.key)
        assertEquals("k2", dao.neighbour(c("k1"), news, +1)?.key) // wraps
        assertEquals("k1", dao.neighbour(c("k2"), news, -1)?.key) // wraps
        assertEquals("k2", scoped.neighbour(c("k3"), news, -1)?.key)
        assertEquals(listOf("k2", "k3", "k1"), dao.around(c("k3"), news, 1).keys())
        assertEquals(listOf("k2", "k3", "k1"), scoped.around(c("k3"), news, 1).keys())
    }

    @Test fun equalGroupRanksFollowNumberNotId() = runTest {
        val p = db.addPlaylist()
        // Inserted (lower id) first but numbered higher: number breaks the groupRank tie.
        val ch = db.insertChannels(
            channel(p, "nine", 9, groupTitle = "News", groupRank = 0),
            channel(p, "eight", 8, groupTitle = "News", groupRank = 0),
            channel(p, "last", 1, groupTitle = "News", groupRank = 1),
        )
        val news = ChannelScope.Group(p, "News")
        assertEquals(listOf("eight", "nine", "last"), dao.groupChannels(p, "News").loadAll().keys())
        assertEquals("nine", dao.neighbour(ch.getValue("eight"), news, +1)?.key)
        assertEquals("eight", dao.neighbour(ch.getValue("nine"), news, -1)?.key)
        assertEquals(listOf("eight", "nine", "last"), dao.editorChannels(p, "News").loadAll().map { it.channel.key })
    }

    @Test fun keysetAgreesWithPaging() = runTest {
        val p1 = db.addPlaylist("One")
        val p3 = db.addPlaylist("Three")
        db.insertChannels(
            channel(p1, "a", 1, guideNumber = 1, groupRank = 0),
            channel(p1, "b", 2, guideNumber = 1, groupRank = 0),
            channel(p1, "c", 3, guideNumber = 2, groupRank = 1),
            channel(p1, "z", 4, guideNumber = 2, groupRank = 0, visible = false),
            channel(p3, "d", 1, guideNumber = 1, groupRank = 0),
            channel(p3, "e", 2, guideNumber = 2, groupRank = 5),
            channel(p3, "f", 3, guideNumber = 2, groupRank = 5),
        )
        for (scope in listOf<ChannelScope>(ChannelScope.All, ChannelScope.Group(p1, "G"), ChannelScope.Group(p3, "G"))) {
            val group = scope as? ChannelScope.Group
            val paged = scoped.pages(scope).loadAll()
            assertTrue("$scope", paged.size >= 3)
            val forward = generateSequence(paged.first()) { kotlinx.coroutines.runBlocking { dao.neighbour(it, group, +1) } }
                .take(paged.size + 1).toList()
            assertEquals("$scope forward", paged + paged.first(), forward)
            val backward = generateSequence(paged.last()) { kotlinx.coroutines.runBlocking { dao.neighbour(it, group, -1) } }
                .take(paged.size + 1).toList()
            assertEquals("$scope backward", paged.reversed() + paged.last(), backward)
            paged.forEach { assertEquals("$scope around ${it.key}", paged, dao.around(it, group, paged.size)) }
        }
    }

    @Test fun editorQueriesIncludeHidden() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(
            channel(p, "a", 1, groupTitle = "News", groupRank = 1),
            channel(p, "h", 2, groupTitle = "News", groupRank = 2, visible = false),
            // Hidden through its group: its own prefs say not hidden.
            channel(p, "b", 3, groupTitle = "Sport", groupRank = 1, visible = false).copy(groupOrder = 1),
        )
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "h", hidden = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Sport", hidden = true, customName = "Games", sortMode = GroupSort.CUSTOM))
        assertEquals(
            listOf(
                EditorGroupRow("News", "News", hidden = false, renamed = false, count = 2, order = 0, sortMode = GroupSort.PLAYLIST),
                EditorGroupRow("Sport", "Games", hidden = true, renamed = true, count = 1, order = 1, sortMode = GroupSort.CUSTOM),
            ),
            dao.editorGroups(p).first(),
        )
        val news = dao.editorChannels(p, "News").loadAll()
        assertEquals(listOf("a" to false, "h" to true), news.map { it.channel.key to it.hidden })
        val sport = dao.editorChannels(p, "Sport").loadAll().single()
        assertEquals("b", sport.channel.key)
        assertFalse(sport.hidden)
        assertFalse(sport.channel.visible)
        // Not in the guide's groups.
        assertEquals(listOf("News"), dao.groups().first().map { it.title })
    }

    @Test fun programmeSharedWithHiddenChannelShowsTheVisibleOne() = runTest {
        val p = db.addPlaylist()
        val ch = db.insertChannels(
            // The hidden channel has the lower number: MIN(guideNumber) must still never pick it.
            channel(p, "h", 1, "Hidden", epgChannelId = "e", visible = false),
            channel(p, "a", 2, "Shown", epgChannelId = "e"),
        )
        db.programmeDao().insertAll(listOf(programme("e", "News shared")))
        val hit = db.searchDao().programmes(searchPattern("news")!!, now, 10).single()
        assertEquals(ch.getValue("a").id, hit.channelId)
        assertEquals("Shown", hit.channelName)
        assertEquals(2, hit.channelNumber)
    }

    @Test fun countWithEpgUsesGuideId() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(
            channel(p, "a", 1, epgChannelId = "ea"),
            channel(p, "b", 2, guideEpgId = "manual"), // manual mapping only
            channel(p, "c", 3, epgChannelId = "ec", guideEpgId = null),
            channel(p, "d", 4, epgChannelId = "ed", visible = false),
        )
        assertEquals(3, dao.countWithEpg(p))
    }
}
