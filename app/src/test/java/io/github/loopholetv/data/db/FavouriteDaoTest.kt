package io.github.loopholetv.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class FavouriteDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FavouriteDao
    private lateinit var ch: Map<String, ChannelEntity>
    private var t = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        dao = db.favouriteDao()
        val p = db.addPlaylist()
        ch = db.insertChannels(*listOf("a", "b", "c", "d", "e", "f").mapIndexed { i, k -> channel(p, k, i + 1) }.toTypedArray())
    }

    @After fun tearDown() = db.close()

    private suspend fun fav(vararg keys: String) = keys.forEach { dao.add(c(it).ref, Instant.ofEpochMilli(++t)) }
    private suspend fun listed() = dao.channels().loadAll().map { it.key }
    private fun c(key: String) = ch.getValue(key)

    @Test fun newFavouritesAppendInOrderAdded() = runTest {
        fav("c", "a", "b")
        assertEquals(listOf("c", "a", "b"), listed())
        fav("c") // already present: ignored, keeps its place
        assertEquals(listOf("c", "a", "b"), listed())
        assertEquals(3, dao.count().first())
        assertEquals(listOf("c", "a", "b").map { c(it).ref }, dao.refs().first())
        assertTrue(dao.contains(c("a").playlistId, "a"))
        assertFalse(dao.contains(c("d").playlistId, "d"))
        dao.remove(c("a").playlistId, "a")
        assertEquals(listOf("c", "b"), listed())
        fav("a") // re-added: goes to the end
        assertEquals(listOf("c", "b", "a"), listed())
    }

    @Test fun moveSwapsWithVisibleNeighbourAndStopsAtEnds() = runTest {
        fav("a", "b", "c")
        assertTrue(dao.move(c("b").ref, -1))
        assertEquals(listOf("b", "a", "c"), listed())
        assertFalse(dao.move(c("b").ref, -1))
        assertFalse(dao.move(c("c").ref, +1))
        assertTrue(dao.move(c("a").ref, +1))
        assertEquals(listOf("b", "c", "a"), listed())
        assertFalse("not a favourite", dao.move(c("d").ref, -1))
    }

    @Test fun moveSkipsHiddenFavourites() = runTest {
        fav("a")
        dao.add(ChannelRef(c("a").playlistId, "x"), Instant.ofEpochMilli(++t)) // its channel is absent
        fav("c")
        assertEquals(2, dao.count().first())
        assertTrue(dao.move(c("c").ref, -1))
        assertEquals(listOf("c", "a"), listed())
        assertFalse(dao.move(c("c").ref, -1))
        assertEquals(3, dao.refs().first().size)
    }

    @Test fun favouriteNeighbourWrapsAndAroundCentres() = runTest {
        assertNull("no favourites", dao.neighbour(c("a"), +1))
        assertEquals(listOf(c("a")), dao.around(c("a"), 2))

        fav("a", "c", "e")
        assertEquals(c("c"), dao.neighbour(c("a"), +1))
        assertEquals(c("a"), dao.neighbour(c("e"), +1))
        assertEquals(c("e"), dao.neighbour(c("a"), -1))
        assertEquals(c("a"), dao.neighbour(c("c"), -1))
        // current not a favourite → first / last
        assertEquals(c("a"), dao.neighbour(c("d"), +1))
        assertEquals(c("e"), dao.neighbour(c("d"), -1))

        fav("b", "d") // order a, c, e, b, d
        assertEquals(listOf("c", "e", "b"), dao.around(c("e"), 1).map { it.key })
        assertEquals(listOf("a", "c", "e"), dao.around(c("a"), 2).map { it.key })
        assertEquals(listOf("e", "b", "d"), dao.around(c("d"), 2).map { it.key })
        assertEquals(listOf("f", "a", "c"), dao.around(c("f"), 2).map { it.key })
        assertEquals(listOf("e"), dao.around(c("e"), 0).map { it.key })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.around(c("e"), -1) } }
    }
}
