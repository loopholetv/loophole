package io.github.loopholetv.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class HistoryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: HistoryDao

    @Before fun setUp() {
        db = inMemoryDb()
        dao = db.historyDao()
    }

    @After fun tearDown() = db.close()

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    private suspend fun rows(): Int = db.rowCount("history")

    @Test fun recordMovesToFrontAndTrimsToKeep() = runTest {
        assertNull(dao.latest())
        val p = db.addPlaylist()
        val ch = db.insertChannels(*(1..32).map { channel(p, "k$it", it) }.toTypedArray())
        fun c(n: Int) = ch.getValue("k$n")
        (1..32).forEach { dao.record(c(it).ref, at(it.toLong()), keep = 30) }

        assertEquals(30, rows())
        assertEquals((32 downTo 3).toList(), dao.recent(50).first().map { it.number })
        assertEquals(listOf(32, 31), dao.recent(2).first().map { it.number })

        dao.record(c(5).ref, at(100), keep = 30)
        assertEquals(30, rows())
        assertEquals(5, dao.recent(50).first().first().number)
        assertEquals(c(5), dao.latest())

        dao.record(c(1).ref, at(101), keep = 30) // was trimmed: comes back, the oldest (3) goes
        assertEquals(30, rows())
        val recent = dao.recent(50).first().map { it.number }
        assertEquals(listOf(1, 5, 32), recent.take(3))
        assertEquals(4, recent.last())
    }

    @Test fun keepAndLimitMustBePositive() = runTest {
        val p = db.addPlaylist()
        val c = db.insertChannels(channel(p, "k", 1)).getValue("k")
        for (bad in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.record(c.ref, at(1), keep = bad) } }
            assertThrows(IllegalArgumentException::class.java) { dao.recent(bad) }
        }
        assertEquals(0, rows())
    }

    @Test fun latestSkipsVanishedChannel() = runTest {
        M3uPlaylist(db).use { m3u ->
            m3u.add("A", "B")
            dao.record(m3u.channel("A").ref, at(1), keep = 30)
            dao.record(m3u.channel("B").ref, at(2), keep = 30)
            assertEquals("B", dao.latest()?.name)

            m3u.refresh("A")
            assertEquals("A", dao.latest()?.name)
            assertEquals(listOf("A"), dao.recent(30).first().map { it.name })

            m3u.refresh("A", "B") // B returns with a new row id; history still finds it
            assertEquals("B", dao.latest()?.name)
        }
    }
}
