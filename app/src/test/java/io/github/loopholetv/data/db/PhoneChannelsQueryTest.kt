package io.github.loopholetv.data.db

import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.ChannelOverlay
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneChannelsQueryTest {
    private lateinit var db: AppDatabase
    private var p = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    private suspend fun page(group: String?, query: String? = null, limit: Int = 100, offset: Int = 0) =
        db.channelDao().phoneChannels(p, group, query, limit, offset)

    private fun ch(key: String, number: Int, group: String, groupOrder: Int, name: String = key) =
        channel(p, key, number, name = name, groupTitle = group).copy(groupOrder = groupOrder)

    @Test fun pagesInEditorOrder() = runTest {
        db.insertChannels(
            ch("a", 3, "G", 0), ch("b", 1, "G", 0), ch("c", 2, "G", 0), ch("d", 4, "G", 0),
        )
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "c", hidden = true))
        db.favouriteDao().add(ChannelRef(p, "d"), Instant.ofEpochMilli(1))

        val first = page("G", limit = 3)
        assertEquals(listOf("b", "c", "a"), first.map { it.channel.key })
        assertEquals(listOf(false, true, false), first.map { it.hidden })
        assertEquals(listOf("d"), page("G", limit = 3, offset = 3).map { it.channel.key })
        assertTrue(page("G", offset = 3).single().favourite)
        assertFalse(first.any { it.favourite })
    }

    @Test fun searchesCustomAndSourceName() = runTest {
        db.insertChannels(ch("a", 1, "G", 0, name = "Alpha News"), ch("b", 2, "G", 0, name = "Beta"))
        ChannelEdits(db, ChannelOverlay(db)).rename(ChannelRef(p, "b"), "Sports Hub")

        assertEquals(listOf("a"), page(null, "alpha").map { it.channel.key })
        assertEquals(listOf("b"), page(null, "SPORTS").map { it.channel.key })
        // The source name of a renamed channel no longer matches: only what is shown does.
        assertEquals(emptyList<String>(), page(null, "beta").map { it.channel.key })
        assertEquals("Sports Hub", page(null, "hub").single().customName)
    }

    @Test fun searchEscapesWildcards() = runTest {
        db.insertChannels(
            ch("a", 1, "G", 0, name = "Radio 50% Hits"), ch("b", 2, "G", 0, name = "Radio 500"),
            ch("c", 3, "G", 0, name = "a_b"), ch("d", 4, "G", 0, name = "axb"), ch("e", 5, "G", 0, name = "back\\slash"),
        )
        assertEquals(listOf("a"), page(null, "50%").map { it.channel.key })
        assertEquals(listOf("c"), page(null, "a_b").map { it.channel.key })
        assertEquals(listOf("e"), page(null, "k\\s").map { it.channel.key })
    }

    @Test fun acrossGroupsFollowsGroupOrder() = runTest {
        db.insertChannels(
            ch("a1", 1, "A", 0), ch("a2", 2, "A", 0),
            ch("b1", 1, "B", 1), ch("b2", 2, "B", 1),
            ch("c1", 1, "C", 2),
        )
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1"), page(null).map { it.channel.key })
        // A group moved by hand goes first, as the editor lists it.
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "C", position = 0))
        assertEquals(listOf("c1", "a1", "a2", "b1", "b2"), page(null).map { it.channel.key })
        assertEquals(listOf("b1", "b2"), page("B").map { it.channel.key })
    }

    @Test fun twentyThousandChannelsPageFast() = runTest {
        db.channelDao().insertAll(
            (1..20_000).map { ch("k$it", it, "G${it % 40}", it % 40, name = "Channel $it") },
        )
        val start = System.nanoTime()
        val rows = page(null, limit = 200, offset = 19_800)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(200, rows.size)
        assertTrue("took $ms ms", ms < 1_000)
    }
}
