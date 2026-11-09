package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.M3uPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.loadAll
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.db.rowCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class FavouritesRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var m3u: M3uPlaylist
    private lateinit var favourites: FavouritesRepository
    private lateinit var history: HistoryRepository
    private var t = 0L

    @Before fun setUp() {
        db = inMemoryDb()
        m3u = M3uPlaylist(db)
        favourites = FavouritesRepository(db) { Instant.ofEpochMilli(++t) }
        history = HistoryRepository(db) { Instant.ofEpochMilli(++t) }
    }

    @After fun tearDown() {
        m3u.close()
        db.close()
    }

    private suspend fun listed() = db.favouriteDao().channels().loadAll().map { it.name }

    private suspend fun rows(table: String): Int = db.rowCount(table)

    @Test fun favouriteSurvivesRefreshAndReturnsWhenChannelReappears() = runTest {
        m3u.add("A", "B", "C")
        for (name in listOf("A", "B", "C")) assertTrue(favourites.toggle(m3u.channel(name)))
        val oldB = m3u.channel("B")

        m3u.refresh("A", "C")
        assertEquals(2, favourites.count.first())
        assertTrue(oldB.ref in favourites.refs.first())
        assertEquals(listOf("A", "C"), listed())

        m3u.refresh("A", "B", "C")
        val newB = m3u.channel("B")
        assertNotEquals("B came back as a new row", oldB.id, newB.id)
        assertEquals(3, favourites.count.first())
        assertEquals(listOf("A", "B", "C"), listed())
        assertTrue(favourites.isFavourite(newB))
    }

    @Test fun favouriteKeepsRowIdAcrossRefreshWhenKeyStays() = runTest {
        m3u.add("A", "B")
        val a = m3u.channel("A")
        favourites.toggle(a)
        m3u.refresh("B", "A") // reordered
        assertEquals(a.id, m3u.channel("A").id)
        assertEquals(listOf("A"), listed())
    }

    @Test fun deletingPlaylistRemovesFavouritesAndHistory() = runTest {
        m3u.add("A", "B")
        favourites.toggle(m3u.channel("A"))
        history.record(m3u.channel("B"))
        assertEquals("B", history.latest()?.name)

        m3u.repo.delete(m3u.id)
        assertEquals(emptySet<Any>(), favourites.refs.first())
        assertEquals(0, favourites.count.first())
        assertEquals(emptyList<Any>(), history.recent().first())
        assertEquals(0, rows("favourites"))
        assertEquals(0, rows("history"))
    }

    @Test fun toggleTwiceRemoves() = runTest {
        m3u.add("A")
        val a = m3u.channel("A")
        assertFalse(favourites.isFavourite(a))
        assertTrue(favourites.toggle(a))
        assertTrue(favourites.isFavourite(a))
        assertEquals(1, favourites.count.first())
        assertFalse(favourites.toggle(a))
        assertFalse(favourites.isFavourite(a))
        assertEquals(0, favourites.count.first())
    }

    @Test fun repositoryMoveReordersFavourites() = runTest {
        m3u.add("A", "B")
        favourites.toggle(m3u.channel("A"))
        favourites.toggle(m3u.channel("B"))
        assertTrue(favourites.move(m3u.channel("B"), -1))
        assertEquals(listOf("B", "A"), listed())
        assertFalse(favourites.move(m3u.channel("B"), -1))
    }

    @Test fun historyRecordsNewestFirstWithClock() = runTest {
        m3u.add("A", "B")
        history.record(m3u.channel("A"))
        history.record(m3u.channel("B"))
        history.record(m3u.channel("A"))
        assertEquals(listOf("A", "B"), history.recent().first().map { it.name })
        assertEquals(30, HISTORY_SIZE)
        assertEquals(2, rows("history"))
    }
}
